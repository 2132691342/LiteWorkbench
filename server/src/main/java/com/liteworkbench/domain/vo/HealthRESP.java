package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 健康监控视图：聚合状态 + 逐组件明细 */
@Data
public class HealthRESP implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 聚合状态 UP / DEGRADED / DOWN */
    private String status;
    /** 逐组件明细 */
    private List<ComponentHealth> components;

    /** 单组件健康明细 */
    @Data
    public static class ComponentHealth implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 组件名 postgres / redis / minio / rabbitmq / python */
        private String name;
        /** 状态 UP / DOWN */
        private String status;
        /** 说明，失败原因或版本信息 */
        private String detail;
        /** 探测耗时毫秒 */
        private Long costMs;
    }
}
