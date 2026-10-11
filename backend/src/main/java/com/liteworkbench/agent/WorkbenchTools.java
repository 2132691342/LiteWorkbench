package com.liteworkbench.agent;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.OutputSanitizer;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.domain.constant.KbConst;
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

/** 内置工具集：启用状态读 wb_tool，禁用即拒答，失败以 ERROR: 前缀文本返回 */
@Slf4j
public class WorkbenchTools {

    /** 工具默认超时，秒 */
    private static final long DEFAULT_TOOL_TIMEOUT = 60L;

    private final ToolGate toolGate;
    private final KbSearchPort kbSearchPort;
    private final PythonRuntime pythonRuntime;
    private final ToolOutputCapture capture;
    /** 当前运行归属用户，kb_search 以此限定检索范围 */
    private final String userId;
    /** 工作区根目录，按用户隔离，运行期不变 */
    private final Path workDir;

    public WorkbenchTools(ToolGate toolGate, KbSearchPort kbSearchPort, PythonRuntime pythonRuntime,
                          RuntimeProperties runtimeProperties, ToolOutputCapture capture, String userId) {
        this.toolGate = toolGate;
        this.kbSearchPort = kbSearchPort;
        this.pythonRuntime = pythonRuntime;
        this.capture = capture;
        this.userId = userId;
        String owner = StrUtil.isBlank(userId) ? "anonymous" : userId;
        this.workDir = Paths.get(runtimeProperties.getDataDir(), "workspace", owner)
                .toAbsolutePath().normalize();
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

    /** 检索当前用户知识库 */
    @Tool(name = "kb_search", description = "检索当前用户知识库，返回与关键词最相关的原文片段（带文档名与块序号），回答引用用户资料时先用它取证")
    public String kbSearch(
            @ToolParam(name = "keyword", description = "检索关键词") String keyword,
            @ToolParam(name = "kbId", description = "限定知识库 ID，不传则跨全部知识库", required = false)
            String kbId) {
        if (!toolGate.isEnabled(KbConst.TOOL_NAME)) {
            return error("工具 kb_search 已被禁用");
        }
        if (StrUtil.isBlank(keyword)) {
            return error("keyword 不能为空");
        }
        try {
            String output = kbSearchPort.search(userId, keyword, kbId, KbConst.TOOL_SEARCH_LIMIT);
            capture.record(KbConst.TOOL_NAME, output);
            return output;
        } catch (Exception e) {
            return error("检索失败：" + e.getMessage());
        }
    }

    /** 把相对路径限定到用户工作区：拒绝空路径、绝对路径、任何上级目录片段与越界结果 */
    private Path resolve(String relative) {
        String raw = Objects.toString(relative, "");
        if (StrUtil.isBlank(raw) || raw.contains("..") || Paths.get(raw).isAbsolute()) {
            throw new BizException(ErrorCodeEnum.PYTHON_UNSAFE_PATH, raw);
        }
        Path target = workDir.resolve(raw).normalize();
        if (!target.startsWith(workDir)) {
            throw new BizException(ErrorCodeEnum.PYTHON_UNSAFE_PATH, raw);
        }
        return target;
    }

    private String error(String message) {
        return "ERROR: " + message;
    }
}
