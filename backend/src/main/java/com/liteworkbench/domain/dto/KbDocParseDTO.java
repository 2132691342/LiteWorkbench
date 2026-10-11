package com.liteworkbench.domain.dto;

import java.io.Serializable;

/** 知识库文档解析任务消息体，经 MQ 在上传线程与解析消费之间传递 */
public record KbDocParseDTO(String docId, String userId, String kbId,
                            String bucketName, String objectKey, String docName) implements Serializable {
}
