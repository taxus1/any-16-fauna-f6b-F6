package com.somepro.interfaces.rest.alert.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 疫病预警对外返回对象（VO，用户接口层）—— 不可变 record。预警编号 alertNo 必带，
 * 跟处置台账对得上号。
 *
 * 处置中→已解除之外没有专列时刻：发布看 raisedAt、解除看 resolvedAt；最近一次推进时刻
 * 取审计列 updateTime（handledAt）—— 每次推进走 UPDATE 时由 MetaObjectHandler 自动刷新。
 */
public record AlertVO(Long id, String alertNo, Long reportId, Long sampleId, String alertLevel,
                      String status, String disposalMethod, LocalDateTime raisedAt,
                      LocalDateTime resolvedAt, LocalDateTime handledAt,
                      LocalDateTime createTime) implements Serializable {
}
