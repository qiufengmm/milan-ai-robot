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

    /**
     * 根据文件 MD5 值查询所有已上传的分片，按分片序号升序。
     * <p>
     * 顺序必须是升序：合并大文件时依赖这个顺序拼接，乱序会得到一个内容错乱的文件。
     *
     * @param fileMd5 文件 MD5
     * @return 分片记录集合
     */
    default List<FileChunkInfoDO> selectChunkedList(String fileMd5) {
        return selectList(
                Wrappers.<FileChunkInfoDO>lambdaQuery()
                        .eq(FileChunkInfoDO::getFileMd5, fileMd5)
                        .orderByAsc(FileChunkInfoDO::getChunkNumber)
        );
    }

    /**
     * 查询指定分片是否已被上传（断点续传时重复提交同一个分片，直接跳过）
     *
     * @param fileMd5  文件 MD5
     * @param chunkNum 分片序号
     * @return 命中条数
     */
    default Long selectCountByMd5AndChunkNum(String fileMd5, Integer chunkNum) {
        return selectCount(
                Wrappers.<FileChunkInfoDO>lambdaQuery()
                        .eq(FileChunkInfoDO::getFileMd5, fileMd5)
                        .eq(FileChunkInfoDO::getChunkNumber, chunkNum)
        );
    }

    /**
     * 根据文件 MD5 删除该文件的所有分片记录（合并完成 / 删除文件后清理）
     *
     * @param fileMd5 文件 MD5
     * @return 影响行数
     */
    default int deleteByMd5(String fileMd5) {
        return delete(Wrappers.<FileChunkInfoDO>lambdaQuery()
                .eq(FileChunkInfoDO::getFileMd5, fileMd5));
    }
}
