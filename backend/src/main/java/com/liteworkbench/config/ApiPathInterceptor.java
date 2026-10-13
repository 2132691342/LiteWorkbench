package com.liteworkbench.config;

import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** /api 路径兜底：未命中任何业务接口时回 404 信封，避免被前端深链兜底当成页面返回 HTML */
@Slf4j
public class ApiPathInterceptor implements HandlerInterceptor {

    /** 命中非控制器处理器（深链视图控制器 / 静态资源）即视为接口不存在 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (handler instanceof HandlerMethod) {
            return true;
        }
        ErrorCodeEnum code = ErrorCodeEnum.RESOURCE_NOT_FOUND;
        log.warn("[api.notfound] uri={} handler={}", request.getRequestURI(), handler.getClass().getSimpleName());
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(R.fail(code)));
        return false;
    }
}
