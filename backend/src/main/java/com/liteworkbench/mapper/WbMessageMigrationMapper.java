package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.MessageLegacyRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 旧版消息表迁移数据访问：仅 MessageStoreMigration 使用，表迁完即删 */
@Mapper
public interface WbMessageMigrationMapper {

    /** wb_message 表是否存在 */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM information_schema.tables
                           WHERE table_schema = current_schema() AND table_name = 'wb_message')
            """)
    boolean tableExists();

    /** 全量行数 */
    @Select("SELECT COUNT(1) FROM wb_message")
    long countAll();

    /** 分批读取，按创建时间升序 */
    @Select("""
            SELECT message_id, user_id, session_id, run_id, role_code, content,
                   token_count, created_at
            FROM wb_message
            ORDER BY created_at ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MessageLegacyRow> selectBatch(@Param("offset") long offset, @Param("limit") int limit);

    /** 迁移完成后删除旧表 */
    @Update("DROP TABLE IF EXISTS wb_message")
    void dropTable();
}
