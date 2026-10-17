package com.liteworkbench.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/** 拦截器包装：SSE 等异步请求收尾时会重跑一次拦截器链，此时 Sa-Token 上下文已失效，必须跳过 */
public class AsyncSafeInterceptor implements AsyncHandlerInterceptor {

    private final HandlerInterceptor delegate;

    public AsyncSafeInterceptor(HandlerInterceptor delegate) {
        this.delegate = delegate;
    }

    /** 异步派发不重复跑前置逻辑 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        return isAsyncDispatch(request) || delegate.preHandle(request, response, handler);
    }

    /** 异步派发跳过视图收尾 */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) throws Exception {
        if (!isAsyncDispatch(request)) {
            delegate.postHandle(request, response, handler, modelAndView);
        }
    }

    /** 仅同步请求回收上下文 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        if (!isAsyncDispatch(request)) {
            delegate.afterCompletion(request, response, handler, ex);
        }
    }

    /** 异步开始是原始请求上的回调而非异步派发，必须透传；被包装的拦截器不关心异步时无操作 */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) throws Exception {
        if (delegate instanceof AsyncHandlerInterceptor asyncDelegate) {
            asyncDelegate.afterConcurrentHandlingStarted(request, response, handler);
        }
    }

    /** 异步派发判定：此时请求线程已与原始请求上下文脱钩 */
    static boolean isAsyncDispatch(HttpServletRequest request) {
        return DispatcherType.ASYNC.equals(request.getDispatcherType());
    }
}
