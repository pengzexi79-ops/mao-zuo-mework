package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Portable per-computer release notes. It never writes source files or the running Jar. */
@Service
@RequiredArgsConstructor
public class LocalReleaseHistoryService {
    private static final String RESOURCE = "release-notes.json";
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");
    private final ObjectMapper mapper;
    private final AppProps props;
    private Map<String, Object> cachedLocal;
    private long cachedLocalModifiedAt = Long.MIN_VALUE;
    private Map<String, Object> cachedBundled;

    public synchronized Map<String, Object> get() {
        try {
            Path file = file();
            if (!Files.exists(file)) initialize(file);
            Map<String, Object> local = localNotes(file);
            Map<String, Object> bundled = bundledNotes();
            if (compareVersion(String.valueOf(local.get("version")), String.valueOf(bundled.get("version"))) < 0) {
                bundled.put("source", "data/release-history/local-release-notes.json");
                write(file, bundled);
                return bundled;
            }
            if (mergeBundledHistory(local, bundled)) write(file, local);
            return local;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本机版本记录：" + file(), e);
        }
    }

    /** Returns a small history window by default; callers can explicitly request all records. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> view(int historyLimit) {
        Map<String, Object> notes = mapper.convertValue(get(), new TypeReference<LinkedHashMap<String, Object>>() { });
        List<Map<String, Object>> history = (List<Map<String, Object>>) notes.getOrDefault("history", List.of());
        int total = history.size();
        int limit = historyLimit <= 0 ? Integer.MAX_VALUE : historyLimit;
        if (history.size() > limit) notes.put("history", new ArrayList<>(history.subList(0, limit)));
        notes.put("historyTotal", total);
        notes.put("historyHasMore", total > limit);
        return notes;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> notes = get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersion", notes.get("version"));
        result.put("formalJarVersion", bundledVersion());
        result.put("nextVersion", nextPatch(String.valueOf(notes.get("version"))));
        result.put("storagePath", file().toString());
        result.put("portable", true);
        result.put("pending", pending());
        result.put("historyStatus", historyStatus(notes, bundledNotesUnchecked()));
        return result;
    }

    /** Re-merge bundled records into this computer's store and report the complete version range. */
    public synchronized Map<String, Object> syncBundledHistory() {
        Map<String, Object> notes = get();
        Map<String, Object> bundled = bundledNotesUnchecked();
        boolean changed = mergeBundledHistory(notes, bundled);
        if (changed) write(file(), notes);
        Map<String, Object> result = status();
        result.put("synced", true);
        result.put("changed", changed);
        return result;
    }

    public synchronized Map<String, Object> pending() {
        Path pending = file().resolveSibling("pending.json");
        if (!Files.exists(pending)) return new LinkedHashMap<>();
        try {
            Map<String, Object> value = mapper.readValue(pending.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            return value == null ? new LinkedHashMap<>() : value;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本机待发布记录", e);
        }
    }

    public synchronized Map<String, Object> savePending(Map<String, Object> draft) {
        validateDraft(draft);
        write(file().resolveSibling("pending.json"), draft);
        return status();
    }

    public synchronized Map<String, Object> checkPending() {
        Map<String, Object> draft = pending();
        validateDraft(draft);
        return Map.of("valid", true, "nextVersion", nextPatch(String.valueOf(get().get("version"))));
    }

    public synchronized Map<String, Object> apply(Map<String, Object> draft) {
        validateDraft(draft);
        Map<String, Object> notes = get();
        String currentVersion = String.valueOf(notes.get("version"));
        String nextVersion = nextPatch(currentVersion);
        Map<String, Object> oldCurrent = new LinkedHashMap<>();
        for (String key : List.of("id", "version", "kind", "releasedAt", "title", "summary", "changes", "fixes", "verification", "compatibility", "evidence")) {
            oldCurrent.put(key, notes.get(key));
        }
        oldCurrent.put("kind", "历史开发阶段");
        oldCurrent.put("id", releaseId(currentVersion));
        @SuppressWarnings("unchecked") List<Map<String, Object>> history = (List<Map<String, Object>>) notes.getOrDefault("history", new ArrayList<>());
        List<Map<String, Object>> updatedHistory = new ArrayList<>();
        updatedHistory.add(oldCurrent);
        updatedHistory.addAll(history);
        notes.putAll(draft);
        notes.put("version", nextVersion);
        notes.put("id", releaseId(nextVersion));
        notes.put("kind", "当前本机构建");
        notes.put("releasedAt", LocalDate.now().toString());
        notes.put("history", updatedHistory);
        notes.put("source", "data/release-history/local-release-notes.json");
        write(file(), notes);
        write(file().resolveSibling("pending.json"), new LinkedHashMap<>());
        return status();
    }

    public void validateDraft(Map<String, Object> draft) {
        if (draft == null) throw new IllegalArgumentException("更新记录不能为空");
        for (String field : List.of("title", "summary", "compatibility")) {
            if (!(draft.get(field) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("请填写：" + field);
        }
        for (String field : List.of("changes", "fixes", "verification", "evidence")) {
            Object value = draft.get(field);
            if (!(value instanceof List<?> list) || list.isEmpty() || list.stream().anyMatch(item -> String.valueOf(item).isBlank())) {
                throw new IllegalArgumentException("请至少填写一条：" + field);
            }
        }
        String text = String.valueOf(draft.get("evidence")).toLowerCase();
        if (text.contains(".env") || text.contains("token=") || text.contains("password=")) {
            throw new IllegalArgumentException("证据中不能包含密码、令牌或 .env");
        }
    }

    private String bundledVersion() {
        try {
            return String.valueOf(bundledNotes().getOrDefault("version", "-"));
        } catch (IOException e) {
            return "-";
        }
    }

    private Map<String, Object> bundledNotesUnchecked() {
        try {
            return bundledNotes();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取内置完整版本记录", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> historyStatus(Map<String, Object> notes, Map<String, Object> bundled) {
        List<Map<String, Object>> history = (List<Map<String, Object>>) notes.getOrDefault("history", List.of());
        List<Map<String, Object>> bundledHistory = (List<Map<String, Object>>) bundled.getOrDefault("history", List.of());
        List<String> versions = new ArrayList<>();
        versions.add(String.valueOf(notes.get("version")));
        for (Map<String, Object> item : history) versions.add(String.valueOf(item.get("version")));
        versions.sort(this::compareVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", versions.size());
        result.put("bundledCount", bundledHistory.size() + 1);
        result.put("oldestVersion", versions.isEmpty() ? "-" : versions.get(0));
        result.put("newestVersion", versions.isEmpty() ? "-" : versions.get(versions.size() - 1));
        result.put("complete", versions.size() >= bundledHistory.size() + 1);
        return result;
    }

    private Map<String, Object> localNotes(Path file) throws IOException {
        long modifiedAt = Files.getLastModifiedTime(file).toMillis();
        if (cachedLocal == null || cachedLocalModifiedAt != modifiedAt) {
            cachedLocal = mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            cachedLocalModifiedAt = modifiedAt;
        }
        return cachedLocal;
    }

    private Map<String, Object> bundledNotes() throws IOException {
        if (cachedBundled == null) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
                if (input == null) throw new IOException("缺少内置版本记录");
                cachedBundled = mapper.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
            }
        }
        return mapper.convertValue(cachedBundled, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private boolean mergeBundledHistory(Map<String, Object> local, Map<String, Object> bundled) {
        List<Map<String, Object>> localHistory = (List<Map<String, Object>>) local.getOrDefault("history", new ArrayList<>());
        List<Map<String, Object>> bundledHistory = (List<Map<String, Object>>) bundled.getOrDefault("history", new ArrayList<>());
        java.util.Set<String> known = new java.util.HashSet<>();
        known.add(String.valueOf(local.get("version")));
        for (Map<String, Object> item : localHistory) known.add(String.valueOf(item.get("version")));
        List<Map<String, Object>> missing = new ArrayList<>();
        if (known.add(String.valueOf(bundled.get("version")))) missing.add(bundled);
        for (Map<String, Object> item : bundledHistory) if (known.add(String.valueOf(item.get("version")))) missing.add(item);
        if (missing.isEmpty()) return false;
        localHistory.addAll(missing);
        localHistory.sort((left, right) -> compareVersion(String.valueOf(right.get("version")), String.valueOf(left.get("version"))));
        local.put("history", localHistory);
        local.put("source", "data/release-history/local-release-notes.json");
        return true;
    }

    private int compareVersion(String left, String right) {
        if (!VERSION.matcher(left).matches() || !VERSION.matcher(right).matches()) return left.compareTo(right);
        String[] first = left.split("\\.");
        String[] second = right.split("\\.");
        for (int index = 0; index < 3; index++) {
            int comparison = Integer.compare(Integer.parseInt(first[index]), Integer.parseInt(second[index]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private Path file() {
        return props.data().resolve("release-history").resolve("local-release-notes.json");
    }

    private void initialize(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("缺少内置版本记录");
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void write(Path file, Map<String, Object> notes) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(notes) + "\n", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (file.equals(file())) {
                cachedLocal = notes;
                cachedLocalModifiedAt = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存本机版本记录，请检查 data 目录权限", e);
        }
    }

    private String nextPatch(String version) {
        String[] parts = version.split("\\.");
        if (parts.length != 3 || !VERSION.matcher(version).matches()) throw new IllegalStateException("当前本机版本格式无效：" + version);
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private String releaseId(String version) {
        return "release-" + version.replace('.', '-');
    }
}
