package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbDocDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 创作文档表数据访问 */
@Mapper
public interface WbDocMapper {

    /** 新增文档 */
    @Insert("""
            INSERT INTO wb_doc (doc_id, user_id, doc_title, doc_type, category, tags, summary,
                                content, content_text, word_count, version_no, source_kb_id, state, created_by)
            VALUES (#{docId}, #{userId}, #{docTitle}, #{docType}, #{category}, #{tags}, #{summary},
                    #{content}, #{contentText}, #{wordCount}, #{versionNo}, #{sourceKbId}, #{state}, #{createdBy})
            """)
    int insert(WbDocDO doc);

    /** 更新文档：正文与元数据全量覆盖，版本号由服务层递增 */
    @Update("""
            UPDATE wb_doc SET doc_title = #{docTitle}, doc_type = #{docType}, category = #{category},
                             tags = #{tags}, summary = #{summary}, content = #{content},
                             content_text = #{contentText}, word_count = #{wordCount},
                             version_no = #{versionNo}, source_kb_id = #{sourceKbId}, state = #{state},
                             updated_by = #{updatedBy}, updated_at = now()
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    int update(WbDocDO doc);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_doc SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("docId") String docId,
                   @Param("updatedBy") String updatedBy);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_doc
            WHERE doc_id = #{docId} AND user_id = #{userId} AND deleted = false
            """)
    WbDocDO selectByIdAndUser(@Param("userId") String userId, @Param("docId") String docId);

    /** 用户内重名校验；excludeId 为 null 时 PG 无法推断参数类型，必须显式 CAST */
    @Select("""
            SELECT COUNT(1) FROM wb_doc
            WHERE user_id = #{userId} AND doc_title = #{docTitle} AND deleted = false
              AND (CAST(#{excludeId} AS varchar) IS NULL OR #{excludeId} = '' OR doc_id <> #{excludeId})
            """)
    long countByTitle(@Param("userId") String userId,
                      @Param("docTitle") String docTitle,
                      @Param("excludeId") String excludeId);

    /** 分页查询 */
    List<WbDocDO> selectPage(@Param("req") PageREQ req,
                             @Param("userId") String userId,
                             @Param("category") String category,
                             @Param("state") String state,
                             @Param("offset") long offset,
                             @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") PageREQ req,
                   @Param("userId") String userId,
                   @Param("category") String category,
                   @Param("state") String state);
}
