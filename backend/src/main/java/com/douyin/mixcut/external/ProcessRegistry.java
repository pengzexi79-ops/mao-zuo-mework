package com.douyin.mixcut.external;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Owns external processes started by one application task. */
@Component
public class ProcessRegistry {
    private final Map<CancellationContext, Set<Registration>> active = new ConcurrentHashMap<>();
    private final Map<CancellationContext, Set<OutputRegistration>> outputs = new ConcurrentHashMap<>();
    private final Map<String, CancellationContext> contexts = new ConcurrentHashMap<>();

    private record OutputRegistration(Path path, Path root) {}

    /** Creates a task-owned cancellation context. */
    public CancellationContext create(String taskKey) {
        CancellationContext requested = new CancellationContext(taskKey);
        if (!requested.isTracked()) return requested;
        return contexts.computeIfAbsent(requested.taskKey(), ignored -> requested);
    }

    /** Creates a fresh context for a new execution generation after a prior generation was cancelled. */
    public CancellationContext replace(String taskKey) {
        CancellationContext fresh = new CancellationContext(taskKey);
        if (!fresh.isTracked()) return fresh;
        CancellationContext previous = contexts.put(taskKey, fresh);
        if (previous != null) {
            cancel(previous);
            cleanupOutputs(previous);
            active.remove(previous);
            outputs.remove(previous);
        }
        return fresh;
    }

    /** Immutable task cancellation signal. The task key is created only by the backend. */
    public static final class CancellationContext {
        private final String taskKey;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public CancellationContext(String taskKey) {
            this.taskKey = taskKey == null || taskKey.isBlank() ? null : taskKey;
        }

        public static CancellationContext none() {
            return new CancellationContext(null);
        }

        public String taskKey() {
            return taskKey;
        }

        public boolean isTracked() {
            return taskKey != null;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** Throws a standard cancellation exception at a cooperative cancellation boundary. */
        public void throwIfCancelled() {
            if (isCancelled()) throw new CancellationException("Task cancelled: " + taskKey);
        }

        boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }
    }

    /** Registration deliberately keeps identity equality; stale workers cannot remove new processes. */
    public static final class Registration {
        private final CancellationContext context;
        private final Process process;

        private Registration(CancellationContext context, Process process) {
            this.context = context;
            this.process = process;
        }

        public CancellationContext context() {
            return context;
        }

        public Process process() {
            return process;
        }
    }

    /** Registers an application-owned output path under a task and its allowed root. */
    public boolean registerOutput(CancellationContext context, Path output, Path outputRoot) {
        if (context == null || !context.isTracked() || output == null || outputRoot == null) return false;
        Path root = outputRoot.toAbsolutePath().normalize();
        Path path = output.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) return false;
        if (context.isCancelled()) {
            deleteTree(path);
            return false;
        }
        outputs.computeIfAbsent(context, ignored -> ConcurrentHashMap.newKeySet())
                .add(new OutputRegistration(path, root));
        if (context.isCancelled()) cleanupOutputs(context);
        return true;
    }

    /** Removes one previously registered output without touching source media. */
    public boolean forgetOutput(CancellationContext context, Path output) {
        if (context == null || !context.isTracked() || output == null) return false;
        Set<OutputRegistration> paths = outputs.get(context);
        if (paths == null) return false;
        Path normalized = output.toAbsolutePath().normalize();
        boolean removed = paths.removeIf(registration -> registration.path().equals(normalized));
        if (paths.isEmpty()) outputs.remove(context, paths);
        return removed;
    }

    /** Cleans only output paths registered for this task; unregistered/source paths are ignored. */
    public int cleanupOutputs(CancellationContext context) {
        if (context == null || !context.isTracked()) return 0;
        Set<OutputRegistration> paths = outputs.get(context);
        if (paths == null) return 0;
        int cleaned = 0;
        for (OutputRegistration registration : new ArrayList<>(paths)) {
            if (!registration.path().startsWith(registration.root()) || registration.path().equals(registration.root())) {
                paths.remove(registration);
                continue;
            }
            if (deleteTree(registration.path())) {
                paths.remove(registration);
                cleaned++;
            }
        }
        if (paths.isEmpty()) outputs.remove(context, paths);
        return cleaned;
    }

    public Registration register(CancellationContext context, Process process) {
        if (context == null || !context.isTracked() || process == null) return null;
        Registration registration = new Registration(context, process);
        active.computeIfAbsent(context, ignored -> ConcurrentHashMap.newKeySet()).add(registration);
        if (context.isCancelled()) terminate(process);
        return registration;
    }

    public void remove(Registration registration) {
        if (registration == null || registration.context().taskKey() == null) return;
        active.computeIfPresent(registration.context(), (key, registrations) -> {
            registrations.removeIf(candidate -> candidate == registration);
            return registrations.isEmpty() ? null : registrations;
        });
    }

    /** Marks the task cancelled before terminating only its currently registered processes. */
    public int cancel(CancellationContext context) {
        if (context == null) return 0;
        context.markCancelled();
        String key = context.taskKey();
        if (key == null) return 0;
        Set<Registration> registrations = active.get(context);
        if (registrations == null) return 0;
        List<Registration> snapshot = new ArrayList<>(registrations);
        for (Registration registration : snapshot) terminate(registration.process());
        return snapshot.size();
    }

    /** Cancels a task by key when the caller does not retain its context. */
    public int cancel(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) return 0;
        CancellationContext context = contexts.computeIfAbsent(taskKey, CancellationContext::new);
        return cancel(context);
    }

    /** Forgets task bookkeeping without deleting registered output files. */
    public void forget(CancellationContext context) {
        if (context == null || !context.isTracked()) return;
        active.remove(context);
        outputs.remove(context);
        contexts.remove(context.taskKey(), context);
    }

    private boolean deleteTree(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return Files.deleteIfExists(path);
            }
            try (Stream<Path> paths = Files.walk(path)) {
                paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                    try {
                        if (Files.isSymbolicLink(candidate) || Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(candidate);
                        } else if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(candidate);
                        }
                    } catch (IOException ignored) { }
                });
            }
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Used by ProcRunner for timeout/interruption without marking a task cancelled. */
    void terminate(Process process) {
        if (process == null) return;
        try {
            process.toHandle().descendants()
                    .sorted((left, right) -> Long.compare(right.pid(), left.pid()))
                    .forEach(handle -> {
                        if (handle.isAlive()) handle.destroyForcibly();
                    });
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
