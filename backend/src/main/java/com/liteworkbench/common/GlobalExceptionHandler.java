package com.liteworkbench.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.util.StrUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/** 全局异常处理：业务异常按码输出，未知异常转内部错误且不回传堆栈 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        log.warn("[biz.error] code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(R.fail(e.getErrorCode(), e.getMessage()));
    }

    /** 未登录：令牌超时单独给提示 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        ErrorCodeEnum code = NotLoginException.TOKEN_TIMEOUT.equals(e.getType())
                ? ErrorCodeEnum.TOKEN_EXPIRED
                : ErrorCodeEnum.UNAUTHORIZED;
        log.warn("[auth.notlogin] type={} message={}", e.getType(), e.getMessage());
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }

    /** 无权限：缺少角色或权限点 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> handleNotPermission(Exception e) {
        log.warn("[auth.forbidden] message={}", e.getMessage());
        ErrorCodeEnum code = ErrorCodeEnum.FORBIDDEN;
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }

    /** 参数校验失败：请求体 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleBodyInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCodeEnum.PARAM_INVALID.getMessage());
        log.warn("[param.invalid] detail={}", detail);
        return badRequest(detail);
    }

    /** 参数校验失败：单参数约束 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(ErrorCodeEnum.PARAM_INVALID.getMessage());
        log.warn("[param.invalid] detail={}", detail);
        return badRequest(detail);
    }

    /** 缺少必填参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[param.missing] name={}", e.getParameterName());
        return badRequest("缺少参数：" + e.getParameterName());
    }

    /** 兜底：未知异常记 error 含堆栈，对外只给通用提示 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("[unhandled.error] type={} message={}", e.getClass().getSimpleName(), e.getMessage(), e);
        ErrorCodeEnum code = ErrorCodeEnum.INTERNAL_ERROR;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(code));
    }

    private ResponseEntity<R<Void>> badRequest(String detail) {
        ErrorCodeEnum code = ErrorCodeEnum.PARAM_INVALID;
        String message = Objects.isNull(detail) ? code.getMessage() : detail;
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code, message));
    }
}
