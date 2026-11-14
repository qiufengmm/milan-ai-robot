package com.milanai.robot.service;


import com.milanai.robot.model.vo.customerService.CheckFileReqVO;
import com.milanai.robot.model.vo.customerService.CheckFileRspVO;
import com.milanai.robot.model.vo.customerService.DeleteMarkdownFileReqVO;
import com.milanai.robot.model.vo.customerService.FindMarkdownFilePageListReqVO;
import com.milanai.robot.model.vo.customerService.FindMarkdownFilePageListRspVO;
import com.milanai.robot.model.vo.customerService.MergeChunkReqVO;
import com.milanai.robot.model.vo.customerService.UpdateMarkdownFileReqVO;
import com.milanai.robot.model.vo.customerService.UploadChunkReqVO;
import com.milanai.robot.utils.PageResponse;
import com.milanai.robot.utils.Response;

/**
 * @description: AI 客服
 **/
public interface CustomerService {

    /**
     * 检查文件是否存在（秒传 / 断点续传判断）
     *
     * @param checkFileReqVO 入参
     * @return 检查结果
     */
    Response<CheckFileRspVO> checkFile(CheckFileReqVO checkFileReqVO);

    /**
     * 文件分片上传
     *
     * @param uploadChunkReqVO 入参
     * @return 上传结果
     */
    Response<?> uploadChunk(UploadChunkReqVO uploadChunkReqVO);

    /**
     * 文件分片合并
     *
     * @param mergeChunkReqVO 入参
     * @return 合并结果
     */
    Response<?> mergeChunk(MergeChunkReqVO mergeChunkReqVO);

    /**
     * 删除问答文件
     * @param deleteMarkdownFileReqVO
     * @return
     */
    Response<?> deleteMarkdownFile(DeleteMarkdownFileReqVO deleteMarkdownFileReqVO);
    /**
     * 分页查询问答文件
     * @param findMarkdownFilePageListReqVO
     * @return
     */
    PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(FindMarkdownFilePageListReqVO findMarkdownFilePageListReqVO);

    /**
     * 修改问答文件信息
     * @param updateMarkdownFileReqVO
     * @return
     */
    Response<?> updateMarkdownFile(UpdateMarkdownFileReqVO updateMarkdownFileReqVO);
}
