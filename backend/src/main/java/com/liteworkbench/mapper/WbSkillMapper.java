package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbSkillDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 技能表数据访问 */
@Mapper
public interface WbSkillMapper {

    /** 新增技能 */
    @Insert("""
            INSERT INTO wb_skill (skill_id, user_id, skill_code, skill_name, summary, content,
                                  trigger_words, enabled, builtin, created_by, remark)
            VALUES (#{skillId}, #{userId}, #{skillCode}, #{skillName}, #{summary}, #{content},
                    #{triggerWords}, #{enabled}, #{builtin}, #{createdBy}, #{remark})
            """)
    int insert(WbSkillDO skill);

    /** 按主键更新 */
    int update(WbSkillDO skill);

    /** 恢复内置技能为种子版本：只回写内容字段，保留启用状态，不动 user_id 与 created_by */
    @Update("""
            UPDATE wb_skill SET skill_name = #{skillName}, summary = #{summary}, content = #{content},
                   trigger_words = #{triggerWords}, builtin = true, remark = #{remark},
                   updated_by = #{updatedBy}, updated_at = now()
            WHERE skill_id = #{skillId} AND user_id = #{userId} AND deleted = false
            """)
    int restoreBuiltin(WbSkillDO skill);

    /** 存量行补标记内置：只改 builtin，不动内容（升级前补种的技能没有这一列） */
    @Update("""
            UPDATE wb_skill SET builtin = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE skill_id = #{skillId} AND builtin = false
            """)
    int markBuiltin(@Param("skillId") String skillId, @Param("updatedBy") String updatedBy);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_skill SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE skill_id = #{skillId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("skillId") String skillId,
                   @Param("updatedBy") String updatedBy);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_skill
            WHERE skill_id = #{skillId} AND user_id = #{userId} AND deleted = false
            """)
    WbSkillDO selectByIdAndUser(@Param("userId") String userId, @Param("skillId") String skillId);

    /** 按编码查询，冲突校验用 */
    @Select("""
            SELECT * FROM wb_skill
            WHERE skill_code = #{skillCode} AND user_id = #{userId} AND deleted = false
            """)
    WbSkillDO selectByCode(@Param("userId") String userId, @Param("skillCode") String skillCode);

    /** 按编码查询且不排除已删行：内置技能补种要能识别"用户已删过"，避免每次启动复活 */
    @Select("""
            SELECT skill_id FROM wb_skill
            WHERE skill_code = #{skillCode} AND user_id = #{userId}
            """)
    String selectAnyIdByCode(@Param("userId") String userId, @Param("skillCode") String skillCode);

    /** 用户全部启用技能，提示词注入用 */
    @Select("SELECT * FROM wb_skill WHERE user_id = #{userId} AND enabled = true AND deleted = false")
    List<WbSkillDO> selectEnabledByUser(@Param("userId") String userId);

    /** 分页查询 */
    List<WbSkillDO> selectPage(@Param("req") PageREQ req,
                               @Param("userId") String userId,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") PageREQ req, @Param("userId") String userId);
}
