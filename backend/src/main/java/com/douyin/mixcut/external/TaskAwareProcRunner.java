package com.douyin.mixcut.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Adds task-scoped cancellation to the existing, security-reviewed process runner. */
@Slf4j
@Component
public class TaskAwareProcRunner {
    private final ProcRunner delegate;

    public TaskAwareProcRunner(ProcRunner delegate) {
        this.delegate = delegate;
    }

    public ProcRunner.Result run(java.util.List<String> command, long timeoutSec,
                                 ProcessRegistry.CancellationContext context) {
        ProcessRegistry.CancellationContext effective = context == null
                ? ProcessRegistry.CancellationContext.none() : context;
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();
        Thread watcher = startWatcher(effective, caller, finished);
        try {
            ProcRunner.Result result = delegate.run(command, timeoutSec);
            if (effective.isCancelled()) return new ProcRunner.Result(-4, "[cancelled]" + suffix(result.out()));
            return result;
        } finally {
            finished.set(true);
            watcher.interrupt();
        }
    }

    public ProcRunner.SeparateResult runSeparate(java.util.List<String> command, long timeoutSec,
                                                  ProcessRegistry.CancellationContext context) {
        ProcessRegistry.CancellationContext effective = context == null
                ? ProcessRegistry.CancellationContext.none() : context;
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();
        Thread watcher = startWatcher(effective, caller, finished);
        try {
            ProcRunner.SeparateResult result = delegate.runSeparate(command, timeoutSec);
            if (effective.isCancelled()) {
                return new ProcRunner.SeparateResult(-4, result.out(), "[cancelled]" + suffix(result.err()));
            }
            return result;
        } finally {
            finished.set(true);
            watcher.interrupt();
        }
    }

    private Thread startWatcher(ProcessRegistry.CancellationContext context,
                                Thread caller, AtomicBoolean finished) {
        Thread watcher = new Thread(() -> {
            try {
                while (!finished.get()) {
                    if (context.isCancelled()) {
                        caller.interrupt();
                        return;
                    }
                    Thread.sleep(25L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                log.debug("task cancellation watcher stopped: {}", error.toString());
            }
        }, "task-cancellation-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private String suffix(String value) {
        return value == null || value.isBlank() ? "" : "\n" + value;
    }
}
