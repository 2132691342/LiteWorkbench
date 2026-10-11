package com.liteworkbench.runtime;

import java.nio.file.Path;

/** 脚本执行请求 */
public record ExecRequest(String code, long timeoutSeconds, Path workDir) {
}
