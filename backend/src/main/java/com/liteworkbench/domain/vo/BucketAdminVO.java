package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 存储桶管理视图对象：全平台桶清单与占用 */
@Data
public class BucketAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 桶名 */
    private String bucketName;
    /** 创建时间，毫秒时间戳 */
    private Long creationDate;
    /** 桶内对象数 */
    private Long objectCount;
    /** 桶内对象总字节数 */
    private Long totalSize;
    /** 平台桶（主桶与工作区桶）：禁止在管理端删除，防误断业务链路 */
    private Boolean protectedBucket;
}
