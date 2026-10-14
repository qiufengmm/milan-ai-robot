package com.milanai.robot.model.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * @description: 分页查询基类
 **/
@Data
public class BasePageQuery {
    /**
     * 当前页码, 默认第一页
     */
    @Min(value = 1, message = "当前页码不能小于 1")
    private Long current = 1L;
    /**
     * 每页展示的数据数量，默认每页展示 10 条数据，单页上限 100 条
     */
    @Min(value = 1, message = "每页展示数量不能小于 1")
    @Max(value = 100, message = "每页展示数量不能超过 100")
    private Long size = 10L;
}

