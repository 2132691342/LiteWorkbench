package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbFileDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文件对象表数据访问 */
@Mapper
public interface WbFileMapper {

    /** 新增文件元数据 */
    @Insert("""
            INSERT INTO wb_file (file_id, user_id, session_id, file_name, object_key, bucket_name,
                                 file_size, content_type, category, created_by)
            VALUES (#{fileId}, #{userId}, #{sessionId}, #{fileName}, #{objectKey}, #{bucketName},
                    #{fileSize}, #{contentType}, #{category}, #{createdBy})
            """)
    int insert(WbFileDO file);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_file
            WHERE file_id = #{fileId} AND user_id = #{userId} AND deleted = false
            """)
    WbFileDO selectByIdAndUser(@Param("userId") String userId, @Param("fileId") String fileId);

    /** 分页查询，keyword 匹配文件名 */
    List<WbFileDO> selectPage(@Param("userId") String userId,
                              @Param("sessionId") String sessionId,
                              @Param("category") String category,
                              @Param("keyword") String keyword,
                              @Param("offset") long offset,
                              @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("userId") String userId,
                   @Param("sessionId") String sessionId,
                   @Param("category") String category,
                   @Param("keyword") String keyword);

    /** 逻辑删除元数据 */
    @Delete("""
            UPDATE wb_file SET deleted = true
            WHERE file_id = #{fileId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId, @Param("fileId") String fileId);
}
