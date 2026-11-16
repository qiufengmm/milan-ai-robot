package com.milanai.robot.exception;

import com.milanai.robot.enums.ResponseCodeEnum;
import com.milanai.robot.utils.Response;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * @Version: v1.0.0
 * @Description: 全局异常处理
 **/
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler({org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<Response<Object>> handleMissingEndpoint(Exception exception) {
        org.springframework.web.ErrorResponse error = (org.springframework.web.ErrorResponse) exception;
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .body(Response.fail("HTTP_" + error.getStatusCode().value(), "请求的接口或方法不存在"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Response<Object>> handleAccessDenied(HttpServletRequest request, AccessDeniedException e) {
        log.warn("{} access denied", request.getRequestURI());
        return ResponseEntity.status(403).body(Response.fail("FORBIDDEN", "无权执行此操作"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Response<Object>> handleResponseStatus(HttpServletRequest request, ResponseStatusException e) {
        String message = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        log.warn("{} request rejected with HTTP {}", request.getRequestURI(), e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(Response.fail("HTTP_" + e.getStatusCode().value(), message));
    }

    /**
     * 捕获自定义业务异常
     * @return
     */
    @ExceptionHandler({ BizException.class })
    @ResponseBody
    public Response<Object> handleBizException(HttpServletRequest request, BizException e) {
        log.warn("{} request fail, errorCode: {}, errorMessage: {}", request.getRequestURI(), e.getErrorCode(), e.getErrorMessage());
        return Response.fail(e);
    }

    /**
     * 捕获参数校验异常
     * @return
     */
    @ExceptionHandler({ MethodArgumentNotValidException.class })
    @ResponseBody
    public ResponseEntity<Response<Object>> handleMethodArgumentNotValidException(HttpServletRequest request, MethodArgumentNotValidException e) {
        // 参数错误异常码
        String errorCode = ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode();

        // 获取 BindingResult
        BindingResult bindingResult = e.getBindingResult();

        StringBuilder sb = new StringBuilder();

        // 获取校验不通过的字段，并组合错误信息，格式为： email 邮箱格式不正确, 当前值: '123124qq.com';
        Optional.ofNullable(bindingResult.getFieldErrors()).ifPresent(errors -> {
            errors.forEach(error ->
                    sb.append(error.getField())
                            .append(" ")
                            .append(error.getDefaultMessage())
                            .append(", 当前值: '")
                            .append(error.getRejectedValue())
                            .append("'; ")

            );
        });

        // 错误信息
        String errorMessage = sb.toString();

        log.warn("{} request error, errorCode: {}, errorMessage: {}", request.getRequestURI(), errorCode, errorMessage);

        return ResponseEntity.badRequest().body(Response.fail(errorCode, errorMessage));
    }


    /**
     * 其他类型异常
     * @param request
     * @param e
     * @return
     */
    @ExceptionHandler({ Exception.class })
    @ResponseBody
    public Response<Object> handleOtherException(HttpServletRequest request, Exception e) {
        log.error("{} request error, ", request.getRequestURI(), e);
        return Response.fail(ResponseCodeEnum.SYSTEM_ERROR);
    }

}

