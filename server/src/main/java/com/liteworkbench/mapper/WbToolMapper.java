package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbToolDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 工具定义表数据访问，平台级定义 */
@Mapper
public interface WbToolMapper {

    /** 新增工具定义 */
    @Insert("""
            INSERT INTO wb_tool (tool_id, tool_name, tool_label, category, risk_level, enabled, schema_json)
            VALUES (#{toolId}, #{toolName}, #{toolLabel}, #{category}, #{riskLevel}, #{enabled}, #{schemaJson})
            """)
    int insert(WbToolDO tool);

    /** 全量列表 */
    @Select("SELECT * FROM wb_tool ORDER BY category, tool_name")
    List<WbToolDO> selectAll();

    /** 按调用名查询 */
    @Select("SELECT * FROM wb_tool WHERE tool_name = #{toolName}")
    WbToolDO selectByName(@Param("toolName") String toolName);

    /** 按主键查询 */
    @Select("SELECT * FROM wb_tool WHERE tool_id = #{toolId}")
    WbToolDO selectById(@Param("toolId") String toolId);

    /** 启停 */
    @Update("""
            UPDATE wb_tool SET enabled = #{enabled}, updated_at = now()
            WHERE tool_id = #{toolId}
            """)
    int updateEnabled(@Param("toolId") String toolId, @Param("enabled") boolean enabled);
}
