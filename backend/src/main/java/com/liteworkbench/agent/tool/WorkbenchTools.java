package com.liteworkbench.agent.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpUtil;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.agent.spi.MemoryPort;
import com.liteworkbench.agent.spi.SubRunPort;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.OutputSanitizer;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.KbConst;
import com.liteworkbench.domain.constant.MemoryConst;
import com.liteworkbench.domain.constant.PlanConst;
import com.liteworkbench.domain.constant.ToolRiskEnum;
import com.liteworkbench.runtime.ExecRequest;
import com.liteworkbench.runtime.PythonRuntime;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 内置工具集：启用状态读 wb_tool，禁用即拒答，失败以 ERROR: 前缀文本返回让模型换路 */
@Slf4j
public class WorkbenchTools {

    /** 工具默认超时，秒 */
    private static final long DEFAULT_TOOL_TIMEOUT = 60L;
    /** HTTP 请求超时，秒 */
    private static final int HTTP_TIMEOUT_SECONDS = 60;

    private final ToolGate toolGate;
    private final KbSearchPort kbSearchPort;
    private final MemoryPort memoryPort;
    private final SubRunPort subRunPort;
    private final PythonRuntime pythonRuntime;
    /** 本次运行的归属身份，检索与记忆以此限定范围 */
    private final ToolOwner owner;
    private final String userId;
    /** 工作区根目录，按用户隔离，运行期不变 */
    private final Path workDir;

    public WorkbenchTools(ToolGate toolGate, KbSearchPort kbSearchPort, MemoryPort memoryPort,
                          SubRunPort subRunPort, PythonRuntime pythonRuntime,
                          RuntimeProperties runtimeProperties, ToolOwner owner) {
        this.toolGate = toolGate;
        this.kbSearchPort = kbSearchPort;
        this.memoryPort = memoryPort;
        this.subRunPort = subRunPort;
        this.pythonRuntime = pythonRuntime;
        this.owner = owner;
        this.userId = owner.userId();
        String scope = StrUtil.isBlank(userId) ? "anonymous" : userId;
        this.workDir = Paths.get(runtimeProperties.getDataDir(), "workspace", scope)
                .toAbsolutePath().normalize();
    }

    /** 执行 Python 脚本 */
    @AgentTool(name = "python_exec", risk = ToolRiskEnum.CONFIRM, parallelSafe = false,
            description = "执行一段 Python 代码并返回标准输出。脚本无交互输入，结果只经 stdout 返回，"
                    + "需要的数据务必 print 出来；报错时返回完整 traceback，可据此修正后重试；"
                    + "单次上限 300 秒，长任务拆成多次调用")
    public String pythonExec(
            @AgentToolParam(name = "code", description = "要执行的 Python 源码") String code,
            @AgentToolParam(name = "timeoutSeconds", description = "超时秒数，默认 60，上限 300",
                    required = false, type = "integer") Integer timeoutSeconds) {
        if (!toolGate.isEnabled("python_exec")) {
            return error("工具 python_exec 已被禁用");
        }
        try {
            var result = pythonRuntime.execute(new ExecRequest(code,
                    Objects.nonNull(timeoutSeconds) ? timeoutSeconds : DEFAULT_TOOL_TIMEOUT, workDir));
            if (result.success()) {
                return StrUtil.isBlank(result.stdout()) ? "（无标准输出）" : result.stdout();
            }
            return error("exitCode=" + result.exitCode() + "\nstdout:\n" + result.stdout()
                    + "\nstderr:\n" + result.stderr());
        } catch (BizException e) {
            return error(e.getMessage());
        }
    }

    /** 读取工作区内文本文件 */
    @AgentTool(name = "file_read", risk = ToolRiskEnum.SAFE,
            description = "读取工作区内文本文件的全部内容")
    public String fileRead(
            @AgentToolParam(name = "path", description = "相对工作区根目录的路径") String path) {
        if (!toolGate.isEnabled("file_read")) {
            return error("工具 file_read 已被禁用");
        }
        try {
            String content = Files.readString(resolve(path), StandardCharsets.UTF_8);
            return OutputSanitizer.clean(content);
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("读取失败：" + e.getMessage());
        }
    }

    /** 写入工作区内文件 */
    @AgentTool(name = "file_write", risk = ToolRiskEnum.CONFIRM, parallelSafe = false,
            description = "把文本写入工作区文件：父目录自动创建，同名文件整体覆盖（不是追加）。"
                    + "写大文件时一次写全量内容，不要分多次写同一文件")
    public String fileWrite(
            @AgentToolParam(name = "path", description = "相对工作区根目录的路径") String path,
            @AgentToolParam(name = "content", description = "要写入的文本内容") String content) {
        if (!toolGate.isEnabled("file_write")) {
            return error("工具 file_write 已被禁用");
        }
        try {
            Path target = resolve(path);
            FileUtil.mkParentDirs(target.toFile());
            String text = Objects.toString(content, StrUtil.EMPTY);
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return StrUtil.format("已写入 {}（{} 字符）", target, text.length());
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("写入失败：" + e.getMessage());
        }
    }

    /** 发起 GET 请求 */
    @AgentTool(name = "http_get", risk = ToolRiskEnum.SAFE,
            description = "对给定 URL 发起 GET 请求，返回响应文本（截断至 8000 字符）；"
                    + "只允许公网 http/https 地址，内网地址一律拒绝")
    public String httpGet(
            @AgentToolParam(name = "url", description = "完整 http(s) 地址") String url) {
        if (!toolGate.isEnabled("http_get")) {
            return error("工具 http_get 已被禁用");
        }
        try {
            assertPublicTarget(url);
            String body = HttpUtil.createGet(url)
                    .timeout((int) TimeUnit.SECONDS.toMillis(HTTP_TIMEOUT_SECONDS))
                    .execute().body();
            return OutputSanitizer.clean(Objects.toString(body, StrUtil.EMPTY));
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("请求失败：" + e.getMessage());
        }
    }

    /** 目标地址校验：只放行公网 http/https，阻断回环 / 私有 / 链路本地 / 组播地址，避免被当作 SSRF 跳板 */
    private void assertPublicTarget(String url) {
        if (StrUtil.isBlank(url)) {
            throw new BizException(ErrorCodeEnum.TOOL_TARGET_BLOCKED, "url 不能为空");
        }
        URL parsed = URLUtil.url(url);
        String protocol = StrUtil.nullToEmpty(parsed.getProtocol()).toLowerCase();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new BizException(ErrorCodeEnum.TOOL_TARGET_BLOCKED, "只允许 http/https");
        }
        String host = parsed.getHost();
        if (StrUtil.isBlank(host)) {
            throw new BizException(ErrorCodeEnum.TOOL_TARGET_BLOCKED, "url 缺少主机名");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BizException(ErrorCodeEnum.TOOL_TARGET_BLOCKED, "域名无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new BizException(ErrorCodeEnum.TOOL_TARGET_BLOCKED, "不允许访问内网地址：" + host);
            }
        }
    }

    /** 检索当前用户知识库 */
    @AgentTool(name = "kb_search", risk = ToolRiskEnum.SAFE,
            description = "检索当前用户知识库，返回与关键词最相关的原文片段（带文档名与块序号）。"
                    + "回答引用用户资料前必须先用它取证；首次检索命中率低时，换同义词或更具体的词重试，"
                    + "不要凭一次空结果断言\"资料里没有\"")
    public String kbSearch(
            @AgentToolParam(name = "keyword", description = "检索关键词") String keyword,
            @AgentToolParam(name = "kbId", description = "限定知识库 ID，不传则跨全部知识库", required = false)
            String kbId) {
        if (!toolGate.isEnabled(KbConst.TOOL_NAME)) {
            return error("工具 kb_search 已被禁用");
        }
        if (StrUtil.isBlank(keyword)) {
            return error("keyword 不能为空");
        }
        try {
            return kbSearchPort.search(userId, keyword, kbId, KbConst.TOOL_SEARCH_LIMIT);
        } catch (Exception e) {
            return error("检索失败：" + e.getMessage());
        }
    }

    /** 规划器：写下或更新整份计划，界面据此渲染进度清单 */
    @AgentTool(name = PlanConst.TOOL_NAME, risk = ToolRiskEnum.SAFE, emitsPlan = true,
            description = "写下或更新整份任务计划，用于长任务分步推进与向用户交代进度。"
                    + "plan 参数每行一步，已完成行以 [x] 开头、进行中 [~]、未开始 [ ]；"
                    + "每次调用必须给出完整计划（覆盖式），不要在旧计划上追加")
    public String taskPlan(
            @AgentToolParam(name = "plan", description = "整份计划，每行一步，行首可带 [x] / [~] / [ ] 状态标记")
            String plan) {
        if (!toolGate.isEnabled(PlanConst.TOOL_NAME)) {
            return error("工具 task_plan 已被禁用");
        }
        var steps = PlanNormalizer.parse(plan);
        if (steps.isEmpty()) {
            return error("计划为空：plan 需要至少一行步骤描述");
        }
        return steps.toString();
    }

    /** 记忆写入：沉淀用户事实与偏好，跨会话复用 */
    @AgentTool(name = MemoryConst.TOOL_WRITE, risk = ToolRiskEnum.CONFIRM, parallelSafe = false,
            description = "把值得长期记住的信息写入用户记忆（如姓名、偏好、长期约束、常用格式要求），"
                    + "同 key 会覆盖旧值。仅记录用户明确表达或可长期复用的信息，不要记录一次性任务细节")
    public String memoryWrite(
            @AgentToolParam(name = "key", description = "记忆键，短且可辨识，如 输出语言偏好") String key,
            @AgentToolParam(name = "value", description = "记忆内容，一句话说清") String value,
            @AgentToolParam(name = "category", description = "维度：fact / preference / constraint / profile，默认 fact",
                    required = false) String category,
            @AgentToolParam(name = "weight", description = "权重 0-9，越大越优先注入，默认 0",
                    required = false, type = "integer") Integer weight) {
        if (!toolGate.isEnabled(MemoryConst.TOOL_WRITE)) {
            return error("工具 memory_write 已被禁用");
        }
        try {
            return memoryPort.write(userId, key, value, category, weight);
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("记忆写入失败：" + e.getMessage());
        }
    }

    /** 记忆检索：按关键词取回已沉淀的记忆 */
    @AgentTool(name = MemoryConst.TOOL_SEARCH, risk = ToolRiskEnum.SAFE,
            description = "检索当前用户的长期记忆，回答涉及用户个人情况与偏好前先用它取证")
    public String memorySearch(
            @AgentToolParam(name = "keyword", description = "检索关键词") String keyword) {
        if (!toolGate.isEnabled(MemoryConst.TOOL_SEARCH)) {
            return error("工具 memory_search 已被禁用");
        }
        if (StrUtil.isBlank(keyword)) {
            return error("keyword 不能为空");
        }
        try {
            return memoryPort.search(userId, keyword, MemoryConst.SEARCH_LIMIT);
        } catch (Exception e) {
            return error("记忆检索失败：" + e.getMessage());
        }
    }

    /** 子任务委派：把一个边界清晰的子任务交给独立运行执行，只看产出 */
    @AgentTool(name = PlanConst.TOOL_DELEGATE, risk = ToolRiskEnum.CONFIRM, parallelSafe = false,
            description = "把一个自成一体的子任务交给独立的子运行执行，返回子任务的最终产出。"
                    + "适合长链路中可独立完成、只需结果不需要中间过程的部分；"
                    + "子任务描述要自带全部上下文，子运行看不到当前对话")
    public String taskDelegate(
            @AgentToolParam(name = "task", description = "子任务描述，需自带完整上下文与期望产出格式") String task,
            @AgentToolParam(name = "timeoutSeconds", description = "等待上限秒数，默认 180，上限 300",
                    required = false, type = "integer") Integer timeoutSeconds) {
        if (!toolGate.isEnabled(PlanConst.TOOL_DELEGATE)) {
            return error("工具 task_delegate 已被禁用，需要先在工具页开启");
        }
        if (StrUtil.isBlank(task)) {
            return error("task 不能为空");
        }
        long timeout = Objects.nonNull(timeoutSeconds) && timeoutSeconds > 0
                ? Math.min(timeoutSeconds, PlanConst.DELEGATE_TIMEOUT_SECONDS * 2) : PlanConst.DELEGATE_TIMEOUT_SECONDS;
        if (StrUtil.isBlank(owner.runId())) {
            return error("子任务委派只在运行内可用，当前没有父运行上下文");
        }
        try {
            return subRunPort.delegate(userId, owner.runId(), owner.sessionId(), task, timeout);
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            return error("子任务执行失败：" + e.getMessage());
        }
    }

    /** 把相对路径限定到用户工作区：拒绝空路径、绝对路径、任何上级目录片段与越界结果 */
    private Path resolve(String relative) {
        String raw = Objects.toString(relative, StrUtil.EMPTY);
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
