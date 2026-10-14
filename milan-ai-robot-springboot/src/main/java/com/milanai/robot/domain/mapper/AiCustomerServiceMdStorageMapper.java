package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.milanai.robot.domain.dos.AiCustomerServiceMdStorageDO;

import java.time.LocalDate;
import java.util.Objects;

/**
 * @Description: TODO
 **/
public interface AiCustomerServiceMdStorageMapper extends BaseMapper<AiCustomerServiceMdStorageDO> {

    /**
     * 分页查询
     * @param current
     * @param size
     * @param fileName 文件名称（模糊查询）
     * @param startDate 起始日期
     * @param endDate 结束日期
     * @return
     */
    default Page<AiCustomerServiceMdStorageDO> selectPageList(Long current, Long size, String fileName, LocalDate startDate, LocalDate endDate) {
        // 分页对象(查询第几页、每页多少数据)
        Page<AiCustomerServiceMdStorageDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<AiCustomerServiceMdStorageDO> wrapper = Wrappers.<AiCustomerServiceMdStorageDO>lambdaQuery()
                .like(StringUtils.isNotBlank(fileName), AiCustomerServiceMdStorageDO::getOriginalFileName, fileName) // like 模糊查询
                .ge(Objects.nonNull(startDate), AiCustomerServiceMdStorageDO::getCreateTime, startDate) // 大于等于 startDate
                .lt(Objects.nonNull(endDate), AiCustomerServiceMdStorageDO::getCreateTime, endDate) // 小于 endDate
                .orderByDesc(AiCustomerServiceMdStorageDO::getCreateTime); // 按创建时间倒序

        return selectPage(page, wrapper);
    }
}
