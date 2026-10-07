package com.somepro.application.alert;

import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 疫病预警应用层：编排预警用例（处置推进、查看、条件分页）。
 *
 * 预警没有「人来点一下立一条」的入口 —— 它在检测结果那条链上自动冒出来：样本结果
 * 一录成阳性，由 SampleTest 仓储在结果回填事务里把预警一起落了（同一份阳性样本只落一条）；
 * 这里只管立起来之后怎么往前盯：已发布→处置中→已解除→已归档。
 *
 * 解除那一步的联动（挂的上报还没结案就跟着推到已结案，已结案的照旧）由仓储层在同一
 * 事务里落；「只顺不逆不跳级、归档终态、解除不早于发布」是领域规则，由领域对象把守。
 */
@Service
public class EpiAlertAppService {

    private final EpiAlertRepository alertRepository;

    public EpiAlertAppService(EpiAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /**
     * 处置推进：已发布→处置中→已解除→已归档，只能顺着走，不能跳级不能回退，
     * 已归档的再推挡回（领域对象拦）；并发推同一单由条件更新兜底，只有一下翻得动。
     * 推到解除记下解除时刻（不能早于发布），并顺手把挂的上报收尾到已结案。
     *
     * @param disposalMethod 处置措施（处置中/解除时可带，不带不动）
     * @param resolvedAt     解除时刻（仅解除时用，不传取当下）
     */
    public Mono<EpiAlert> advance(Long id, String targetStatus,
                                  String disposalMethod, LocalDateTime resolvedAt) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")))
                .flatMap(alert -> {
                    String fromStatus = alert.getStatus();
                    alert.advance(targetStatus, normalize(disposalMethod), resolvedAt);
                    return alertRepository.advance(alert, fromStatus)
                            .flatMap(flipped -> flipped
                                    // 重查一遍：处置/解除时刻由审计列在落库时刷新，内存里的还是推进前的
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /** 查看单条在册预警。 */
    public Mono<EpiAlert> detail(Long id) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")));
    }

    /** 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。 */
    public Mono<PageResult<EpiAlert>> pageAlerts(int pageNum, int pageSize,
                                                 Long reportId, Long sampleId,
                                                 String alertLevel, String status) {
        return alertRepository.page(pageNum, pageSize, reportId, sampleId,
                normalize(alertLevel), normalize(status));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
