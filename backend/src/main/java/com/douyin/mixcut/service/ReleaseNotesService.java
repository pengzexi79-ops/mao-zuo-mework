package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the shipped, build-validated release history used by the environment center. */
@Service
@RequiredArgsConstructor
public class ReleaseNotesService {

    private static final String RESOURCE = "release-notes.json";
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");
    private static final Set<String> REQUIRED = Set.of(
            "id", "version", "kind", "releasedAt", "title", "summary",
            "changes", "fixes", "verification", "compatibility", "evidence");
    private static final Set<String> KINDS = Set.of(
            "当前本机构建", "交付构建", "正式发行", "阶段验收", "历史开发阶段",
            "数据库演进阶段", "旧版原型（已取代）", "正式技术栈重写");
    private final ObjectMapper objectMapper;
    private final AppProps props;
    private Map<String, Object> notes;

    @PostConstruct
    void load() {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> parsed = objectMapper.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
            validate(parsed, props.releaseVersion());
            notes = Map.copyOf(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("发行历史 release-notes.json 无法通过校验，拒绝启动未记录版本的构建", e);
        }
    }

    public Map<String, Object> get() {
        return notes;
    }

    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> notes, String appVersion) {
        requireRecord(notes, "current");
        String currentVersion = String.valueOf(notes.get("version"));
        if (!currentVersion.equals(appVersion)) {
            throw new IllegalArgumentException("发行历史当前版本必须与 app.version 一致");
        }
        Object historyValue = notes.get("history");
        if (!(historyValue instanceof List<?> history) || history.isEmpty()) {
            throw new IllegalArgumentException("发行历史必须至少包含一条历史记录");
        }
        Set<String> ids = new java.util.HashSet<>();
        Set<String> versions = new java.util.HashSet<>();
        addIdentity(notes, ids, versions, "current");
        LocalDate previousDate = releaseDate(notes, "current");
        int[] previousVersion = versionParts(currentVersion, "current");
        for (Object value : history) {
            if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("历史记录格式无效");
            Map<String, Object> record = (Map<String, Object>) raw;
            requireRecord(record, "history");
            addIdentity(record, ids, versions, "history");
            LocalDate recordDate = releaseDate(record, "history");
            int[] recordVersion = versionParts(String.valueOf(record.get("version")), "history");
            if (recordDate.isAfter(previousDate)
                    || (recordDate.equals(previousDate) && compareVersions(recordVersion, previousVersion) >= 0)) {
                throw new IllegalArgumentException("历史记录必须按日期和版本从最新到最早排列");
            }
            previousDate = recordDate;
            previousVersion = recordVersion;
        }
    }

    private static void addIdentity(Map<String, Object> record, Set<String> ids, Set<String> versions, String label) {
        String id = String.valueOf(record.get("id"));
        String version = String.valueOf(record.get("version"));
        if (!ids.add(id) || !versions.add(version)) {
            throw new IllegalArgumentException(label + " 的 ID 或版本与已有记录重复：" + id);
        }
        if (!id.equals("release-" + version.replace('.', '-'))) {
            throw new IllegalArgumentException(label + " 的 ID 必须与版本对应：" + id);
        }
    }

    private static void requireRecord(Map<String, Object> record, String label) {
        for (String field : REQUIRED) {
            Object value = record.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw new IllegalArgumentException(label + " 缺少字段：" + field);
            }
            if ((field.equals("changes") || field.equals("fixes") || field.equals("verification") || field.equals("evidence"))
                    && (!(value instanceof Collection<?> values) || values.isEmpty())) {
                throw new IllegalArgumentException(label + " 数组字段不能为空：" + field);
            }
        }
        if (!KINDS.contains(String.valueOf(record.get("kind")))) {
            throw new IllegalArgumentException(label + " 使用了不允许的记录类型：" + record.get("kind"));
        }
        String evidence = String.valueOf(record.get("evidence")).toLowerCase();
        String secretField = "pass" + "word=";
        String tokenField = "to" + "ken=";
        if (evidence.contains(secretField) || evidence.contains(tokenField) || evidence.contains(".env")) {
            throw new IllegalArgumentException(label + " 证据来源不能包含敏感配置内容");
        }
        versionParts(String.valueOf(record.get("version")), label);
    }

    private static LocalDate releaseDate(Map<String, Object> record, String label) {
        LocalDate result = LocalDate.parse(String.valueOf(record.get("releasedAt")));
        if (result.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(label + " 的发布日期不能晚于今天");
        }
        return result;
    }

    private static int[] versionParts(String version, String label) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(label + " 的版本必须是 x.y.z：" + version);
        }
        return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }

    private static int compareVersions(int[] left, int[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
