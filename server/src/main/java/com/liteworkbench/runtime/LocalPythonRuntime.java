package com.liteworkbench.runtime;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.OutputSanitizer;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.IdPrefixConst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 本机 Python 运行时：探测顺序 配置路径 → 数据目录内置解释器 → PATH，
 * 执行带超时与进程树终止，输出清洗后保尾截断。
 */
@Slf4j
@Component
public class LocalPythonRuntime implements PythonRuntime {

    /** 单次执行超时硬上限，秒 */
    private static final long MAX_TIMEOUT_SECONDS = 300L;
    /** 探测命令超时，秒 */
    private static final long PROBE_TIMEOUT_SECONDS = 5L;
    /** 版本输出形如 Python 3.12.1 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(\\.(\\d+))?");
    /** 路径穿越片段 */
    private static final Pattern TRAVERSAL_PATTERN = Pattern.compile("(\\.\\./|\\.\\.\\\\){2,}");

    private final RuntimeProperties properties;

    /** 双检锁探测缓存 */
    private volatile RuntimeStatus cachedStatus;
    private final Object probeFlight = new Object();

    public LocalPythonRuntime(RuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuntimeStatus detect() {
        RuntimeStatus status = cachedStatus;
        if (Objects.isNull(status)) {
            synchronized (probeFlight) {
                status = cachedStatus;
                if (Objects.isNull(status)) {
                    status = doProbe();
                    cachedStatus = status;
                }
            }
        }
        return status;
    }

    @Override
    public void resetProbe() {
        cachedStatus = null;
        log.info("[python.reprobe] 探测缓存已清空");
    }

    @Override
    public ExecResult execute(ExecRequest request) {
        RuntimeStatus status = detect();
        if (!status.ok()) {
            throw new BizException(ErrorCodeEnum.PYTHON_NOT_READY, status.status());
        }
        Path workDir = confineWorkDir(request.workDir());
        long timeout = normalizeTimeout(request.timeoutSeconds());
        checkUnsafe(request.code());

        Path script = writeScript(request.code());
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(status.source(), script.toAbsolutePath().toString()));
            builder.directory(workDir.toFile());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = builder.start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                return new ExecResult(-1, "", "执行超时（" + timeout + " 秒），进程树已终止", true);
            }
            String stdout = OutputSanitizer.clean(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            String stderr = OutputSanitizer.clean(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            return new ExecResult(process.exitValue(), stdout, stderr, false);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PYTHON_EXEC_FAILED, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodeEnum.PYTHON_EXEC_FAILED, "执行被中断");
        } finally {
            FileUtil.del(script.toFile());
        }
    }

    /** 探测：按顺序尝试候选解释器，取第一个能输出版本的 */
    private RuntimeStatus doProbe() {
        List<String> candidates = buildCandidates();
        for (String candidate : candidates) {
            String version = readVersion(candidate);
            if (Objects.isNull(version)) {
                continue;
            }
            var matcher = VERSION_PATTERN.matcher(version);
            if (matcher.find()) {
                int major = Integer.parseInt(matcher.group(1));
                int minor = Integer.parseInt(matcher.group(2));
                boolean ok = major > 3 || (major == 3 && minor >= RuntimeStatus.MIN_VERSION);
                RuntimeStatus status = new RuntimeStatus(ok ? RuntimeStatus.READY : RuntimeStatus.BAD_VERSION,
                        major + "." + minor, candidate, ok);
                log.info("[python.probe] source={} version={} ok={}", candidate, status.version(), ok);
                return status;
            }
        }
        log.warn("[python.probe] 未找到可用解释器，候选={} ", candidates.size());
        return new RuntimeStatus(RuntimeStatus.NOT_FOUND, null, null, false);
    }

    /** 候选解释器：配置路径 → 数据目录 → PATH */
    private List<String> buildCandidates() {
        var list = new java.util.ArrayList<String>();
        if (StrUtil.isNotBlank(properties.getPython().getPath())) {
            list.add(properties.getPython().getPath());
        }
        String exe = FileUtil.isWindows() ? "python.exe" : "python3";
        list.add(Paths.get(properties.getDataDir(), "runtime", "python", "bin", exe).toString());
        list.add("python3");
        list.add("python");
        return list;
    }

    /** 读解释器版本，失败返回 null */
    private String readVersion(String command) {
        try {
            Process process = new ProcessBuilder(List.of(command, "--version"))
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** 工作目录必须落在数据目录内 */
    private Path confineWorkDir(Path workDir) {
        Path dataDir = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        Path effective = Objects.isNull(workDir)
                ? dataDir.resolve("python")
                : workDir.toAbsolutePath().normalize();
        if (!effective.startsWith(dataDir)) {
            throw new BizException(ErrorCodeEnum.PYTHON_UNSAFE_PATH, effective.toString());
        }
        FileUtil.mkParentDirs(effective.toFile());
        return effective;
    }

    /** 超时取调用值与默认值的较小者，封顶 300 秒 */
    private long normalizeTimeout(long timeoutSeconds) {
        long request = timeoutSeconds <= 0
                ? properties.getPython().getTimeoutSeconds() : timeoutSeconds;
        return Math.min(request, MAX_TIMEOUT_SECONDS);
    }

    /** 拒绝显式路径穿越 */
    private void checkUnsafe(String code) {
        if (StrUtil.isNotBlank(code) && TRAVERSAL_PATTERN.matcher(code).find()) {
            throw new BizException(ErrorCodeEnum.PYTHON_UNSAFE_PATH);
        }
    }

    /** 脚本落盘临时目录，执行后由调用方删除 */
    private Path writeScript(String code) {
        try {
            Path script = Files.createTempFile("lwb-python-" + IdGenerator.raw(), ".py");
            Files.writeString(script, Objects.toString(code, ""), StandardCharsets.UTF_8);
            return script;
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PYTHON_EXEC_FAILED, "脚本落盘失败：" + e.getMessage());
        }
    }

    /** 按进程树终止：先子孙后自身，防孤儿进程占用文件与端口 */
    private void killTree(Process process) {
        try {
            process.descendants().forEach(handle -> handle.destroyForcibly());
            process.destroyForcibly();
            process.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[python.kill] 进程树终止异常 {}", e.getMessage());
        }
    }

    /** 供健康探测复用 */
    public Duration probeTimeout() {
        return Duration.ofSeconds(PROBE_TIMEOUT_SECONDS);
    }
}
