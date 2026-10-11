package com.liteworkbench.domain.dto;

import com.liteworkbench.domain.constant.AppConst;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** 分页请求基类，所有分页查询继承它 */
@Data
public class PageREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private Integer page = AppConst.DEFAULT_PAGE;
    /** 每页条数，上限 AppConst.MAX_SIZE */
    private Integer size = AppConst.DEFAULT_SIZE;
    /** 模糊检索关键字 */
    private String keyword;

    /** 归一化页码 */
    public long pageNo() {
        return Objects.isNull(page) || page < 1 ? AppConst.DEFAULT_PAGE : page;
    }

    /** 归一化每页条数 */
    public long pageSize() {
        int sizeValue = Objects.isNull(size) || size < 1 ? AppConst.DEFAULT_SIZE : size;
        return Math.min(sizeValue, AppConst.MAX_SIZE);
    }

    /** SQL offset */
    public long offset() {
        return (pageNo() - 1) * pageSize();
    }
}
