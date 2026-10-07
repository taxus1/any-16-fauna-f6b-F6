package com.somepro.infrastructure.persistence.alert;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.alert.converter.EpiAlertPoConverter;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.report.AbnormalReportMapper;
import com.somepro.infrastructure.persistence.report.po.AbnormalReportPO;
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
 * 疫病预警仓储适配器（基础设施层）。
 *
 * 编号分配：alertNo 按 AL-YYYY-NNNN 生成（年份按发布当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 删除记录占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 预警的「生」不在自己这条链上点，而在检测结果回填事务里由 SampleTestRepositoryImpl 调
 * {@link #doCreateAuto} 落：那条事务先条件更新样本（行锁已把同一样本的并发回填串行化），
 * 再 countBySample 兜底 —— 同一份阳性样本前后脚递两回，后到的数到已有一条，整体回滚，
 * 只落一条，也不甩底层 DuplicateKeyException。
 *
 * 解除是「两头一起动」：一个事务里先按原状态条件更新预警（并发推同一单只有一下翻得动），
 * 再把挂的那条上报顺手收尾 —— 还没结案的按 status != CLOSED 条件更新推到已结案，
 * 已结案的 0 行照旧放行。
 */
@Repository
public class EpiAlertRepositoryImpl implements EpiAlertRepository {

    /** 编号前缀：AL-（完整形如 AL-2026-） */
    private static final String NO_PREFIX = "AL-";

    private final EpiAlertMapper alertMapper;
    private final AbnormalReportMapper reportMapper;
    private final TransactionTemplate transactionTemplate;

    public EpiAlertRepositoryImpl(EpiAlertMapper alertMapper,
                                  AbnormalReportMapper reportMapper,
                                  PlatformTransactionManager transactionManager) {
        this.alertMapper = alertMapper;
        this.reportMapper = reportMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<EpiAlert> create(EpiAlert alert) {
        return blocking(() -> insertWithNo(alert));
    }

    @Override
    public Mono<EpiAlert> findById(Long id) {
        return blocking(() -> {
            EpiAlertPO po = alertMapper.selectById(id);
            return po == null ? null : EpiAlertPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> advance(EpiAlert alert, String fromStatus) {
        return blocking(() -> transactionTemplate.execute(txStatus -> {
            // 条件更新：只有仍处原状态的那一行才翻得动（并发推同一单只放行一下）；
            // SET 只带非空字段：处置措施传了才记，解除时刻只在解除那步带；推进时刻由
            // 审计列 update_time 自动记下，del_flag=0 由 @TableLogic 拼上。
            EpiAlertPO po = new EpiAlertPO();
            po.setStatus(alert.getStatus());
            if (alert.getDisposalMethod() != null) {
                po.setDisposalMethod(alert.getDisposalMethod());
            }
            if (alert.getResolvedAt() != null) {
                po.setResolvedAt(alert.getResolvedAt());
            }
            int rows = alertMapper.update(po, Wrappers.<EpiAlertPO>lambdaUpdate()
                    .eq(EpiAlertPO::getId, alert.getId())
                    .eq(EpiAlertPO::getStatus, fromStatus));
            if (rows != 1) {
                return false;
            }
            if (EpiAlert.STATUS_RESOLVED.equals(alert.getStatus())) {
                // 顺手收尾挂的那条上报：还没结案的跟着推到已结案；已结案的 0 行照旧。
                // ne 条件天然不含 CLOSED；del_flag=0 由 @TableLogic 拼上，已作废的不动。
                AbnormalReportPO close = new AbnormalReportPO();
                close.setStatus(AbnormalReport.STATUS_CLOSED);
                reportMapper.update(close, Wrappers.<AbnormalReportPO>lambdaUpdate()
                        .eq(AbnormalReportPO::getId, alert.getReportId())
                        .ne(AbnormalReportPO::getStatus, AbnormalReport.STATUS_CLOSED));
            }
            return true;
        }));
    }

    @Override
    public Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                           Long reportId, Long sampleId,
                                           String alertLevel, String status) {
        return this.<PageResult<EpiAlert>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<EpiAlertPO> wrapper = Wrappers.<EpiAlertPO>lambdaQuery()
                        .eq(reportId != null, EpiAlertPO::getReportId, reportId)
                        .eq(sampleId != null, EpiAlertPO::getSampleId, sampleId)
                        .eq(hasText(alertLevel), EpiAlertPO::getAlertLevel, alertLevel)
                        .eq(hasText(status), EpiAlertPO::getStatus, status)
                        .orderByAsc(EpiAlertPO::getId);
                List<EpiAlertPO> rows = alertMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<EpiAlert> content = rows.stream()
                        .map(EpiAlertPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /**
     * 自动预警落库：供检测结果回填事务在同一事务内直接调用（不经响应式桥接 ——
     * 调用方本身已跑在 boundedElastic + 外层事务里）。
     * 先数这份样本名下是否已有预警兜底，再取号落库 —— 一份阳性样本只落一条。
     *
     * @return 落库后的预警
     */
    public EpiAlert doCreateAuto(EpiAlert alert) {
        Long exists = alertMapper.countBySample(alert.getSampleId());
        if (exists != null && exists > 0) {
            throw new BizException("该阳性样本已生成过预警，同一份阳性样本只立一条");
        }
        return insertWithNo(alert);
    }

    private EpiAlert insertWithNo(EpiAlert alert) {
        String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
        return BizNoGenerator.insertWithRetry(
                () -> alertMapper.selectMaxSeq(prefix, prefix.length() + 1),
                prefix,
                no -> doInsert(alert, no));
    }

    private EpiAlert doInsert(EpiAlert alert, String alertNo) {
        alert.setAlertNo(alertNo);
        EpiAlertPO po = EpiAlertPoConverter.toPo(alert);
        po.setId(IdUtil.getSnowflakeNextId());
        alertMapper.insert(po);
        return EpiAlertPoConverter.toDomain(po);
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
