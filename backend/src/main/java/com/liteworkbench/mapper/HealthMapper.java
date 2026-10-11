package com.liteworkbench.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 健康探测数据访问 */
@Mapper
public interface HealthMapper {

    /** 数据库连通性探测 */
    @Select("SELECT 1")
    int ping();
}
