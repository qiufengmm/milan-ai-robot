package com.milanai.robot.model.vo.customerService;

import com.milanai.robot.model.common.BasePageQuery;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;


/**
 * @description: 查询 Markdown 问答文件列表
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindMarkdownFilePageListReqVO extends BasePageQuery {

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 起始日期
     */
    private LocalDate startDate;

    /**
     * 结束日期
     */
    private LocalDate endDate;
}
