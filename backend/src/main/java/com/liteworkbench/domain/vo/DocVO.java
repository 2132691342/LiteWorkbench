package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 创作文档视图对象；列表不带正文，详情带正文 */
@Data
public class DocVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID */
    private String docId;
    /** 归属用户 */
    private String userId;
    /** 标题 */
    private String docTitle;
    /** 正文格式 */
    private String docType;
    /** 分类 */
    private String category;
    /** 标签，逗号分隔 */
    private String tags;
    /** 摘要 */
    private String summary;
    /** 正文，仅详情返回 */
    private String content;
    /** 字数 */
    private Integer wordCount;
    /** 当前版本号 */
    private Integer versionNo;
    /** 引用的知识库 */
    private String sourceKbId;
    /** 业务状态 */
    private String state;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
