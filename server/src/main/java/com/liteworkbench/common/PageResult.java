package com.liteworkbench.common;

import cn.hutool.core.collection.CollUtil;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/** 分页结果，列表接口的 data 统一为该类型 */
@Data
@NoArgsConstructor
@SuperBuilder
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list;
    /** 总记录数 */
    private long total;
    /** 页码，从 1 开始 */
    private long page;
    /** 每页条数 */
    private long size;

    /** 构造分页结果，空列表时返回不可变空集合 */
    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.setList(CollUtil.isEmpty(list) ? Collections.emptyList() : list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    /** 空分页 */
    public static <T> PageResult<T> empty(long page, long size) {
        return of(Collections.emptyList(), 0L, page, size);
    }
}
