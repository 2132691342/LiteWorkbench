package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbKbDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 知识库表数据访问 */
@Mapper
public interface WbKbMapper {

    /** 新增知识库 */
    @Insert("""
            INSERT INTO wb_kb (kb_id, user_id, kb_name, description, doc_count, chunk_count, state, created_by)
            VALUES (#{kbId}, #{userId}, #{kbName}, #{description}, 0, 0, #{state}, #{createdBy})
            """)
    int insert(WbKbDO kb);

    /** 按主键更新名称与描述 */
    @Update("""
            UPDATE wb_kb SET kb_name = #{kbName}, description = #{description},
                             updated_by = #{updatedBy}, updated_at = now()
            WHERE kb_id = #{kbId} AND user_id = #{userId} AND deleted = false
            """)
    int update(WbKbDO kb);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_kb SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE kb_id = #{kbId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("kbId") String kbId,
                   @Param("updatedBy") String updatedBy);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_kb
            WHERE kb_id = #{kbId} AND user_id = #{userId} AND deleted = false
            """)
    WbKbDO selectByIdAndUser(@Param("userId") String userId, @Param("kbId") String kbId);

    /** 用户内重名校验 */
    @Select("""
            SELECT COUNT(1) FROM wb_kb
            WHERE user_id = #{userId} AND kb_name = #{kbName} AND deleted = false
              AND (#{excludeId} IS NULL OR #{excludeId} = '' OR kb_id <> #{excludeId})
            """)
    long countByName(@Param("userId") String userId,
                     @Param("kbName") String kbName,
                     @Param("excludeId") String excludeId);

    /** 同步文档与分块冗余计数 */
    @Update("""
            UPDATE wb_kb SET doc_count = #{docCount}, chunk_count = #{chunkCount}, updated_at = now()
            WHERE kb_id = #{kbId} AND deleted = false
            """)
    int updateCounts(@Param("kbId") String kbId,
                     @Param("docCount") int docCount,
                     @Param("chunkCount") int chunkCount);

    /** 分页查询 */
    List<WbKbDO> selectPage(@Param("req") PageREQ req,
                            @Param("userId") String userId,
                            @Param("offset") long offset,
                            @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") PageREQ req, @Param("userId") String userId);
}
