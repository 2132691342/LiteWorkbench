package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbMemoryDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 长期记忆表数据访问：全部语句显式带 user_id，记忆是用户私有资产 */
@Mapper
public interface WbMemoryMapper {

    /** 新增记忆 */
    @Insert("""
            INSERT INTO wb_memory (memory_id, user_id, memory_key, memory_value, category, weight, hit_count, created_by)
            VALUES (#{memoryId}, #{userId}, #{memoryKey}, #{memoryValue}, #{category}, #{weight}, #{hitCount}, #{createdBy})
            """)
    int insert(WbMemoryDO memory);

    /** 按 (用户, key) 覆写内容与维度，权重取两者较大值；命中唯一索引即更新，绝不产生同 key 两行 */
    @Update("""
            UPDATE wb_memory
            SET memory_value = #{memoryValue}, category = #{category},
                weight = GREATEST(weight, #{weight}), updated_by = #{updatedBy}, updated_at = now()
            WHERE user_id = #{userId} AND memory_key = #{memoryKey} AND deleted = false
            """)
    int upsertByKey(WbMemoryDO memory);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_memory SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE memory_id = #{memoryId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("memoryId") String memoryId,
                   @Param("updatedBy") String updatedBy);

    /** 按主键查，带归属过滤 */
    @Select("""
            SELECT * FROM wb_memory WHERE memory_id = #{memoryId} AND user_id = #{userId} AND deleted = false
            """)
    WbMemoryDO selectById(@Param("userId") String userId, @Param("memoryId") String memoryId);

    /** 注入系统提示用：权重优先、其次最近更新，只取少量 */
    @Select("""
            SELECT * FROM wb_memory
            WHERE user_id = #{userId} AND deleted = false
            ORDER BY weight DESC, updated_at DESC NULLS LAST, created_at DESC
            LIMIT #{limit}
            """)
    List<WbMemoryDO> selectTop(@Param("userId") String userId, @Param("limit") int limit);

    /** 键或内容模糊匹配，供 Agent 主动检索 */
    @Select("""
            SELECT * FROM wb_memory
            WHERE user_id = #{userId} AND deleted = false
              AND (memory_key ILIKE '%' || #{keyword} || '%' OR memory_value ILIKE '%' || #{keyword} || '%')
            ORDER BY weight DESC, updated_at DESC NULLS LAST
            LIMIT #{limit}
            """)
    List<WbMemoryDO> search(@Param("userId") String userId,
                            @Param("keyword") String keyword,
                            @Param("limit") int limit);

    /** 命中计数自增，用于后续按实际使用情况调整注入顺序 */
    @Update("""
            UPDATE wb_memory SET hit_count = hit_count + 1 WHERE memory_id = #{memoryId}
            """)
    int incrementHit(@Param("memoryId") String memoryId);

    /** 用户记忆条数，用于容量闸门 */
    @Select("""
            SELECT COUNT(1) FROM wb_memory WHERE user_id = #{userId} AND deleted = false
            """)
    long countByUser(@Param("userId") String userId);

    /** 超限时淘汰权重最低、最久未更新的一行 */
    @Delete("""
            DELETE FROM wb_memory WHERE memory_id = (
                SELECT memory_id FROM wb_memory
                WHERE user_id = #{userId} AND deleted = false
                ORDER BY weight ASC, updated_at ASC NULLS FIRST, created_at ASC
                LIMIT 1
            )
            """)
    int evictLowest(@Param("userId") String userId);

    /** 分页查询 */
    List<WbMemoryDO> selectPage(@Param("userId") String userId,
                                @Param("req") PageREQ req,
                                @Param("category") String category,
                                @Param("offset") long offset,
                                @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("userId") String userId,
                   @Param("req") PageREQ req,
                   @Param("category") String category);
}
