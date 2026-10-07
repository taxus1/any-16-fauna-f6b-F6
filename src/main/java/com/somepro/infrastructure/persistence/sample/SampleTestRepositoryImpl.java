package com.somepro.infrastructure.persistence.sample;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.domain.sample.repository.SampleTestRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.alert.EpiAlertRepositoryImpl;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.obs.WildlifeObsMapper;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
import com.somepro.infrastructure.persistence.sample.converter.SampleTestPoConverter;
import com.somepro.infrastructure.persistence.sample.po.SampleTestPO;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 采样送检与检测仓储适配器（基础设施层）。
 *
 * 编号分配：sampleNo 按 SM-YYYY-NNNN 生成（年份按登记当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 删除记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 检测结果回填是「两头一起动」：一个事务里先按 result=PENDING 条件更新样本
 * （同一条样本的结果只翻得动一次，并发/重复录入在这被拦），再把上报从在办
 * （已上报/处置中）条件更新推到已采样；上报已不在在办的 —— 已采样是幂等放行
 * （同一份上报前一条样本录结果时推过），已救护/已结案/已作废则整体回滚报错，
 * 样本那行也不落。
 *
 * 阳性还多生一头：结果一录成阳性，预警不用人另外去点，同一事务里跟着立一条 ——
 * 级别在上报严重程度之上叠观测上的保护级别快照；样本那行的条件更新已把并发回填
 * 串行化（后到者在样本行锁上排队），再按 sample_id 数一道，同一份阳性样本前后脚
 * 递两回也只落一条预警。阴性/不确定不立。
 */
@Repository
public class SampleTestRepositoryImpl implements SampleTestRepository {

    /** 编号前缀：SM-（完整形如 SM-2026-） */
    private static final String NO_PREFIX = "SM-";

    private final SampleTestMapper sampleMapper;
    private final AbnormalReportMapper reportMapper;
    private final WildlifeObsMapper obsMapper;
    private final EpiAlertRepositoryImpl alertRepository;
    private final TransactionTemplate transactionTemplate;

    public SampleTestRepositoryImpl(SampleTestMapper sampleMapper,
                                    AbnormalReportMapper reportMapper,
                                    WildlifeObsMapper obsMapper,
                                    EpiAlertRepositoryImpl alertRepository,
                                    PlatformTransactionManager transactionManager) {
        this.sampleMapper = sampleMapper;
        this.reportMapper = reportMapper;
        this.obsMapper = obsMapper;
        this.alertRepository = alertRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<SampleTest> create(SampleTest sample) {
        return blocking(() -> {
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> sampleMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(sample, no));
        });
    }

    @Override
    public Mono<SampleTest> findById(Long id) {
        return blocking(() -> {
            SampleTestPO po = sampleMapper.selectById(id);
            return po == null ? null : SampleTestPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<SampleTest> recordResult(SampleTest sample) {
        return blocking(() -> transactionTemplate.execute(txStatus -> {
            // 样本侧：按 result=PENDING 条件更新，同一条样本的结果只翻得动一次；
            // 已出结果的再来录，0 行拦下（领域层已拦一道，这里兜并发）。
            SampleTestPO samplePo = new SampleTestPO();
            samplePo.setResult(sample.getResult());
            samplePo.setTestedAt(sample.getTestedAt());
            int sampleRows = sampleMapper.update(samplePo, Wrappers.<SampleTestPO>lambdaUpdate()
                    .eq(SampleTestPO::getId, sample.getId())
                    .eq(SampleTestPO::getResult, SampleTest.RESULT_PENDING));
            if (sampleRows != 1) {
                throw new BizException("该样本检测结果已录入，同一条样本不重复录入");
            }
            // 上报侧：从在办（已上报/处置中）推到已采样，与样本结果同一个事务落。
            AbnormalReportPO reportPo = new AbnormalReportPO();
            reportPo.setStatus(AbnormalReport.STATUS_SAMPLED);
            int reportRows = reportMapper.update(reportPo, Wrappers.<AbnormalReportPO>lambdaUpdate()
                    .eq(AbnormalReportPO::getId, sample.getReportId())
                    .in(AbnormalReportPO::getStatus,
                            AbnormalReport.STATUS_REPORTED, AbnormalReport.STATUS_HANDLING));
            if (reportRows != 1) {
                // 0 行 = 上报已不在在办：已采样是幂等（同一份上报前一条样本录结果时推过），
                // 放行；已救护/已结案则这单已不走采样线，已作废的查不到，一并回滚报错。
                AbnormalReportPO current = reportMapper.selectById(sample.getReportId());
                if (current == null) {
                    throw new BizException("异常上报不存在或已作废，检测结果回填失败");
                }
                if (!AbnormalReport.STATUS_SAMPLED.equals(current.getStatus())) {
                    throw new BizException("上报已结案或已不走采样线，检测结果回填失败");
                }
            }
            // 阳性预警：结果一录成阳性，预警不用人另外去点，同一事务里跟着立一条；
            // 阴性/不确定不立。级别 = 上报严重程度之上叠观测上抄的保护级别快照（领域算）。
            if (SampleTest.RESULT_POSITIVE.equals(sample.getResult())) {
                AbnormalReportPO report = reportMapper.selectById(sample.getReportId());
                String protectionLevel = obsMapper.selectProtectionLevelById(report.getObsId());
                EpiAlert alert = EpiAlert.raise(report.getId(), sample.getId(),
                        report.getSeverity(), protectionLevel, null);
                alertRepository.doCreateAuto(alert);
            }
            return SampleTestPoConverter.toDomain(sampleMapper.selectById(sample.getId()));
        }));
    }

    @Override
    public Mono<PageResult<SampleTest>> page(int pageNum, int pageSize,
                                             Long reportId, String sampleType, String result) {
        return this.<PageResult<SampleTest>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<SampleTestPO> wrapper = Wrappers.<SampleTestPO>lambdaQuery()
                        .eq(reportId != null, SampleTestPO::getReportId, reportId)
                        .eq(hasText(sampleType), SampleTestPO::getSampleType, sampleType)
                        .eq(hasText(result), SampleTestPO::getResult, result)
                        .orderByAsc(SampleTestPO::getId);
                List<SampleTestPO> rows = sampleMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<SampleTest> content = rows.stream()
                        .map(SampleTestPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    private SampleTest doInsert(SampleTest sample, String sampleNo) {
        sample.setSampleNo(sampleNo);
        SampleTestPO po = SampleTestPoConverter.toPo(sample);
        po.setId(IdUtil.getSnowflakeNextId());
        sampleMapper.insert(po);
        return SampleTestPoConverter.toDomain(po);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 阻塞 DB 调用 → 响应式链路的桥接器：先取 Reactor Context 里的操作人，
     * 再切到 boundedElastic 执行 JDBC，操作人放进 AuditContextHolder 供审计填充。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
