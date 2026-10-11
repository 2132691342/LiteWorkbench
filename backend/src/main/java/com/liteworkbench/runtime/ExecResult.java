package com.liteworkbench.runtime;

/** 脚本执行结果 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean timeout) {

    /** 是否执行成功 */
    public boolean success() {
        return !timeout && exitCode == 0;
    }
}
