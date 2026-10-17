package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbProviderDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 模型供应方表数据访问 */
@Mapper
public interface WbProviderMapper {

    /** 新增供应方 */
    @Insert("""
            INSERT INTO wb_provider (provider_id, provider_code, provider_name, base_url,
                                     api_key_enc, default_model, context_window, max_output_tokens,
                                     temperature, top_p, cap_vision, cap_tool, cap_reasoning,
                                     enabled, created_by)
            VALUES (#{providerId}, #{providerCode}, #{providerName}, #{baseUrl},
                    #{apiKeyEnc}, #{defaultModel}, #{contextWindow}, #{maxOutputTokens},
                    #{temperature}, #{topP}, #{capVision}, #{capTool}, #{capReasoning},
                    #{enabled}, #{createdBy})
            """)
    int insert(WbProviderDO provider);

    /** 按主键更新 */
    int update(WbProviderDO provider);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_provider SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE provider_id = #{providerId} AND deleted = false
            """)
    int deleteById(@Param("providerId") String providerId, @Param("updatedBy") String updatedBy);

    /** 按主键查询 */
    @Select("""
            SELECT * FROM wb_provider
            WHERE provider_id = #{providerId} AND deleted = false
            """)
    WbProviderDO selectById(@Param("providerId") String providerId);

    /** 按编码查询，运行时取配置用 */
    @Select("""
            SELECT * FROM wb_provider
            WHERE provider_code = #{providerCode} AND deleted = false
            """)
    WbProviderDO selectByCode(@Param("providerCode") String providerCode);

    /** 全量列表 */
    @Select("SELECT * FROM wb_provider WHERE deleted = false ORDER BY created_at ASC")
    List<WbProviderDO> selectAll();

    /** 编码冲突计数；excludeId 为空即不做排除，避免 `<> NULL` 让条件恒为 NULL 从而绕过重名校验 */
    @Select("""
            SELECT count(*) FROM wb_provider
            WHERE provider_code = #{providerCode}
              AND deleted = false
              AND (CAST(#{excludeId} AS varchar) IS NULL OR #{excludeId} = '' OR provider_id <> #{excludeId})
            """)
    long countByCodeExclude(@Param("providerCode") String providerCode,
                            @Param("excludeId") String excludeId);
}
