package com.liteworkbench.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 运行句柄：协作式取消 + 完成通知，cancel 幂等且可跨线程调用 */
public class RunHandle {

    private final String runId;
    /** 取消标志，内核在检查点读取 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** 完成通知：正常收敛 complete，异常收敛 completeExceptionally */
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public RunHandle(String runId) {
        this.runId = runId;
    }

    /** 归属运行 ID */
    public String runId() {
        return runId;
    }

    /** 置取消标志，返回是否由本次调用置位 */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /** 是否已请求取消 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 完成回调，供服务层收尾 */
    public CompletableFuture<Void> completion() {
        return completion;
    }

    /** 正常收敛 */
    public void complete() {
        completion.complete(null);
    }

    /** 异常收敛 */
    public void fail(Throwable error) {
        completion.completeExceptionally(error);
    }
}
