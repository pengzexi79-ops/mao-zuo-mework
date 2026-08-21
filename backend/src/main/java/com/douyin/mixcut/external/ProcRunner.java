package com.douyin.mixcut.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 统一的外部进程调用器（ffmpeg / ffprobe / yt-dlp / you-get 全走这里）。 */
@Slf4j
@Component
public class ProcRunner {

    private static final int OUTPUT_TAIL_LIMIT = 32_000;
    private static final int ANALYSIS_OUTPUT_LIMIT = 2_000_000;

    public record Result(int code, String out) {
        public boolean ok() {
            return code == 0;
        }
    }

    /** 分离 stdout/stderr 的进程结果：out 是标准输出原文，err 是标准错误原文（均可能截断为尾部）。 */
    public record SeparateResult(int code, String out, String err) {
        public boolean ok() {
            return code == 0;
        }
    }

    /**
     * 分离读取 stdout 与 stderr 的进程调用。用于解析类命令（ffprobe JSON 等）：
     * 合并流会把 stderr 的诊断/警告文本混进 stdout，破坏纯文本解析。
     * stdout 保留尾部 ANALYSIS_OUTPUT_LIMIT，stderr 保留尾部 OUTPUT_TAIL_LIMIT；
     * 两个输出流各由独立 reader 排空，避免管道写满导致子进程阻塞。
     */
    public SeparateResult runSeparate(List<String> cmd, long timeoutSec) {
        TailBuffer output = new TailBuffer(ANALYSIS_OUTPUT_LIMIT);
        TailBuffer error = new TailBuffer(OUTPUT_TAIL_LIMIT);
        Process process = null;
        Thread outReader = null;
        Thread errReader = null;
        try {
            if (cmd == null || cmd.isEmpty()) return new SeparateResult(-1, "", "[exception] empty command");
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(false);
            process = builder.start();
            Process activeProcess = process;
            outReader = new Thread(() -> copyOutput(activeProcess.getInputStream(), output), "proc-out-reader");
            errReader = new Thread(() -> copyOutput(activeProcess.getErrorStream(), error), "proc-err-reader");
            outReader.setDaemon(true);
            errReader.setDaemon(true);
            outReader.start();
            errReader.start();

            long safeTimeout = Math.max(1, timeoutSec);
            boolean finished = process.waitFor(safeTimeout, TimeUnit.SECONDS);
            if (!finished) {
                terminateTree(process);
                closeOutput(process);
                joinReader(outReader);
                joinReader(errReader);
                return new SeparateResult(-2, output.text(),
                        diagnostic(error, "timeout after " + safeTimeout
                                + "s; process tree terminated; command=" + commandSummary(cmd)));
            }
            closeOutput(process);
            joinReader(outReader);
            joinReader(errReader);
            int code = process.exitValue();
            if (code == 0) {
                return new SeparateResult(code, output.text(), error.text());
            }
            String tail = error.text().isBlank() ? output.text() : error.text();
            return new SeparateResult(code, output.text(),
                    "process exited with code " + code + "; command=" + commandSummary(cmd)
                            + (tail.isBlank() ? "" : "\n" + tail));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateTree(process);
                closeOutput(process);
            }
            joinReader(outReader);
            joinReader(errReader);
            return new SeparateResult(-3, output.text(), diagnostic(error, "interrupted; command=" + commandSummary(cmd)));
        } catch (Exception e) {
            log.warn("runSeparate failed: {} -> {}", cmd == null || cmd.isEmpty() ? "" : cmd.get(0), e.toString());
            if (process != null) {
                terminateTree(process);
                closeOutput(process);
            }
            joinReader(outReader);
            joinReader(errReader);
            return new SeparateResult(-1, output.text(), diagnostic(error, "exception: " + e + "; command=" + commandSummary(cmd)));
        } finally {
            if (process != null && process.isAlive()) terminateTree(process);
        }
    }

    /**
     * 在独立 reader 中持续排空合并后的 stdout/stderr，主线程可按时 waitFor，避免外部进程
     * 长时间不关闭输出流时把 timeout 卡死。只保留错误输出尾部，既满足诊断需要又不会让
     * ffmpeg 的进度日志无限占用内存。
     */
    public Result run(List<String> cmd, long timeoutSec) {
        TailBuffer output = new TailBuffer(OUTPUT_TAIL_LIMIT);
        Process process = null;
        Thread reader = null;
        try {
            if (cmd == null || cmd.isEmpty()) return new Result(-1, "[exception] empty command");
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            process = builder.start();
            Process activeProcess = process;
            reader = new Thread(() -> copyOutput(activeProcess.getInputStream(), output), "proc-output-reader");
            reader.setDaemon(true);
            reader.start();

            long safeTimeout = Math.max(1, timeoutSec);
            boolean finished = process.waitFor(safeTimeout, TimeUnit.SECONDS);
            if (!finished) {
                terminateTree(process);
                closeOutput(process);
                joinReader(reader);
                return new Result(-2, diagnostic(output, "timeout after " + safeTimeout
                        + "s; process tree terminated; command=" + commandSummary(cmd)));
            }
            closeOutput(process);
            joinReader(reader);
            int code = process.exitValue();
            return new Result(code, code == 0 ? output.text()
                    : diagnostic(output, "process exited with code " + code + "; command=" + commandSummary(cmd)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateTree(process);
                closeOutput(process);
            }
            joinReader(reader);
            return new Result(-3, diagnostic(output, "interrupted; command=" + commandSummary(cmd)));
        } catch (Exception e) {
            log.warn("run failed: {} -> {}", cmd == null || cmd.isEmpty() ? "" : cmd.get(0), e.toString());
            if (process != null) {
                terminateTree(process);
                closeOutput(process);
            }
            joinReader(reader);
            return new Result(-1, diagnostic(output, "exception: " + e + "; command=" + commandSummary(cmd)));
        } finally {
            if (process != null && process.isAlive()) terminateTree(process);
        }
    }

    private void copyOutput(InputStream in, TailBuffer output) {
        try (in) {
            byte[] bytes = new byte[4096];
            int read;
            while ((read = in.read(bytes)) >= 0) {
                if (read > 0) output.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            output.append("\n[output reader] " + e + "\n");
        }
    }

    private void closeOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (Exception ignore) {
            // 进程可能已自行关闭输出流。
        }
    }

    private String diagnostic(TailBuffer output, String reason) {
        String tail = output.text();
        String prefix = output.truncated() ? "[output truncated; retained tail]\n" : "";
        return prefix + "[" + reason + "]" + (tail.isBlank() ? "" : "\n" + tail);
    }

    private String commandSummary(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return "<empty>";
        String joined = String.join(" ", cmd);
        return joined.length() <= 1000 ? joined : joined.substring(0, 997) + "...";
    }

    private void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 尽力终止子进程，再终止父进程；ffmpeg 在平台上偶尔会派生辅助进程。 */
    private void terminateTree(Process process) {
        try {
            process.toHandle().descendants()
                    .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                    .forEach(handle -> {
                        if (handle.isAlive()) handle.destroyForcibly();
                    });
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
        } catch (Exception e) {
            log.debug("process termination failed: {}", e.toString());
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** 线程安全的固定容量尾部缓冲区。 */
    private static final class TailBuffer {
        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        private TailBuffer(int limit) {
            this.limit = limit;
        }

        synchronized void append(String text) {
            value.append(text);
            int excess = value.length() - limit;
            if (excess > 0) {
                value.delete(0, excess);
                truncated = true;
            }
        }

        synchronized String text() {
            return value.toString();
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }

    /** 探测某个可执行文件是否可用 */
    public boolean available(String exe, String versionFlag) {
        try {
            Result result = run(List.of(exe, versionFlag), 12);
            return result.code() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测以命令前缀启动的工具是否可用（如 python -m 模块）。 */
    public boolean available(List<String> prefix, String versionFlag) {
        try {
            List<String> cmd = new ArrayList<>(prefix);
            cmd.add(versionFlag);
            Result result = run(cmd, 12);
            return result.code() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
