package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 知识库分块检索结果项，content 为原文，高亮由前端按关键字分段渲染 */
@Data
public class KbChunkVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分块 ID */
    private String chunkId;
    /** 归属用户 */
    private String userId;
    /** 归属知识库 */
    private String kbId;
    /** 归属文档 */
    private String docId;
    /** 文档名 */
    private String docName;
    /** 文档内块序号，从 1 递增 */
    private Integer seq;
    /** 块正文 */
    private String content;
    /** 相关度得分，FT.SEARCH WITHSCORES */
    private Double score;
    /** 入库时间，毫秒时间戳 */
    private Long createdAt;
}
