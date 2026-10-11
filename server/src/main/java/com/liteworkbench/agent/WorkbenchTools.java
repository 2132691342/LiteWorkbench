package com.liteworkbench.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.OutputSanitizer;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.runtime.ExecRequest;
import com.liteworkbench.runtime.PythonRuntime;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;

/**
 * 内置工具集：python_exec / file_read / file_write / http_get。
 * 启用状态从 wb_tool 读取，禁用即拒答；失败以 ERROR: 前缀文本返回，让模型自行调整。
 */
@Slf4j
public class WorkbenchTools {

    /** 工具默认超时，秒 */
    private static final long DEFAULT_TOOL_TIMEOUT = 60L;

    private final ToolGate toolGate;
    private final PythonRuntime pythonRuntime;
    private final ToolOutputCapture capture;
    /** 工作区根目录，运行期不变 */
    private final Path workDir;

    public WorkbenchTools(ToolGate toolGate, PythonRuntime pythonRuntime,
                          RuntimeProperties runtimeProperties, ToolOutputCapture capture) {
        this.toolGate = toolGate;
        this.pythonRuntime = pythonRuntime;
        this.capture = capture;
        this.workDir = Paths.get(runtimeProperties.getDataDir(), "workspace").toAbsolutePath().normalize();
    }

    /** 执行 Python 脚本 */
    @Tool(name = "python_exec", description = "执行一段 Python 代码并返回标准输出；报错时返回完整 traceback，可据此修正后重试")
    public String pythonExec(
            @ToolParam(name = "code", description = "要执行的 Python 源码") String code,
            @ToolParam(name = "timeoutSeconds", description = "超时秒数，默认 60，上限 300", required = false)
            Integer timeoutSeconds) {
        if (!toolGate.isEnabled("python_exec")) {
            return error("工具 python_exec 已被禁用");
        }
        try {
            var result = pythonRuntime.execute(new ExecRequest(code,
                    Objects.nonNull(timeoutSeconds) ? timeoutSeconds : DEFAULT_TOOL_TIMEOUT, workDir));
            String output = result.success()
                    ? (StrUtil.isBlank(result.stdout()) ? "（无标准输出）" : result.stdout())
                    : error("exitCode=" + result.exitCode() + "\nstdout:\n" + result.stdout()
                            + "\nstderr:\n" + result.stderr());
            capture.record("python_exec", output);
            return output;
        } catch (BizException e) {
            return error(e.getMessage());
        }
    }

    /** 读取数据目录内文件 */
    @Tool(name = "file_read", description = "读取工作区内文本文件的全部内容")
    public String fileRead(
            @ToolParam(name = "path", description = "相对工作区根目录的路径") String path) {
        if (!toolGate.isEnabled("file_read")) {
            return error("工具 file_read 已被禁用");
        }
        try {
            Path target = resolve(path);
            String content = Files.readString(target, StandardCharsets.UTF_8);
            String output = OutputSanitizer.clean(content);
            capture.record("file_read", output);
            return output;
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("读取失败：" + e.getMessage());
        }
    }

    /** 写文件到数据目录 */
    @Tool(name = "file_write", description = "把文本写入工作区文件，父目录自动创建，覆盖同名文件")
    public String fileWrite(
            @ToolParam(name = "path", description = "相对工作区根目录的路径") String path,
            @ToolParam(name = "content", description = "要写入的文本内容") String content) {
        if (!toolGate.isEnabled("file_write")) {
            return error("工具 file_write 已被禁用");
        }
        try {
            Path target = resolve(path);
            FileUtil.mkParentDirs(target.toFile());
            Files.writeString(target, Objects.toString(content, ""), StandardCharsets.UTF_8);
            String output = "已写入 " + target + "（" + content.length() + " 字符）";
            capture.record("file_write", output);
            return output;
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("写入失败：" + e.getMessage());
        }
    }

    /** 发起 GET 请求 */
    @Tool(name = "http_get", description = "对给定 URL 发起 GET 请求，返回响应文本（截断至 8000 字符）")
    public String httpGet(
            @ToolParam(name = "url", description = "完整 http(s) 地址") String url) {
        if (!toolGate.isEnabled("http_get")) {
            return error("工具 http_get 已被禁用");
        }
        try {
            String body = HttpUtil.createGet(url)
                    .timeout((int) Duration.ofSeconds(DEFAULT_TOOL_TIMEOUT).toMillis())
                    .execute().body();
            String output = OutputSanitizer.clean(Objects.toString(body, ""));
            capture.record("http_get", output);
            return output;
        } catch (Exception e) {
            return error("请求失败：" + e.getMessage());
        }
    }

    /** 把相对路径限定到工作区，拒绝越界 */
    private Path resolve(String relative) {
        Path target = workDir.resolve(Objects.toString(relative, "")).normalize();
        if (!target.startsWith(workDir)) {
            throw new BizException(ErrorCodeEnum.PYTHON_UNSAFE_PATH, relative);
        }
        return target;
    }

    private String error(String message) {
        return "ERROR: " + message;
    }
}
