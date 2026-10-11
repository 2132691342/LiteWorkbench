package com.liteworkbench.domain.dto;

import java.io.InputStream;

/** 上传文件传输对象：只带元数据与流，service 侧不感知 multipart */
public record UploadFileDTO(String fileName, String contentType, long fileSize, InputStream content) {
}
