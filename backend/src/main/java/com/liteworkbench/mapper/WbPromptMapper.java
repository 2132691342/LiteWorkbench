package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbPromptDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 提示词表数据访问：用户模板与内置模板同表，内置行 user_id = builtin */
@Mapper
public interface WbPromptMapper {

    /** 新增模板 */
    @Insert("""
            INSERT INTO wb_prompt (prompt_id, user_id, prompt_title, category, content, variables, builtin, use_count, created_by)
            VALUES (#{promptId}, #{userId}, #{promptTitle}, #{category}, #{content},
                    CAST(#{variables} AS jsonb), #{builtin}, #{useCount}, #{createdBy})
            """)
    int insert(WbPromptDO prompt);

    /** 更新模板：内置行由服务层拒绝，这里只按用户范围更新 */
    @Update("""
            UPDATE wb_prompt SET prompt_title = #{promptTitle}, category = #{category}, content = #{content},
                                 variables = CAST(#{variables} AS jsonb), updated_by = #{updatedBy}, updated_at = now()
            WHERE prompt_id = #{promptId} AND user_id = #{userId} AND deleted = false
            """)
    int update(WbPromptDO prompt);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_prompt SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE prompt_id = #{promptId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("promptId") String promptId,
                   @Param("updatedBy") String updatedBy);

    /** 按主键查询，内置模板也能命中，可见性由服务层判定 */
    @Select("""
            SELECT * FROM wb_prompt WHERE prompt_id = #{promptId} AND deleted = false
            """)
    WbPromptDO selectById(@Param("promptId") String promptId);

    /** 用户内重名校验 */
    @Select("""
            SELECT COUNT(1) FROM wb_prompt
            WHERE user_id = #{userId} AND prompt_title = #{promptTitle} AND deleted = false
              AND (#{excludeId} IS NULL OR #{excludeId} = '' OR prompt_id != #{excludeId})
            """)
    long countByTitle(@Param("userId") String userId,
                      @Param("promptTitle") String promptTitle,
                      @Param("excludeId") String excludeId);

    /** 使用次数自增，只统计用户自有模板 */
    @Update("""
            UPDATE wb_prompt SET use_count = use_count + 1 WHERE prompt_id = #{promptId} AND builtin = false
            """)
    int incrementUseCount(@Param("promptId") String promptId);

    /** 分页查询：自有模板 + 内置模板 */
    List<WbPromptDO> selectPage(@Param("userId") String userId,
                                @Param("req") PageREQ req,
                                @Param("category") String category,
                                @Param("offset") long offset,
                                @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("userId") String userId,
                   @Param("req") PageREQ req,
                   @Param("category") String category);
}
