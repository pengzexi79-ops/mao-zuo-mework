package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

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
