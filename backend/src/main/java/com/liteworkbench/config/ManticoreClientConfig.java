package com.liteworkbench.config;

import com.manticoresearch.client.ApiClient;
import com.manticoresearch.client.api.IndexApi;
import com.manticoresearch.client.api.SearchApi;
import com.manticoresearch.client.api.UtilsApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Manticore SDK 装配：ApiClient 单例，三个 API 门面共享同一连接配置 */
@Configuration
public class ManticoreClientConfig {

    /** 检索服务客户端 */
    @Bean
    public ApiClient manticoreApiClient(ManticoreProperties properties) {
        return new ApiClient()
                .setBasePath(properties.getUrl())
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout());
    }

    /** 索引写接口 */
    @Bean
    public IndexApi manticoreIndexApi(ApiClient manticoreApiClient) {
        return new IndexApi(manticoreApiClient);
    }

    /** 检索接口 */
    @Bean
    public SearchApi manticoreSearchApi(ApiClient manticoreApiClient) {
        return new SearchApi(manticoreApiClient);
    }

    /** 建表与探活接口 */
    @Bean
    public UtilsApi manticoreUtilsApi(ApiClient manticoreApiClient) {
        return new UtilsApi(manticoreApiClient);
    }
}
