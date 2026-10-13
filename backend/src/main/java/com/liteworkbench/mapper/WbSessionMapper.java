package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.entity.WbSessionDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 会话表数据访问 */
@Mapper
public interface WbSessionMapper {

    /** 新增会话 */
    @Insert("""
            INSERT INTO wb_session (session_id, user_id, title, provider_code, model_name,
                                    system_prompt, message_count, run_count, total_tokens, last_active_at,
                                    state, created_by, remark)
            VALUES (#{sessionId}, #{userId}, #{title}, #{providerCode}, #{modelName},
                    #{systemPrompt}, 0, 0, 0, now(), #{state}, #{createdBy}, #{remark})
            """)
    int insert(WbSessionDO session);

    /** 按主键更新标题 / 模型 / 提示词 / 状态 */
    int update(WbSessionDO session);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_session SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("sessionId") String sessionId,
                   @Param("updatedBy") String updatedBy);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_session
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND deleted = false
            """)
    WbSessionDO selectByIdAndUser(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 追加会话统计：消息数 / 运行数 / token / 最近活跃 */
    @Update("""
            UPDATE wb_session
            SET message_count = message_count + #{messageDelta},
                run_count = run_count + #{runDelta},
                total_tokens = total_tokens + #{tokenDelta},
                last_active_at = now()
            WHERE session_id = #{sessionId}
            """)
    int updateStats(@Param("sessionId") String sessionId,
                    @Param("messageDelta") int messageDelta,
                    @Param("runDelta") int runDelta,
                    @Param("tokenDelta") long tokenDelta);

    /** 更新标题，首轮对话自动命名用，仅归属用户可改 */
    @Update("""
            UPDATE wb_session SET title = #{title}, updated_at = now()
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND deleted = false
            """)
    int updateTitle(@Param("sessionId") String sessionId,
                    @Param("userId") String userId,
                    @Param("title") String title);

    /** 分页查询，仅返回归属用户的会话 */
    List<WbSessionDO> selectPage(@Param("req") SessionPageREQ req,
                                 @Param("userId") String userId,
                                 @Param("offset") long offset,
                                 @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") SessionPageREQ req, @Param("userId") String userId);

    /** 会话总数，仪表盘用 */
    @Select("SELECT count(*) FROM wb_session WHERE deleted = false")
    long countAll();
}
