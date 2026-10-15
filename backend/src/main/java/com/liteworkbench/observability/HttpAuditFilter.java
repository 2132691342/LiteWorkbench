package com.liteworkbench.observability;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.config.ObserveProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** HTTP 审计：记录方法、URI、查询串、关键请求头、请求体与响应体（脱敏截断）与耗时，慢请求与 5xx 升级为 warn */
@Slf4j
@Component
@RequiredArgsConstructor
// 必须晚于 TraceFilter：traceId 由它入 MDC，本过滤器输出的日志才能带上链路标识
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpAuditFilter extends OncePerRequestFilter {

    /** ID 形态的路径片段：业务前缀 + ULID、UUID、纯数字长串 */
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(?:[A-Z][A-Z0-9]*_[0-9A-Z]{20,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9]{8,})$");
    /** 请求体缓存上限：只为了日志采样，超出一律不缓存，避免大上传把堆吃掉 */
    private static final int REQUEST_CACHE_LIMIT = 4096;
    /** 不记录响应体的内容类型：SPA 页面与二进制资源，只留状态码与耗时 */
    private static final List<String> SILENT_RESPONSE_TYPES = List.of(
            "text/html", "image/", "font/", "audio/", "video/",
            "application/pdf", "application/octet-stream", "application/zip");

    private final ObserveProperties observeProperties;
    private final ObserveStats observeStats;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!observeProperties.getHttp().isEnabled()) {
            return true;
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(method) || "HEAD".equals(method)) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String prefix : observeProperties.getHttp().getSkipPrefixes()) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // SSE 与异步响应绝不能包缓存包装器：包装会吞掉流式写出，长连接永远不会 flush
        boolean stream = isStream(request);
        boolean cacheBody = hasTextBody(request);
        HttpServletRequest wrapped = cacheBody
                ? new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT)
                : request;
        HttpServletResponse target = stream ? response : new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(wrapped, target);
        } finally {
            long cost = System.currentTimeMillis() - start;
            String requestBody = cacheBody
                    ? text(((ContentCachingRequestWrapper) wrapped).getContentAsByteArray())
                    : "";
            logRequest(request, requestBody);
            if (stream) {
                logResponse(request, response.getStatus(), cost, "");
            } else {
                ContentCachingResponseWrapper cached = (ContentCachingResponseWrapper) target;
                String type = StrUtil.nullToEmpty(cached.getContentType()).toLowerCase(Locale.ROOT);
                String responseBody = silent(type) ? "" : text(cached.getContentAsByteArray());
                // 必须把缓存的响应体写回真实响应，否则整条链路拿不到任何内容
                cached.copyBodyToResponse();
                logResponse(request, cached.getStatus(), cost, responseBody);
            }
            observeStats.recordHttp(routeKey(request), cost, statusOf(target, response, stream), request.getQueryString());
        }
    }

    private void logRequest(HttpServletRequest request, String body) {
        log.info("[http.req] method={} uri={} query={} ip={} headers={} body={}",
                request.getMethod(), request.getRequestURI(), StrUtil.nullToEmpty(request.getQueryString()),
                clientIp(request), headers(request), body);
    }

    private void logResponse(HttpServletRequest request, int status, long cost, String body) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if (status >= 500) {
            log.warn("[http.error] method={} uri={} status={} cost={}ms body={}", method, uri, status, cost, body);
        } else if (cost >= observeProperties.getHttp().getSlowMs()) {
            log.warn("[http.slow] method={} uri={} status={} cost={}ms threshold={}ms body={}",
                    method, uri, status, cost, observeProperties.getHttp().getSlowMs(), body);
        } else {
            log.info("[http.res] method={} uri={} status={} cost={}ms body={}", method, uri, status, cost, body);
        }
    }

    /** 页面与二进制响应只记状态码与耗时，正文不进日志 */
    private boolean silent(String contentType) {
        for (String prefix : SILENT_RESPONSE_TYPES) {
            if (contentType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 只记白名单内的请求头：Authorization 与 Cookie 一律不落盘 */
    private String headers(HttpServletRequest request) {
        StringBuilder text = new StringBuilder(64);
        for (String name : observeProperties.getHttp().getLogHeaders()) {
            String value = request.getHeader(name);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (text.length() > 0) {
                text.append('|');
            }
            text.append(name).append('=').append(StrUtil.sub(value, 0, 120));
        }
        return text.toString();
    }

    /** 载荷转文本：脱敏后截断，二进制与超长内容只留前若干字符 */
    private String text(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return LogSanitizer.mask(new String(bytes, StandardCharsets.UTF_8),
                observeProperties.getSensitiveKeys(), observeProperties.getHttp().getMaxBodyChars());
    }

    /** 流式判定：SSE 端点按路径与 Accept 头识别，异步已启动的一律不包装 */
    private boolean isStream(HttpServletRequest request) {
        if (request.isAsyncStarted()) {
            return true;
        }
        if (request.getRequestURI().endsWith("/stream")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return StrUtil.isNotBlank(accept) && accept.contains("text/event-stream");
    }

    /** 只缓存文本类请求体：multipart 与大上传走原请求，不进内存 */
    private boolean hasTextBody(HttpServletRequest request) {
        String type = StrUtil.nullToEmpty(request.getContentType()).toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.startsWith("multipart/")) {
            return false;
        }
        return type.startsWith("application/json")
                || type.startsWith("text/")
                || type.startsWith("application/x-www-form-urlencoded");
    }

    /** 路由模式：把形如 ID 的路径片段替换为 {id}，否则按资源 ID 打散观测键 */
    private String routeKey(HttpServletRequest request) {
        StringBuilder text = new StringBuilder(request.getRequestURI().length());
        for (String segment : StrUtil.split(request.getRequestURI(), '/')) {
            if (segment.isEmpty()) {
                continue;
            }
            text.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
        }
        return request.getMethod() + " " + (text.length() == 0 ? "/" : text);
    }

    private int statusOf(HttpServletResponse wrapped, HttpServletResponse original, boolean stream) {
        return stream ? original.getStatus() : ((ContentCachingResponseWrapper) wrapped).getStatus();
    }

    /** 客户端 IP：优先代理头首段，兜底 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            return StrUtil.subBefore(forwarded, ',', false).trim();
        }
        return request.getRemoteAddr();
    }
}
