package com.liteworkbench.runtime;

/** 内置 Python 运行时契约：探测 + 执行 */
public interface PythonRuntime {

    /** 探测解释器，结果缓存，重探调用 resetProbe */
    RuntimeStatus detect();

    /** 清探测缓存，下次 detect 重新探测 */
    void resetProbe();

    /** 执行一段脚本，返回双通道输出；失败以非 0 退出码表达 */
    ExecResult execute(ExecRequest request);
}
