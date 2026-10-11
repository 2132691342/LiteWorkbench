package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbDocVersionDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文档版本表数据访问：快照只读，只有插入、查询与裁剪 */
@Mapper
public interface WbDocVersionMapper {

    /** 写一条版本快照 */
    @Insert("""
            INSERT INTO wb_doc_version (version_id, doc_id, user_id, version_no, doc_title, content, change_note, created_by)
            VALUES (#{versionId}, #{docId}, #{userId}, #{versionNo}, #{docTitle}, #{content}, #{changeNote}, #{createdBy})
            """)
    int insert(WbDocVersionDO version);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_doc_version
            WHERE version_id = #{versionId} AND user_id = #{userId}
            """)
    WbDocVersionDO selectByIdAndUser(@Param("userId") String userId, @Param("versionId") String versionId);

    /** 文档版本列表，倒序取最近 size 条 */
    @Select("""
            SELECT version_id, doc_id, version_no, doc_title, change_note, created_at
            FROM wb_doc_version
            WHERE doc_id = #{docId} AND user_id = #{userId}
            ORDER BY version_no DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<WbDocVersionDO> selectPage(@Param("docId") String docId,
                                    @Param("userId") String userId,
                                    @Param("offset") long offset,
                                    @Param("size") long size);

    /** 文档版本总数 */
    @Select("""
            SELECT COUNT(1) FROM wb_doc_version WHERE doc_id = #{docId} AND user_id = #{userId}
            """)
    long countByDoc(@Param("docId") String docId, @Param("userId") String userId);

    /** 裁剪超出保留条数的旧版本：保留最近 keep 条，其余按版本号倒序跳过前 keep 条后删除 */
    @Delete("""
            DELETE FROM wb_doc_version
            WHERE doc_id = #{docId}
              AND version_no IN (SELECT version_no FROM wb_doc_version
                                 WHERE doc_id = #{docId}
                                 ORDER BY version_no DESC
                                 OFFSET #{keep})
            """)
    int trimKeep(@Param("docId") String docId, @Param("keep") int keep);

    /** 删除文档的全部版本 */
    @Delete("""
            DELETE FROM wb_doc_version WHERE doc_id = #{docId} AND user_id = #{userId}
            """)
    int deleteByDoc(@Param("docId") String docId, @Param("userId") String userId);
}
