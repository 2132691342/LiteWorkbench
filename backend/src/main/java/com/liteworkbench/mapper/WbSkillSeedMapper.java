package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbSkillSeedDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 内置技能种子数据访问：只读，写入由 db/data.sql 负责 */
@Mapper
public interface WbSkillSeedMapper {

    /** 全部种子，按展示顺序 */
    @Select("SELECT * FROM wb_skill_seed ORDER BY sort_no, skill_code")
    List<WbSkillSeedDO> selectAll();

    /** 按编码取种子，恢复默认用 */
    @Select("SELECT * FROM wb_skill_seed WHERE skill_code = #{skillCode}")
    WbSkillSeedDO selectByCode(@Param("skillCode") String skillCode);
}
