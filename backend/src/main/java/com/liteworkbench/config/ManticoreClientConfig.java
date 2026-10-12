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

    @Bean
    public ApiClient manticoreApiClient(ManticoreProperties properties) {
        return new ApiClient()
                .setBasePath(properties.getUrl())
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout());
    }

    @Bean
    public IndexApi manticoreIndexApi(ApiClient manticoreApiClient) {
        return new IndexApi(manticoreApiClient);
    }

    @Bean
    public SearchApi manticoreSearchApi(ApiClient manticoreApiClient) {
        return new SearchApi(manticoreApiClient);
    }

    @Bean
    public UtilsApi manticoreUtilsApi(ApiClient manticoreApiClient) {
        return new UtilsApi(manticoreApiClient);
    }
}
