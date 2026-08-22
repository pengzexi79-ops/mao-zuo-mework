package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAwareProcRunnerTest {
    @Test
    void cancellationInterruptsOnlyTheTaskAwareCall() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        TaskAwareProcRunner runner = new TaskAwareProcRunner(new ProcRunner());
        ProcessRegistry.CancellationContext context = registry.create("task-aware-test");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProcRunner.Result> future = executor.submit(() -> runner.run(
                    List.of("cmd.exe", "/c", "ping", "127.0.0.1", "-n", "20"), 30, context));
            Thread.sleep(250L);
            registry.cancel(context);
            ProcRunner.Result result = future.get(5, TimeUnit.SECONDS);
            assertEquals(-4, result.code());
            assertTrue(context.isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }
}
