package com.liteworkbench.common;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.constant.AppConst;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 请求链路标识注入：优先复用请求头，缺失时生成，贯穿日志与响应 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(AppConst.HEADER_TRACE_ID);
        if (StrUtil.isBlank(traceId)) {
            traceId = IdGenerator.raw();
        }
        MDC.put(AppConst.MDC_TRACE_ID, traceId);
        response.setHeader(AppConst.HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(AppConst.MDC_TRACE_ID);
        }
    }
}
