package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRegistryTest {
    @TempDir
    Path temp;

    @Test
    void cancellationContextIsReusedAndCancellationIsScopedByTaskKey() {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext first = registry.create("task-a");
        ProcessRegistry.CancellationContext same = registry.create("task-a");
        ProcessRegistry.CancellationContext other = registry.create("task-b");

        assertTrue(first == same);
        assertFalse(other.isCancelled());
        registry.cancel("task-a");

        assertTrue(first.isCancelled());
        assertTrue(same.isCancelled());
        assertFalse(other.isCancelled());
        assertThrows(CancellationException.class, first::throwIfCancelled);
    }

    @Test
    void replacementKeepsGenerationsOutputCleanupIsolated() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext first = registry.create("task-generation");
        Path root = temp.resolve("generation");
        Path firstOutput = root.resolve("first.mp4");
        Files.createDirectories(root);
        Files.writeString(firstOutput, "first");
        assertTrue(registry.registerOutput(first, firstOutput, root));

        ProcessRegistry.CancellationContext second = registry.replace("task-generation");
        Path secondOutput = root.resolve("second.mp4");
        Files.writeString(secondOutput, "second");
        assertTrue(registry.registerOutput(second, secondOutput, root));

        registry.cleanupOutputs(first);
        assertTrue(Files.exists(secondOutput));
        assertFalse(Files.exists(firstOutput));
    }

    @Test
    void lateOutputRegistrationAfterCancellationDeletesTheOutput() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext context = registry.create("task-late-output");
        Path root = temp.resolve("late-output");
        Path output = root.resolve("late.mp4");
        Files.createDirectories(root);
        Files.writeString(output, "late");

        registry.cancel(context);

        assertFalse(registry.registerOutput(context, output, root));
        assertFalse(Files.exists(output));
    }

    @Test
    void cleanupRetainsMissingOutputRegistrationForLateMove() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext context = registry.create("task-late-move");
        Path root = temp.resolve("late-move");
        Path output = root.resolve("final.mp4");
        Files.createDirectories(root);
        assertTrue(registry.registerOutput(context, output, root));

        assertEquals(0, registry.cleanupOutputs(context));
        Files.writeString(output, "late output");

        assertEquals(1, registry.cleanupOutputs(context));
        assertFalse(Files.exists(output));
    }

    @Test
    void cleanupRefusesToFollowSymlinkedOutputParent() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext context = registry.create("task-symlink-output");
        Path root = temp.resolve("output-root");
        Path outside = temp.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Path redirected = root.resolve("redirected");
        try {
            Files.createSymbolicLink(redirected, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            return;
        }
        Path external = outside.resolve("result.mp4");
        Files.writeString(external, "outside");
        assertTrue(registry.registerOutput(context, redirected.resolve("result.mp4"), root));

        registry.cleanupOutputs(context);

        assertTrue(Files.exists(external));
    }

    @Test
    void cleanupOnlyDeletesRegisteredDescendantAndKeepsSourceOutsideRoot() throws Exception {
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext context = registry.create("task-output");
        Path root = temp.resolve("media-tools");
        Path output = root.resolve("generated").resolve("result.mp4");
        Path source = temp.resolve("source.mp4");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "generated");
        Files.writeString(source, "source");

        assertTrue(registry.registerOutput(context, output, root));
        assertFalse(registry.registerOutput(context, source, root));
        assertTrue(Files.exists(output));
        assertTrue(Files.exists(source));

        registry.cleanupOutputs(context);

        assertFalse(Files.exists(output));
        assertTrue(Files.exists(source));
    }
}
