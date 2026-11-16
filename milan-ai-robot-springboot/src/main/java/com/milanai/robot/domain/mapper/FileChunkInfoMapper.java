package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.FileChunkInfoDO;

import java.util.List;

/**
 * @Description: 分片信息表
 **/
public interface FileChunkInfoMapper extends BaseMapper<FileChunkInfoDO> {

    default List<FileChunkInfoDO> selectChunkedList(String md5, Long kb) {
        return selectList(Wrappers.<FileChunkInfoDO>lambdaQuery()
                .eq(FileChunkInfoDO::getFileMd5, md5).eq(FileChunkInfoDO::getKnowledgeBaseId, kb)
                .orderByAsc(FileChunkInfoDO::getChunkNumber));
    }

    default int deleteByMd5(String md5, Long kb) {
        return delete(Wrappers.<FileChunkInfoDO>lambdaQuery()
                .eq(FileChunkInfoDO::getFileMd5, md5).eq(FileChunkInfoDO::getKnowledgeBaseId, kb));
    }

}
