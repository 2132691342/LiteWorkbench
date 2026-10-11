package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbSettingDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户设置表数据访问 */
@Mapper
public interface WbSettingMapper {

    /** 幂等写入：存在则更新值 */
    @Insert("""
            INSERT INTO wb_setting (setting_id, user_id, setting_key, setting_value, value_type,
                                    updated_by, updated_at)
            VALUES (#{settingId}, #{userId}, #{settingKey}, #{settingValue}, #{valueType},
                    #{updatedBy}, now())
            ON CONFLICT (user_id, setting_key)
            DO UPDATE SET setting_value = #{settingValue}, value_type = #{valueType},
                          updated_by = #{updatedBy}, updated_at = now()
            """)
    int upsert(WbSettingDO setting);

    /** 用户全量设置 */
    @Select("SELECT * FROM wb_setting WHERE user_id = #{userId} ORDER BY setting_key")
    List<WbSettingDO> selectByUser(@Param("userId") String userId);

    /** 按键查询 */
    @Select("SELECT * FROM wb_setting WHERE user_id = #{userId} AND setting_key = #{settingKey}")
    WbSettingDO selectByUserAndKey(@Param("userId") String userId,
                                   @Param("settingKey") String settingKey);
}
