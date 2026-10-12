package com.liteworkbench.mapper;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.core.util.StrUtil;
import com.manticoresearch.client.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Manticore /cli 执行器：DDL 走此端点，失败语句同样回显 Query OK，调用方另做存在性校验 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManticoreCliExecutor {

    /** /cli 请求超时毫秒 */
    private static final int CLI_TIMEOUT_MS = 10_000;

    private final ApiClient manticoreApiClient;

    /** 执行一条 SQL，返回服务端回执文本；必须以 text/plain 裸 body 提交，异常抛给调用方降级 */
    public String execute(String sql) {
        HttpRequest request = HttpRequest.post(manticoreApiClient.getBasePath() + "/cli")
                .body(sql, "text/plain")
                .timeout(CLI_TIMEOUT_MS);
        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                throw new IllegalStateException(StrUtil.format("manticore /cli HTTP {}", response.getStatus()));
            }
            return response.body();
        }
    }
}
