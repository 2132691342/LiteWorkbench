package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbSettingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户设置表数据访问 */
@Mapper
public interface WbSettingMapper {

    /** 幂等写入：存在则更新值 */
    int upsert(WbSettingDO setting);

    /** 用户全量设置 */
    @Select("SELECT * FROM wb_setting WHERE user_id = #{userId} ORDER BY setting_key")
    List<WbSettingDO> selectByUser(@Param("userId") String userId);

    /** 按键查询 */
    @Select("SELECT * FROM wb_setting WHERE user_id = #{userId} AND setting_key = #{settingKey}")
    WbSettingDO selectByUserAndKey(@Param("userId") String userId,
                                   @Param("settingKey") String settingKey);
}
