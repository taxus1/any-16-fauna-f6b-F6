package com.somepro.domain.alert.repository;

import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 疫病预警的仓储端口（领域层定义，基础设施层实现）。
 */
public interface EpiAlertRepository {

    /**
     * 预警落库；alertNo 由实现侧按 AL-YYYY-NNNN 生成，并发撞号自动重取，不甩底层冲突。
     * 「同一份阳性样本只落一条预警」由检测结果回填那条事务串行化兜底，不由本方法保证。
     */
    Mono<EpiAlert> create(EpiAlert alert);

    /**
     * 按 id 查看在册预警（del_flag=0）。
     */
    Mono<EpiAlert> findById(Long id);

    /**
     * 处置推进落库：按原状态条件更新（WHERE id=? AND status=fromStatus），状态只翻动一次。
     * 并发推同一单只有一下翻得动，另一下返回 false；推进时刻由审计列 update_time 记下。
     *
     * 推到「已解除」时顺手收尾挂的那条上报：还没结案的跟着推到已结案（按非结案条件更新，
     * 已结案的照旧不动）；两头一个事务，要么一起成要么一起回。
     *
     * @param fromStatus 推进前的原状态（应用层加载时读到的那个）
     * @return true 翻动成功；false 预警已不在原状态（被并发翻动）
     */
    Mono<Boolean> advance(EpiAlert alert, String fromStatus);

    /**
     * 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。
     */
    Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                    Long reportId, Long sampleId,
                                    String alertLevel, String status);
}
