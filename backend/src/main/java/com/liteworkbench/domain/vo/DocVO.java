package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 创作文档视图对象；列表不带正文，详情带正文 */
@Data
public class DocVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String docId;
    /** 归属用户 */
    private String userId;
    private String docTitle;
    private String docType;
    private String category;
    /** 标签，逗号分隔 */
    private String tags;
    private String summary;
    /** 正文，仅详情返回 */
    private String content;
    private Integer wordCount;
    private Integer versionNo;
    /** 引用的知识库 */
    private String sourceKbId;
    private String state;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
