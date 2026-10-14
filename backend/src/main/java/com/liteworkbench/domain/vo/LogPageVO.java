package com.liteworkbench.domain.vo;

import lombok.Data;

import java.util.List;

/** 日志检索分页结果：分页字段之外带检索视角标识，供前端展示"仅本人日志 / 全局" */
@Data
public class LogPageVO {

    /** 当前页数据 */
    private List<LogDocVO> list;
    /** 总记录数 */
    private long total;
    /** 页码，从 1 开始 */
    private long page;
    private long size;
    /** 检索视角：global（全局，管理员）/ self（仅本人） */
    private String scope;
}
