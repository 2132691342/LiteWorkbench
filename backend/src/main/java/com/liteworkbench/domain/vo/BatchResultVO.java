package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 批量操作结果：逐条成败对账，成功的直接消失，失败的留在页面上告诉用户为什么 */
@Data
public class BatchResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功条数 */
    private Integer successCount = 0;
    /** 失败项：id 与原因，前端按行标红 */
    private List<Failure> failures = new ArrayList<>();

    /** 记一次成功 */
    public void ok() {
        successCount++;
    }

    /** 记一次失败 */
    public void fail(String id, String reason) {
        Failure failure = new Failure();
        failure.setId(id);
        failure.setReason(reason);
        failures.add(failure);
    }

    /** 单条失败明细 */
    @Data
    public static class Failure implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String id;
        private String reason;
    }
}
