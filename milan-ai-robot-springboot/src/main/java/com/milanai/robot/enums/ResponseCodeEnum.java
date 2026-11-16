package com.milanai.robot.enums;

import com.milanai.robot.exception.BaseExceptionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @description: 响应异常码
 **/
@Getter
@AllArgsConstructor
public enum ResponseCodeEnum implements BaseExceptionInterface {

    // ----------- 通用异常状态码 -----------
    SYSTEM_ERROR("10000", "出错啦，后台小哥正在努力修复中..."),
    PARAM_NOT_VALID("10001", "参数错误"),


    // ----------- 业务异常状态码 -----------
    UNSUPPORTED_FILE_TYPE("20002", "不支持的文件格式，仅支持 Markdown（.md / .markdown）、PDF（.pdf）、Word（.doc / .docx）、TXT（.txt）"),
    UPLOAD_FILE_FAILED("20003", "文件上传失败"),
    MARKDOWN_FILE_NOT_FOUND("20004", "问答文件不存在"),
    MARKDOWN_FILE_CANT_DELETE("20005", "正在处理中的问答文件，不允许删除"),
    MERGE_CHUNK_NOT_FOUND("20006", "合并的分片文件不存在"),
    CHUNK_NUM_NOT_COMPLETE("20007", "分片数量不完整"),
    KNOWLEDGE_BASE_NOT_FOUND("20008", "知识库不存在"),
    FILE_INTEGRITY_FAILED("20009", "文件大小或 MD5 校验失败，请重新上传"),
    UPLOAD_CONFLICT("20010", "上传任务的文件信息不一致"),
    UPLOAD_SIZE_EXCEEDED("20011", "分片累计大小超过声明的文件大小"),
    ;

    // 异常码
    private String errorCode;
    // 错误信息
    private String errorMessage;

}

