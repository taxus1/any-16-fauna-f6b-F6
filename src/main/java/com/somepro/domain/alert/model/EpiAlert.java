package com.somepro.domain.alert.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.species.model.Species;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 疫病预警聚合根（领域层）：阳性检测结果一出，预警不用人另外去点，自己跟着冒出来；
 * 立起来先落在已发布，后面按处置一路盯到解除、归档。
 *
 * 业务规则：
 * - 一条预警挂一条异常上报（reportId）、钉一份阳性样本（sampleId）；同一份阳性样本
 *   只该有一条预警（仓储层在结果回填事务内串行化兜底，前后脚递两回也只落一条）；
 * - 级别不能随手填，得跟这单的来头对得上：上报那头已按上报类别判过严重程度
 *   （MEDIUM/HIGH，口径定死在 {@link AbnormalReport}），预警在它之上再叠一层
 *   观测上抄下来的物种保护级别快照（名录后来怎么改都不跟），保护得越重、级别越高，
 *   落到蓝/黄/橙/红四档（{@link #deriveLevel}），不另造一套口径；
 * - 状态机只顺不逆：已发布 RAISED → 处置中 HANDLING → 已解除 RESOLVED → 已归档 CLOSED，
 *   不能跳级、不能回退；归档是终态，再推挡回；
 * - 每推一步记下时刻：发布时刻 {@link #raisedAt} 立单时记，解除时刻 {@link #resolvedAt}
 *   推到解除时记（解除不能早于发布）；处置中/归档的时刻没有专列，由审计列 update_time 承担。
 *
 * 「解除时把挂的那条上报一并收尾（还没结案的推到已结案）」要查/写上报仓储，
 * 由仓储层在同一事务里落，领域对象只保证自身字段不变量。
 *
 * 编号 alertNo 由仓储层在落库时分配（AL-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class EpiAlert extends BaseEntity {

    /** 预警级别：蓝色 */
    public static final String LEVEL_BLUE = "BLUE";
    /** 预警级别：黄色 */
    public static final String LEVEL_YELLOW = "YELLOW";
    /** 预警级别：橙色 */
    public static final String LEVEL_ORANGE = "ORANGE";
    /** 预警级别：红色 */
    public static final String LEVEL_RED = "RED";

    /** 状态：已发布（起点） */
    public static final String STATUS_RAISED = "RAISED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已解除 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 状态：已归档（终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 可推进到的目标状态（RAISED 是起点，不是推进目标） */
    private static final Set<String> ADVANCE_TARGETS = Set.of(
            STATUS_HANDLING, STATUS_RESOLVED, STATUS_CLOSED);

    /** 级别由轻到重的次序，叠保护级别时只往上抬、不往下压。 */
    private static final List<String> LEVEL_ORDER =
            List.of(LEVEL_BLUE, LEVEL_YELLOW, LEVEL_ORANGE, LEVEL_RED);

    /**
     * 上报严重程度 → 预警基准档：上报那套口径定死在仓库里（死亡/疑似疫病一律 HIGH，
     * 受伤看保护级别快照分 HIGH/MEDIUM），预警照着往上接，不另造一套。
     */
    private static final Map<String, String> SEVERITY_BASE = Map.of(
            AbnormalReport.SEVERITY_MEDIUM, LEVEL_YELLOW,
            AbnormalReport.SEVERITY_HIGH, LEVEL_ORANGE);

    /**
     * 保护级别快照 → 在基准档之上再抬几档：保护得越重抬得越高，只叠不压。
     * 一般物种不抬（守上报判下来的基准），省级抬一档，国家二级抬两档，国家一级抬到红。
     */
    private static final Map<String, Integer> PROTECTION_LIFT = Map.of(
            Species.LEVEL_COMMON, 0,
            Species.LEVEL_PROVINCIAL, 1,
            Species.LEVEL_NATIONAL_TWO, 2,
            Species.LEVEL_NATIONAL_ONE, 3);

    private Long id;

    /** 预警编号（如 AL-2026-0001），全局唯一 */
    private String alertNo;

    /** 挂在哪条异常上报上（t_abnormal_report.id） */
    private Long reportId;

    /** 钉的是哪份阳性样本（t_sample_test.id），一份阳性样本只挂一条预警 */
    private Long sampleId;

    /** 预警级别：BLUE / YELLOW / ORANGE / RED（系统按上报严重程度 + 保护级别快照算） */
    private String alertLevel;

    /** 状态：RAISED / HANDLING / RESOLVED / CLOSED */
    private String status;

    /** 处置措施 */
    private String disposalMethod;

    /** 预警发布时刻（立单时记） */
    private LocalDateTime raisedAt;

    /** 解除时刻（推到已解除时记；处置中/归档没有专列，时刻看审计列 update_time） */
    private LocalDateTime resolvedAt;

    /**
     * 工厂方法：一份阳性样本录成阳性，预警自己跟着冒出来，先落在已发布。
     *
     * @param reportSeverity      挂的那条上报当初按上报类别判下的严重程度（MEDIUM/HIGH）
     * @param obsProtectionLevel  来源观测上抄的物种保护级别快照（不跟名录后来的改动跑）
     * @param raisedAt            发布时刻，null 时取立单当下
     */
    public static EpiAlert raise(Long reportId, Long sampleId, String reportSeverity,
                                 String obsProtectionLevel, LocalDateTime raisedAt) {
        EpiAlert alert = new EpiAlert();
        alert.attachReport(reportId);
        alert.attachSample(sampleId);
        alert.alertLevel = deriveLevel(reportSeverity, obsProtectionLevel);
        alert.status = STATUS_RAISED;
        alert.raisedAt = raisedAt != null ? raisedAt : LocalDateTime.now();
        return alert;
    }

    public void attachReport(Long reportId) {
        if (reportId == null) {
            throw new BizException("所挂上报不能为空");
        }
        this.reportId = reportId;
    }

    public void attachSample(Long sampleId) {
        if (sampleId == null) {
            throw new BizException("阳性样本不能为空");
        }
        this.sampleId = sampleId;
    }

    /**
     * 处置推进：已发布→处置中→已解除→已归档，只能顺着走，不能跳级也不能回退；
     * 已归档是终态，再推挡回。
     *
     * 推进走「按原状态条件更新」落库（仓储层卡原状态），并发推同一单只有一下翻得动；
     * 发布/解除之外的推进时刻由审计列 update_time 记下。
     *
     * @param targetStatus   目标状态 HANDLING / RESOLVED / CLOSED
     * @param disposalMethod 处置措施（处置中/解除时可记，null 表示不动）
     * @param resolvedAt     解除时刻（仅推到已解除时用，null 取当下；不能早于发布时刻）
     */
    public void advance(String targetStatus, String disposalMethod, LocalDateTime resolvedAt) {
        if (targetStatus == null || !ADVANCE_TARGETS.contains(targetStatus.trim())) {
            throw new BizException("目标状态非法，仅支持 HANDLING/RESOLVED/CLOSED");
        }
        String target = targetStatus.trim();
        if (STATUS_CLOSED.equals(this.status)) {
            throw new BizException("预警已归档，不能再推进处置");
        }
        boolean allowed = switch (this.status) {
            case STATUS_RAISED -> STATUS_HANDLING.equals(target);
            case STATUS_HANDLING -> STATUS_RESOLVED.equals(target);
            case STATUS_RESOLVED -> STATUS_CLOSED.equals(target);
            default -> false;
        };
        if (!allowed) {
            throw new BizException("处置只能顺着走：已发布→处置中→已解除→已归档，不能跳级也不能回退");
        }
        if (disposalMethod != null && !disposalMethod.isBlank()) {
            this.disposalMethod = disposalMethod.trim();
        }
        if (STATUS_RESOLVED.equals(target)) {
            LocalDateTime at = resolvedAt != null ? resolvedAt : LocalDateTime.now();
            if (this.raisedAt != null && at.isBefore(this.raisedAt)) {
                throw new BizException("解除时刻不能早于预警发布时刻");
            }
            this.resolvedAt = at;
        }
        this.status = target;
    }

    /**
     * 级别推算：在上上报判下的严重程度基准档上，再叠观测保护级别快照那一层，
     * 保护越重抬得越高，封顶红档。来源口径都定死在仓库里，这里只做衔接，不另造一套。
     */
    private static String deriveLevel(String reportSeverity, String obsProtectionLevel) {
        String base = SEVERITY_BASE.get(reportSeverity);
        if (base == null) {
            // 上报严重程度是登记时按定死口径算的，只会是 MEDIUM/HIGH；走到这说明数据来路不正
            throw new BizException("上报严重程度非法，预警级别无从接起");
        }
        int baseIndex = LEVEL_ORDER.indexOf(base);
        int lift = PROTECTION_LIFT.getOrDefault(obsProtectionLevel, 0);
        int index = Math.min(baseIndex + lift, LEVEL_ORDER.size() - 1);
        return LEVEL_ORDER.get(index);
    }
}
