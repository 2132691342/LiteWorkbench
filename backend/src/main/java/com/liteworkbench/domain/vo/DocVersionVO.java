package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 文档版本视图对象；列表不带正文，详情带正文 */
@Data
public class DocVersionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String versionId;
    private String docId;
    private Integer versionNo;
    /** 快照标题 */
    private String docTitle;
    /** 快照正文，仅详情返回 */
    private String content;
    private String changeNote;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
