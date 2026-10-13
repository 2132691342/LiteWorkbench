package com.liteworkbench.api;

import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.R;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** 接口兜底：未命中任何业务接口的 /api/** 请求统一回 404 信封，不被前端深链兜底吞成 HTML */
@RestController
public class ApiFallbackController {

    /** 只兜底 GET / POST：PUT / PATCH / DELETE 不在此受理，交给方法不支持处理回 405 */
    @RequestMapping(value = "/api/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<R<Void>> notFound() {
        ErrorCodeEnum code = ErrorCodeEnum.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }
}
