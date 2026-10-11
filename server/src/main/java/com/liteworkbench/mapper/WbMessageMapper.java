package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbMessageDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 会话消息表数据访问 */
@Mapper
public interface WbMessageMapper {

    /** 新增消息 */
    @Insert("""
            INSERT INTO wb_message (message_id, session_id, run_id, role_code, content, token_count)
            VALUES (#{messageId}, #{sessionId}, #{runId}, #{roleCode}, #{content}, #{tokenCount})
            """)
    int insert(WbMessageDO message);

    /** 会话全量消息，按时间正序 */
    @Select("""
            SELECT * FROM wb_message
            WHERE session_id = #{sessionId} AND deleted = false
            ORDER BY created_at ASC
            """)
    List<WbMessageDO> selectBySession(@Param("sessionId") String sessionId);

    /** 会话最近 N 条消息（倒序取回后由调用方反转为正序），组装历史用 */
    @Select("""
            SELECT * FROM wb_message
            WHERE session_id = #{sessionId} AND deleted = false
            ORDER BY created_at DESC LIMIT #{limit}
            """)
    List<WbMessageDO> selectRecent(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
