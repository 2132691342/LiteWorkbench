package com.liteworkbench.domain.dto;

import com.liteworkbench.domain.vo.LogDocVO;

import java.util.List;

/** 日志检索结果：当前页文档与命中总数 */
public record LogSearchDTO(List<LogDocVO> list, long total) {
}
