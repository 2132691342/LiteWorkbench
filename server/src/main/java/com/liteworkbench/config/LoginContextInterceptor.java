package com.liteworkbench.config;

import com.liteworkbench.common.LoginContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 请求进入时把登录用户放入线程上下文，请求结束清理 */
public class LoginContextInterceptor implements HandlerInterceptor {

    /** 惰性加载登录用户到 ThreadLocal */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginContext.get();
        return true;
    }

    /** 清理线程上下文，防止线程复用串号 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LoginContext.clear();
    }
}
