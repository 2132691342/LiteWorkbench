package com.liteworkbench.agent.model;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** 模型出站 HTTP 支撑：建连、逐行读流、错误体截断，全部基于 JDK，连接即取消凭据 */
public final class HttpModelSupport {

    /** 错误体保留长度 */
    private static final int ERROR_BODY_MAX = 500;

    private HttpModelSupport() {
    }

    /** 行处理器：返回 false 停止读取 */
    @FunctionalInterface
    public interface LineHandler {
        boolean handle(String line) throws IOException;
    }

    /** 发送 JSON POST，返回已连通的连接；调用方负责在 finally 中 disconnect */
    public static HttpURLConnection open(String url, String body, Map<String, String> headers,
                                         int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/event-stream, application/json");
        if (Objects.nonNull(headers)) {
            headers.forEach(connection::setRequestProperty);
        }
        byte[] payload = StrUtil.bytes(body, StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        // 请求体必须显式关闭：固定长度模式下不关闭会让服务端一直等剩余字节，表现为首字节延迟
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
            out.flush();
        }
        return connection;
    }

    /** 逐行读取成功响应；非 2xx 抛异常并带上响应体片段 */
    public static void readLines(HttpURLConnection connection, LineHandler handler) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new IllegalStateException("HTTP " + status + " " + truncate(readAll(connection.getErrorStream())));
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handler.handle(line)) {
                    return;
                }
            }
        }
    }

    /** 读取整个流，失败返回空串 */
    public static String readAll(InputStream stream) {
        if (Objects.isNull(stream)) {
            return StrUtil.EMPTY;
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // 错误响应体读取失败无需再抛，调用方只用于提示
        }
        return builder.toString();
    }

    /** 出站 I/O 异常归类：超时给专用错误码，其余归为调用失败，消息经截断后可直接进日志与响应 */
    public static BizException ioFailure(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return new BizException(ErrorCodeEnum.MODEL_CALL_TIMEOUT, "模型响应超时");
        }
        return new BizException(ErrorCodeEnum.MODEL_CALL_FAILED, truncate(e.getMessage()));
    }

    /** 去空白并截断 */
    public static String truncate(String text) {
        String normalized = StrUtil.trimToEmpty(text).replaceAll("\\s+", " ");
        return normalized.length() <= ERROR_BODY_MAX ? normalized : normalized.substring(0, ERROR_BODY_MAX);
    }

    /** 安静关闭连接 */
    public static void close(HttpURLConnection connection) {
        if (Objects.nonNull(connection)) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }
}
