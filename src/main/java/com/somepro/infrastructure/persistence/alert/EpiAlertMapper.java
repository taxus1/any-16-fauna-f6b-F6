package com.somepro.infrastructure.persistence.alert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 疫病预警与处置的 MyBatis-Plus Mapper（基础设施层）。
 *
 * 阻塞（JDBC）API，只能在 boundedElastic 线程上调用（见 EpiAlertRepositoryImpl#blocking）。
 */
@Mapper
public interface EpiAlertMapper extends BaseMapper<EpiAlertPO> {

    /**
     * 查指定号段内已用的最大序号（编号生成用）。
     *
     * 自定义 @Select 不会被 @TableLogic 自动拼 del_flag 条件 —— 这是有意的：
     * 已删除预警占用的编号也不复用，一个号永远只归一条预警。
     *
     * @param prefix   编号前缀（如 "AL-2026-"）
     * @param seqStart 序号在编号串中的起始位置（SQL SUBSTRING 从 1 开始，即 prefix 长度 + 1）
     */
    @Select("SELECT MAX(CAST(SUBSTRING(alert_no, #{seqStart}) AS UNSIGNED)) "
            + "FROM t_epi_alert WHERE alert_no LIKE CONCAT(#{prefix}, '%')")
    Long selectMaxSeq(@Param("prefix") String prefix, @Param("seqStart") int seqStart);

    /**
     * 数一份样本名下已落的预警（预警自动生成去重用）。
     *
     * 自定义 @Select 不拼 del_flag —— 预警不走作废，这条计数只认 sample_id；
     * 同一份阳性样本不管库里是何状态，有且只能有一条。
     */
    @Select("SELECT COUNT(*) FROM t_epi_alert WHERE sample_id = #{sampleId}")
    Long countBySample(@Param("sampleId") Long sampleId);
}
