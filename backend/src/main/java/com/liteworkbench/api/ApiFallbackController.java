package com.liteworkbench.api;

import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.R;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 接口兜底：未命中任何业务接口的 /api/** 请求统一回 404 信封，不被前端深链兜底吞成 HTML */
@RestController
public class ApiFallbackController {

    /** 兜底路径优先级最低，具体接口始终优先匹配；未登录请求仍由登录拦截器先拦 */
    @RequestMapping("/api/**")
    public ResponseEntity<R<Void>> notFound() {
        ErrorCodeEnum code = ErrorCodeEnum.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }
}
