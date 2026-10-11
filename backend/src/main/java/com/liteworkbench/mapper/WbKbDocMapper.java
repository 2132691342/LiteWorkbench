package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbKbDocDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 知识库文档表数据访问 */
@Mapper
public interface WbKbDocMapper {

    /** 新增文档（上传即落 pending） */
    @Insert("""
            INSERT INTO wb_kb_doc (doc_id, user_id, kb_id, doc_name, object_key, bucket_name,
                                   file_size, content_type, char_count, chunk_count, state,
                                   created_by, error_message)
            VALUES (#{docId}, #{userId}, #{kbId}, #{docName}, #{objectKey}, #{bucketName},
                    #{fileSize}, #{contentType}, 0, 0, #{state}, #{createdBy}, #{errorMessage})
            """)
    int insert(WbKbDocDO doc);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_kb_doc
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    WbKbDocDO selectByIdAndUser(@Param("userId") String userId, @Param("docId") String docId);

    /** 文档分页（可按状态过滤） */
    List<WbKbDocDO> selectPage(@Param("kbId") String kbId,
                               @Param("userId") String userId,
                               @Param("keyword") String keyword,
                               @Param("state") String state,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 文档分页计数 */
    long countPage(@Param("kbId") String kbId,
                   @Param("userId") String userId,
                   @Param("keyword") String keyword,
                   @Param("state") String state);

    /** 更新解析状态 */
    @Update("""
            UPDATE wb_kb_doc SET state = #{state}, error_message = #{errorMessage},
                                 updated_by = #{updatedBy}, updated_at = now()
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    int updateState(@Param("docId") String docId,
                    @Param("userId") String userId,
                    @Param("state") String state,
                    @Param("errorMessage") String errorMessage,
                    @Param("updatedBy") String updatedBy);

    /** 解析成功后同步字符与分块计数 */
    @Update("""
            UPDATE wb_kb_doc SET char_count = #{charCount}, chunk_count = #{chunkCount}, updated_at = now()
            WHERE doc_id = #{docId} AND deleted = false
            """)
    int updateCounts(@Param("docId") String docId,
                     @Param("charCount") int charCount,
                     @Param("chunkCount") int chunkCount);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_kb_doc SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("docId") String docId,
                   @Param("updatedBy") String updatedBy);

    /** 知识库下全部有效文档行，级联清理用 */
    @Select("SELECT * FROM wb_kb_doc WHERE kb_id = #{kbId} AND user_id = #{userId} AND deleted = false")
    List<WbKbDocDO> selectListByKb(@Param("userId") String userId, @Param("kbId") String kbId);

    /** 知识库下文档数，计数同步用 */
    @Select("SELECT COUNT(1) FROM wb_kb_doc WHERE kb_id = #{kbId} AND deleted = false")
    int countByKb(@Param("kbId") String kbId);

    /** 知识库下分块总数（各文档 chunk_count 求和），计数同步用 */
    @Select("SELECT COALESCE(SUM(chunk_count), 0) FROM wb_kb_doc WHERE kb_id = #{kbId} AND deleted = false")
    int sumChunkCount(@Param("kbId") String kbId);
}
