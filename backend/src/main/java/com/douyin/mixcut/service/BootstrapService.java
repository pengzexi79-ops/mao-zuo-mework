package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.domain.AppPlugin;
import com.douyin.mixcut.domain.SkillDef;
import com.douyin.mixcut.domain.SkillType;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.Workflow;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.security.CredentialCipher;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.PluginRepo;
import com.douyin.mixcut.repository.Repositories.SkillDefRepo;
import com.douyin.mixcut.repository.Repositories.WorkflowRepo;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 首次启动播种：目录、内置工作流、内置 skill。
 * 同时把环境自检结果打到日志上 —— 交付现场最常见的问题就是 ffmpeg 没装，
 * 与其让客户点了出片才报错，不如启动就喊出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapService implements ApplicationRunner {

    private final AppProps props;
    private final WorkflowRepo workflowRepo;
    private final ProjectRepo projectRepo;
    private final SkillDefRepo skillRepo;
    private final SkillEngine skillEngine;
    private final FfmpegTool ffmpeg;
    private final ProcRunner runner;
    private final CrawlerGateway crawler;
    private final JobService jobService;
    private final CrawlJobService crawlJobService;
    private final MaterialService materialService;
    private final AiProviderRepo providerRepo;
    private final PluginRepo pluginRepo;
    private final CredentialCipher credentialCipher;
    private final DataSource dataSource;
    private final CredentialRegistry credentialRegistry;
    private final ObjectMapper om = new ObjectMapper();
    /** 能力中心数据源：capabilities.json（版本化清单，驱动 capabilities 与 installCapability 的修复安装边界）。 */
    private final CapabilityManifest manifest = CapabilityManifest.load();
    /** API 自检读取缓存，避免数据库断开时每个浏览器轮询都阻塞 Hikari 的连接超时。 */
    private volatile boolean databaseReady;
    private static final Set<String> INSTALLING_CAPABILITIES = ConcurrentHashMap.newKeySet();
    private static final int INSTALL_LOG_LIMIT = 2400;
    private volatile Map<String, Object> environmentCache = Map.of();
    private volatile long environmentCacheAt;

    @Override
    public void run(ApplicationArguments args) {
        props.materials();
        props.output();
        props.cache();
        props.slices();
        props.thumbs();
        props.downloads();

        databaseReady = probeDatabase();
        if (databaseReady) {
            migrateOutputSegmentKeys();
            migrateOutputDeliveryQc();
            migrateMaterialFlags();
            migrateProjectTemplates();
            migrateMaterialFolderHierarchy();
            migrateOutputRepairLifecycle();
            migrateOutputEditSessions();
            migratePreparationTasks();
            migrateMediaTasks();
            migrateCrawlTaskDiagnostics();
            migrateMaterialTranscripts();
            migrateMaterialAnalysis();
            migrateMaterialAnalysisIndexMetadata();
            migrateMaterialSegments();
            migrateMaterialAnalysisFrameFields();
            migrateNarrationCaptions();
            migrateEditorialBriefs();
            migrateEditorialBriefHookStrategy();
            migratePlugins();
            migrateProviderCredentials();
            seedWorkflows();
            seedProjects();
            seedSkills();
        } else {
            log.warn("数据库不可用：跳过内置数据播种、任务恢复与凭据迁移；请通过环境中心完成 MySQL 配置后重启应用");
        }

        Map<String, Object> env = env();
        log.info("=========== 环境自检 ===========");
        env.forEach((k, v) -> log.info("  {} : {}", k, v));
        if (Boolean.FALSE.equals(env.get("ffmpeg"))) {
            log.error("  !! 未检测到 ffmpeg，无法出片。请安装后加入 PATH，或在 application.yml 配置绝对路径");
        }
        log.info("================================");
        log.info("能力清单: manifest v{} (schema {})，共 {} 项能力定义", manifest.manifestVersion(), manifest.schemaVersion(), manifest.size());
        log.info("控制台: http://{}:{}/", props.getBindAddress(), System.getProperty("local.server.port", "8760"));
    }

    /** Upgrades a pre-segment-key installation without dynamic SQL or user-controlled identifiers. */
    private void migrateOutputSegmentKeys() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        String alterSql = "ALTER TABLE job_output ADD COLUMN segment_keys TEXT";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql)) {
            exists.setString(1, "job_output");
            exists.setString(2, "segment_keys");
            try (ResultSet result = exists.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) return;
            }
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
                alter.execute();
                log.info("已补齐 job_output.segment_keys，用于恢复出片去重状态");
            }
        } catch (Exception e) {
            log.warn("无法补齐 job_output.segment_keys；成片库可能暂不可用", e);
        }
    }

    /** 为 job_output 补齐可解释交付质检相关字段（幂等，使用参数绑定，不拼接用户输入）。 */
    private void migrateOutputDeliveryQc() {
        addJobOutputColumnIfMissing("retry_count", "ALTER TABLE job_output ADD COLUMN retry_count INT NOT NULL DEFAULT 0");
        addJobOutputColumnIfMissing("hook_strategy", "ALTER TABLE job_output ADD COLUMN hook_strategy VARCHAR(32)");
        addJobOutputColumnIfMissing("downgrade_info", "ALTER TABLE job_output ADD COLUMN downgrade_info TEXT");
        addJobOutputColumnIfMissing("used_materials", "ALTER TABLE job_output ADD COLUMN used_materials TEXT");
        addJobOutputColumnIfMissing("qc_json", "ALTER TABLE job_output ADD COLUMN qc_json TEXT");
    }

    private void addJobOutputColumnIfMissing(String column, String alterSql) {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql)) {
            exists.setString(1, "job_output");
            exists.setString(2, column);
            try (ResultSet result = exists.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) return;
            }
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
                alter.execute();
                log.info("已补齐 job_output.{}", column);
            }
        } catch (Exception e) {
            log.warn("无法补齐 job_output.{}；成片库质检信息可能暂不可用", column, e);
        }
    }

    /** Add fixed, user-consent material columns on upgraded local databases. */
    private void migrateMaterialFlags() {
        migrateMuteOriginalAudio();
        migrateTranscribeForSubtitles();
    }

    /** 总包素材库的父子分类字段，兼容已经存在的旧 material_folder 表。 */
    private void migrateMaterialFolderHierarchy() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_folder' AND column_name = 'parent_id'";
        String alterSql = "ALTER TABLE material_folder ADD COLUMN parent_id BIGINT NULL, ADD INDEX idx_material_folder_parent (parent_id)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
                alter.execute();
                log.info("已补齐 material_folder.parent_id，总包素材库支持音频/视频子分类");
            }
        } catch (Exception e) {
            log.warn("无法补齐 material_folder.parent_id；总包素材库层级可能不可用", e);
        }
    }

    private void migrateMuteOriginalAudio() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material' AND column_name = 'mute_original_audio'";
        String alterSql = "ALTER TABLE material ADD COLUMN mute_original_audio TINYINT(1) NOT NULL DEFAULT 0";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) { alter.execute(); }
            log.info("已补齐 material.mute_original_audio 用户授权字段");
        } catch (Exception e) {
            log.warn("无法补齐 material.mute_original_audio", e);
        }
    }

    private void migrateTranscribeForSubtitles() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material' AND column_name = 'transcribe_for_subtitles'";
        String alterSql = "ALTER TABLE material ADD COLUMN transcribe_for_subtitles TINYINT(1) NOT NULL DEFAULT 0";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) { alter.execute(); }
            log.info("已补齐 material.transcribe_for_subtitles 用户授权字段");
        } catch (Exception e) {
            log.warn("无法补齐 material.transcribe_for_subtitles", e);
        }
    }

    /** Adds the read-only marker used by the built-in project template store. */
    private void migrateProjectTemplates() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'project' AND column_name = 'is_builtin'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) == 0) {
                try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE project ADD COLUMN is_builtin TINYINT(1) NOT NULL DEFAULT 0")) {
                    alter.execute();
                }
            }
            List<String> templateNames = List.of(
                    "美妆测评模板", "食品种草模板", "3C 数码开箱模板", "护肤/美白模板", "母婴/育儿模板",
                    "宠物好物模板", "健身/运动模板", "家居好物模板", "时尚穿搭模板", "旅行攻略模板",
                    "数码配件模板", "家电评测模板", "汽车/车品模板", "健康/养生模板", "教育/知识模板",
                    "剧情短剧模板", "探店/本地模板", "礼盒/节庆模板", "日用清洁模板", "农产品/生鲜模板");
            try (PreparedStatement mark = connection.prepareStatement("UPDATE project SET is_builtin = 1 WHERE name = ?")) {
                for (String name : templateNames) {
                    mark.setString(1, name);
                    mark.addBatch();
                }
                mark.executeBatch();
            }
        } catch (Exception e) {
            log.warn("无法补齐 project.is_builtin 项目模板标记", e);
        }
    }

    /** 执行随发行包提供的固定迁移脚本；不接收或拼接任何用户输入。 */
    private void migrateOutputRepairLifecycle() {
        try (Connection connection = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/output-repair-lifecycle-migration.sql"));
            populator.setContinueOnError(false);
            populator.populate(connection);
            ensureOutputVersionUniqueness(connection);
            log.info("已确认输出版本与修复记录表可用");
        } catch (Exception e) {
            log.warn("无法初始化输出版本/修复记录表；自动修复历史可能不可用", e);
        }
    }

    /** Adds durable, non-sensitive crawl failure diagnostics to installations created before this release. */
    private void migrateCrawlTaskDiagnostics() {
        try (Connection connection = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/crawl-task-diagnostics-migration.sql"));
            populator.setContinueOnError(false);
            populator.populate(connection);
            log.info("已确认采集任务诊断字段可用");
        } catch (Exception e) {
            log.warn("无法初始化采集任务诊断字段；旧任务仍可读取，但新抓取错误详情可能不可保存", e);
        }
    }

    /** Runs the fixed, packaged edit-session migration without accepting dynamic SQL input. */
    private void migrateOutputEditSessions() {
        try (Connection connection = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/output-edit-session-migration.sql"));
            populator.setContinueOnError(false);
            populator.populate(connection);
            log.info("已确认可编辑出片会话表可用");
        } catch (Exception e) {
            log.warn("无法初始化可编辑出片会话表；编辑工作台暂不可用", e);
        }
    }

    /** Creates the persistent, pollable material-preparation task table before any UI can enqueue work. */
    private void migratePreparationTasks() {
        try (Connection connection = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/preparation-task-migration.sql"));
            populator.setContinueOnError(false);
            populator.populate(connection);
            log.info("已确认出片准备任务表可用");
        } catch (Exception e) {
            log.warn("无法初始化出片准备任务表；自动补齐将等待数据库恢复后再试", e);
        }
    }

    /** Creates the persistent media-tool task table before local tool requests are accepted. */
    private void migrateMediaTasks() {
        try (Connection connection = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/media-task-migration.sql"));
            populator.setContinueOnError(false);
            populator.populate(connection);
            log.info("已确认媒体工具任务表可用");
        } catch (Exception e) {
            log.warn("无法初始化媒体工具任务表；媒体任务将在数据库恢复后重试", e);
        }
    }

    /**
     * Adds the version number uniqueness gate to installations created before the repair lifecycle.
     * Existing duplicate rows are preserved as audit evidence and surfaced in logs rather than being
     * silently deleted or renumbered during startup.
     */
    private void ensureOutputVersionUniqueness(Connection connection) {
        String duplicatesSql = "SELECT COUNT(*) FROM (SELECT job_id, idx, version_no FROM output_version GROUP BY job_id, idx, version_no HAVING COUNT(*) > 1) duplicates";
        String indexSql = "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'output_version' AND index_name = 'uniq_output_version_job_idx_no'";
        try (PreparedStatement duplicates = connection.prepareStatement(duplicatesSql);
             ResultSet duplicateRows = duplicates.executeQuery()) {
            if (!duplicateRows.next()) {
                log.warn("无法读取 output_version 历史版本号状态；跳过唯一约束升级");
                return;
            }
            int count = duplicateRows.getInt(1);
            if (count > 0) {
                log.warn("output_version 存在 {} 组历史重复版本号；为保留审计证据未自动修改，新的修复版本将由队列串行生成", count);
                return;
            }
            try (PreparedStatement index = connection.prepareStatement(indexSql);
                 ResultSet indexRows = index.executeQuery()) {
                if (indexRows.next() && indexRows.getInt(1) > 0) return;
            }
            try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE output_version ADD UNIQUE KEY uniq_output_version_job_idx_no (job_id, idx, version_no)")) {
                alter.execute();
                log.info("已补齐 output_version 版本号唯一约束");
            }
        } catch (Exception e) {
            log.warn("无法检查或补齐 output_version 版本号唯一约束", e);
        }
    }

    private void migrateMaterialTranscripts() {
        String createSql = "CREATE TABLE IF NOT EXISTS material_transcript (id BIGINT AUTO_INCREMENT PRIMARY KEY, material_id BIGINT NOT NULL, language VARCHAR(32) NOT NULL DEFAULT 'zh', model VARCHAR(128), cues JSON, status VARCHAR(32) NOT NULL DEFAULT 'pending', error TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_material_transcript (material_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 material_transcript；转写缓存功能暂不可用", e);
        }
    }

    /** 结构化素材分析结果表（幂等创建）。 */
    private void migrateMaterialAnalysis() {
        String createSql = "CREATE TABLE IF NOT EXISTS material_analysis (id BIGINT AUTO_INCREMENT PRIMARY KEY, material_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'pending', source VARCHAR(32), tags_json JSON, ocr_texts_json JSON, transcript_status VARCHAR(32), summary TEXT, issues_json JSON, error TEXT, source_fingerprint VARCHAR(128), index_version VARCHAR(64), attempt_count INT NOT NULL DEFAULT 0, indexed_at DATETIME NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_material_analysis (material_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 material_analysis；素材分析功能暂不可用", e);
        }
    }

    /** Adds audit fields used to reuse unchanged indexes and force safe rebuilds. */
    private void migrateMaterialAnalysisIndexMetadata() {
        addMaterialAnalysisColumnIfMissing("source_fingerprint", "ALTER TABLE material_analysis ADD COLUMN source_fingerprint VARCHAR(128)");
        addMaterialAnalysisColumnIfMissing("index_version", "ALTER TABLE material_analysis ADD COLUMN index_version VARCHAR(64)");
        addMaterialAnalysisColumnIfMissing("attempt_count", "ALTER TABLE material_analysis ADD COLUMN attempt_count INT NOT NULL DEFAULT 0");
        addMaterialAnalysisColumnIfMissing("indexed_at", "ALTER TABLE material_analysis ADD COLUMN indexed_at DATETIME NULL");
    }

    /** Adds fixed frame metadata columns without accepting dynamic SQL input. */
    private void migrateMaterialAnalysisFrameFields() {
        addSampleFramesColumn();
        addRepresentativeFrameTimeColumn();
        addRepresentativeFrameUrlColumn();
    }

    private void addSampleFramesColumn() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_analysis' AND column_name = 'sample_frames_json'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE material_analysis ADD COLUMN sample_frames_json JSON")) { alter.execute(); }
        } catch (Exception e) { log.warn("无法补齐 material_analysis.sample_frames_json", e); }
    }

    private void addRepresentativeFrameTimeColumn() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_segment' AND column_name = 'representative_frame_at_sec'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE material_segment ADD COLUMN representative_frame_at_sec DOUBLE")) { alter.execute(); }
        } catch (Exception e) { log.warn("无法补齐 material_segment.representative_frame_at_sec", e); }
    }

    private void addRepresentativeFrameUrlColumn() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_segment' AND column_name = 'representative_frame_url'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE material_segment ADD COLUMN representative_frame_url VARCHAR(1024)")) { alter.execute(); }
        } catch (Exception e) { log.warn("无法补齐 material_segment.representative_frame_url", e); }
    }

    private void addMaterialAnalysisColumnIfMissing(String column, String alterSql) {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_analysis' AND column_name = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement exists = connection.prepareStatement(existsSql)) {
            exists.setString(1, column);
            try (ResultSet result = exists.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) return;
            }
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) { alter.execute(); }
            log.info("已补齐 material_analysis.{}", column);
        } catch (Exception e) {
            log.warn("无法补齐 material_analysis.{}；可重复索引审计信息可能不可用", column, e);
        }
    }

    /** 素材分析镜头片段表（幂等创建）。 */
    private void migrateMaterialSegments() {
        String createSql = "CREATE TABLE IF NOT EXISTS material_segment (id BIGINT AUTO_INCREMENT PRIMARY KEY, material_id BIGINT NOT NULL, analysis_id BIGINT, idx INT NOT NULL DEFAULT 0, start_sec DOUBLE, end_sec DOUBLE, duration_sec DOUBLE, score DOUBLE, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, INDEX idx_material_segment_material (material_id), INDEX idx_material_segment_analysis (analysis_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 material_segment；素材片段持久化功能暂不可用", e);
        }
    }

    /** 任务级 AI 配音字幕表（幂等创建）。 */
    private void migrateNarrationCaptions() {
        String createSql = "CREATE TABLE IF NOT EXISTS narration_caption (id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT, idx INT NOT NULL DEFAULT 0, voice_material_id BIGINT, script_text TEXT, cues JSON, status VARCHAR(32) NOT NULL DEFAULT 'pending', error TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_narration_caption_job (job_id), UNIQUE KEY uniq_narration_caption (job_id, idx)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 narration_caption；AI 配音字幕功能暂不可用", e);
        }
    }

    /** 任务级编辑意图表（幂等创建）。 */
    private void migrateEditorialBriefs() {
        String createSql = "CREATE TABLE IF NOT EXISTS editorial_brief (id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT, project_id BIGINT, mood_keywords JSON, hook_strategy VARCHAR(32), prefer_human_voice TINYINT(1) NOT NULL DEFAULT 1, duck_bgm TINYINT(1) NOT NULL DEFAULT 1, bgm_material_id BIGINT, summary TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY uniq_editorial_brief_job (job_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 editorial_brief；内容驱动音频选择功能暂不可用", e);
        }
    }

    /** 为旧安装补齐 editorial_brief.hook_strategy，避免覆盖已有任务级编辑意图。 */
    private void migrateEditorialBriefHookStrategy() {
        String existsSql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'editorial_brief' AND column_name = 'hook_strategy'";
        String alterSql = "ALTER TABLE editorial_brief ADD COLUMN hook_strategy VARCHAR(32)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement exists = connection.prepareStatement(existsSql);
             ResultSet result = exists.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) return;
            try (PreparedStatement alter = connection.prepareStatement(alterSql)) { alter.execute(); }
            log.info("已补齐 editorial_brief.hook_strategy 钩子策略字段");
        } catch (Exception e) {
            log.warn("无法补齐 editorial_brief.hook_strategy", e);
        }
    }

    /** 本地插件注册表（幂等创建）。 */
    private void migratePlugins() {
        String createSql = "CREATE TABLE IF NOT EXISTS app_plugin (id BIGINT AUTO_INCREMENT PRIMARY KEY, plugin_key VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(255), category VARCHAR(128), description TEXT, entry_url TEXT, priority INT NOT NULL DEFAULT 100, enabled TINYINT(1) NOT NULL DEFAULT 1, manifest JSON, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection(); PreparedStatement create = connection.prepareStatement(createSql)) {
            create.execute();
        } catch (Exception e) {
            log.warn("无法创建 app_plugin；用户插件注册功能暂不可用", e);
        }
    }

    private void migrateProviderCredentials() {
        if (!credentialCipher.available()) {
            log.warn("APP_MASTER_KEY 未配置：现有 AI 凭据保持原样，新密钥保存已禁用");
            return;
        }
        int migrated = 0;
        for (AiProvider provider : providerRepo.findAll()) {
            String key = provider.getApiKey();
            if (key != null && !key.isBlank() && !credentialCipher.encrypted(key)) {
                provider.setApiKey(credentialCipher.encrypt(key));
                providerRepo.save(provider);
                migrated++;
            }
        }
        if (migrated > 0) log.info("已加密迁移 {} 个 AI Provider 凭据", migrated);
    }

    /**
     * ApplicationRunner 完成目录/内置数据初始化后再恢复任务，避免恢复线程先于应用完全就绪运行。
     * JobService 内部以 dispatched 集合去重，因此重复 ApplicationReadyEvent 或手动调用均安全。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverJobsAfterStartup() {
        if (!databaseConnected()) {
            log.warn("数据库不可用：跳过任务恢复，完成 MySQL 配置并重启应用后会自动恢复");
            return;
        }
        jobService.recoverInterruptedJobs();
        crawlJobService.recoverPending();
        int resumedMedia = materialService.recoverProcessingUploads();
        if (resumedMedia > 0) log.info("resumed {} interrupted media probes", resumedMedia);
    }

    public Map<String, Object> env() {
        return env(false);
    }

    public Map<String, Object> env(boolean forceRefresh) {
        if (forceRefresh) invalidateEnvironmentCache();
        long now = System.currentTimeMillis();
        Map<String, Object> cached = environmentCache;
        if (!cached.isEmpty() && now - environmentCacheAt < 300_000) {
            Map<String, Object> copy = new LinkedHashMap<>(cached);
            copy.put("checkedAt", java.time.OffsetDateTime.now().toString());
            copy.put("databaseConnected", databaseConnected());
            return copy;
        }
        return refreshEnvironment(now);
    }

    private synchronized Map<String, Object> refreshEnvironment(long now) {
        if (!environmentCache.isEmpty() && now - environmentCacheAt < 300_000) return env();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", props.releaseVersion());
        m.put("checkedAt", java.time.OffsetDateTime.now().toString());
        m.put("credentialProtection", credentialCipher.available() ? "ready" : "missing_master_key");
        m.put("ffmpeg", ffmpeg.ffmpegAvailable());
        m.put("ffmpegPath", props.getFfmpeg());
        m.put("ffprobe", ffmpeg.ffprobeAvailable());
        m.put("ffprobePath", props.getFfprobe());
        m.put("yt-dlp", crawler.ytdlpAvailable());
        m.put("you-get", crawler.yougetAvailable());
        m.put("freesoundKey", props.getFreesoundApiKey() != null && !props.getFreesoundApiKey().isBlank());
        m.put("pixabayKey", props.getPixabayApiKey() != null && !props.getPixabayApiKey().isBlank());
        m.put("pexelsKey", props.getPexelsApiKey() != null && !props.getPexelsApiKey().isBlank());
        m.put("allowLoginCrawl", props.isAllowLoginCrawl());
        m.put("backend", "127.0.0.1:" + System.getProperty("local.server.port", "8760"));
        m.put("databaseConnected", databaseConnected());
        m.put("outputDir", props.output().toString());
        m.put("materialsDir", props.materials().toString());
        m.put("os", System.getProperty("os.name", "未知系统"));
        m.put("arch", System.getProperty("os.arch", "未知架构"));
        m.put("javaVersion", System.getProperty("java.version", "未知版本"));
        String localPython = props.localPythonPath();
        boolean localPythonReady = executableAvailable(localPython, "--version");
        m.put("localPython", localPython);
        m.put("localPythonReady", localPythonReady);
        String portablePython = props.portableTool("python/python.exe");
        m.put("portablePython", portablePython == null ? "" : portablePython);
        m.put("portablePythonReady", portablePython != null);
        m.put("offlineWhisperModels", toolState(offlineWhisperModelsReady(), true));
        boolean whisperCppInstalled = executableAvailable(firstTool(props.portableTool("whisper/Release/whisper-cli.exe"), "whisper-cli"), "--help");
        m.put("whisperCpp", toolState(whisperCppInstalled, whisperCppInstalled && whisperCppRuntimeReady()));
        m.put("fasterWhisper", toolState(localPythonReady && pythonModuleAvailable("faster_whisper"), true));
        m.put("rapidOcr", toolState(localPythonReady && pythonModuleAvailable("rapidocr_onnxruntime"), true));
        m.put("neuralTts", toolState(localPythonReady && pythonModuleAvailable("edge_tts"), true));
        m.put("chatTts", toolState(localPythonReady && pythonModuleAvailable("ChatTTS"), true));
        m.put("ffmpegNormalize", toolState(localPythonReady && pythonModuleAvailable("ffmpeg_normalize"), true));
        m.put("demucs", toolState(localPythonReady && pythonModuleAvailable("demucs"), true));
        m.put("galleryDl", toolState(localPythonReady && pythonModuleAvailable("gallery_dl"), true));
        boolean imageMagickReady = executableAvailable(firstTool(props.portableTool("imagemagick/magick.exe"), "magick"), "-version");
        m.put("imageMagick", toolState(imageMagickReady, true));
        m.put("openCv", toolState(localPythonReady && pythonModuleAvailable("cv2"), true));
        m.put("autoEditor", toolState(localPythonReady && pythonModuleAvailable("auto_editor"), true));
        m.put("rembg", toolState(localPythonReady && pythonModuleAvailable("rembg"), true));
        m.put("environmentGuide", environmentGuide());
        environmentCache = Map.copyOf(m);
        environmentCacheAt = now;
        return m;
    }

    /** 能力中心：后端是检测状态、安装边界和官方入口的唯一来源。 */
    public List<Map<String, Object>> capabilities() {
        return capabilities(false);
    }

    public List<Map<String, Object>> capabilities(boolean forceRefresh) {
        if (forceRefresh) invalidateEnvironmentCache();
        Map<String, Object> env = env();
        List<Map<String, Object>> caps = new ArrayList<>();
        for (CapabilityManifest.Entry entry : manifest.entries()) {
            if (entry.isExternal()) {
                caps.add(externalCapability(entry));
            } else {
                addCapability(caps, env, entry);
            }
        }
        return caps;
    }

    /** 尝试自动安装缺失能力（可联网场景）。目前支持 venv pip 可装的能力（如 demucs）。
     *  安装不可靠时返回明确引导信息，由前端展示给用户，绝不静默失败。 */
    public Map<String, Object> installCapability(String key) {
        String python = props.localPythonPath();
        Map<String, Object> out = new LinkedHashMap<>();
        if (key == null || key.isBlank()) {
            out.put("ok", false);
            out.put("message", "缺少能力标识");
            return out;
        }
        if ("chattts".equals(key)) {
            out.put("ok", false);
            out.put("guide", true);
            out.put("message", "ChatTTS 需要兼容的 Python、PyTorch 和 Rust 构建环境，当前内置 Python 3.13 不支持一键安装。");
            out.put("nextAction", "请按官方说明使用 Python 3.10 独立环境安装，并为首次模型下载预留网络和磁盘空间。基础 Edge-TTS 配音不受影响。");
            return out;
        }
        CapabilityManifest.Entry entry = manifest.byKey(key);
        String display = key;
        if (entry != null && entry.installDisplay() != null && !entry.installDisplay().isBlank()) {
            display = entry.installDisplay();
        }
        String pipSpec = entry == null ? null : manifest.approvedSpec(entry.pipRef());
        if (pipSpec == null) {
            out.put("ok", false);
            out.put("message", display + " 无法自动安装（需从官方发布页下载便携版）：请打开「环境中心」查看官方链接并按其步骤操作。");
            out.put("guide", true);
            return out;
        }
        if (!INSTALLING_CAPABILITIES.add(key)) {
            out.put("ok", false);
            out.put("guide", true);
            out.put("message", display + " 正在安装，请等待当前安装完成后重新检测。");
            return out;
        }
        try {
            // pipSpec 只来自清单 approvedPip 中固定的 name==version（如 demucs==4.1.0）。
            // 浏览器输入永远不会成为命令行参数，安装目标不存在"任意包名"路径。
            ProcRunner.Result result = runner.run(List.of(python, "-m", "pip", "install", "--disable-pip-version-check", "--no-input", pipSpec), 300);
            invalidateEnvironmentCache();
            if (!result.ok()) {
                out.put("ok", false);
                out.put("guide", true);
                out.put("message", result.code() == -2
                        ? display + " 安装超时，未确认安装完成。请检查网络后重试或打开官方页面。"
                        : display + " 自动修复失败。请检查网络后重试或打开官方页面。");
                out.put("detail", truncateInstallLog(result.out()));
                return out;
            }
            boolean ready = capabilities(true).stream()
                    .anyMatch(capability -> key.equals(capability.get("key")) && "ready".equals(capability.get("status")));
            out.put("ok", ready);
            out.put("message", ready ? display + " 已安装可用。" : display + " 已完成安装，但重新检测未通过。");
            if (!ready) {
                out.put("guide", true);
                out.put("detail", truncateInstallLog(result.out()));
            }
            return out;
        } finally {
            INSTALLING_CAPABILITIES.remove(key);
            invalidateEnvironmentCache();
        }
    }
    private void addCapability(List<Map<String, Object>> caps, Map<String, Object> env, CapabilityManifest.Entry entry) {
        Object raw = env.get(entry.envKey());
        boolean ready;
        if (raw instanceof Map<?, ?> map) {
            ready = Boolean.TRUE.equals(map.get("installed"));
        } else {
            ready = Boolean.TRUE.equals(raw);
        }
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("key", entry.key());
        cap.put("group", entry.group());
        cap.put("name", entry.name());
        cap.put("tool", entry.tool());
        boolean wired = entry.wired();
        boolean fallback = "magick".equals(entry.key()) || "asr-local".equals(entry.key()) || "chattts".equals(entry.key());
        boolean runtimeReady = ready && (raw instanceof Map<?, ?> map ? Boolean.TRUE.equals(map.get("integrated")) : wired);
        cap.put("installed", ready);
        cap.put("runtimeReady", runtimeReady);
        cap.put("wired", wired);
        cap.put("fallback", fallback);
        cap.put("activationRequired", false);
        cap.put("usedBy", entry.usedBy());
        cap.put("executionPolicy", entry.executionPolicy());
        cap.put("offlineCapable", entry.offlineCapable());
        cap.put("offlineReady", runtimeReady && entry.offlineCapable());
        cap.put("fallbackKeys", entry.fallbackKeys());
        cap.put("configVariables", entry.configVariables());
        cap.put("verifySteps", entry.verifySteps());
        cap.put("restartRequired", entry.restartRequired());
        boolean repairable = entry.repairable();
        cap.put("status", runtimeReady ? "ready" : ready ? "detected_only" : "missing");
        cap.put("needsNetwork", entry.needsNetwork());
        cap.put("installMode", ready ? "bundled" : repairable ? "repairable" : "bundled");
        cap.put("action", ready ? "none" : repairable ? "install" : "official");
        cap.put("actionLabel", ready ? "已安装可用" : repairable ? "修复安装" : "查看安装说明");
        cap.put("officialUrl", entry.officialUrl());
        String guide;
        if (ready && !runtimeReady && fallback) {
            guide = "主工具已随包安装，但当前条件未满足；应用会自动使用已接入的备用链，不需要手工接线。";
        } else if (ready) {
            guide = "已安装并接入默认链路，无需操作。";
        } else if (repairable) {
            guide = "该能力应随安装包预置。缺失时可联网修复安装；若失败，请重新运行安装器。";
        } else {
            guide = "该能力应随安装包预置。缺失时请重新运行安装器的运行环境检查，不会在页面中伪装为可用。";
        }
        cap.put("guide", guide);
        caps.add(cap);
    }

    private Map<String, Object> externalCapability(CapabilityManifest.Entry entry) {
        Map<String, Object> cap = new LinkedHashMap<>();
        boolean authorization = "authorization".equals(entry.installMode());
        CredentialRegistry.Credential credential = credentialRegistry.byCapabilityKey(entry.key()).orElse(null);
        boolean configured = authorization && credential != null && credentialRegistry.configured(credential, props);
        cap.put("key", entry.key());
        cap.put("group", entry.group());
        cap.put("name", entry.name());
        cap.put("tool", entry.tool());
        cap.put("executionPolicy", entry.executionPolicy());
        cap.put("offlineCapable", entry.offlineCapable());
        cap.put("offlineReady", false);
        cap.put("fallbackKeys", entry.fallbackKeys());
        cap.put("configVariables", entry.configVariables());
        cap.put("verifySteps", entry.verifySteps());
        cap.put("restartRequired", entry.restartRequired());
        cap.put("status", configured ? "ready" : "external");
        cap.put("installed", configured);
        cap.put("runtimeReady", configured);
        cap.put("wired", configured);
        cap.put("fallback", false);
        cap.put("activationRequired", !configured);
        cap.put("configured", configured);
        cap.put("needsNetwork", true);
        cap.put("installMode", entry.installMode());
        cap.put("action", authorization ? "configure" : "official");
        cap.put("actionLabel", configured ? "已配置，可测试" : authorization ? "配置 API Key" : "打开官方说明");
        cap.put("officialUrl", entry.officialUrl());
        cap.put("pipelineStages", configured ? "素材检索、自动补齐" : "完成官方授权后可用于素材检索");
        if (credential != null) cap.put("credential", credentialRegistry.metadata(credential));
        cap.put("guide", configured
                ? "已读取本机服务端配置，可在能力中心测试连接；素材使用仍受来源许可、配额和去重准入限制。"
                : entry.guide());
        return cap;
    }

    private void invalidateEnvironmentCache() {
        environmentCache = Map.of();
        environmentCacheAt = 0;
    }
    private List<Map<String, Object>> environmentGuide() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(envGuide("Java 17+", "后端运行", "java -version", "安装 JDK 17+ 并配置 JAVA_HOME", "https://adoptium.net/temurin/releases/", "必须"));
        rows.add(envGuide("MySQL 8+", "项目数据、任务和成片记录", "DB_URL / DB_USERNAME / DB_PASSWORD", "启动 MySQL 后在项目 .env 配置三项连接信息", "https://dev.mysql.com/downloads/installer/", "必须"));
        rows.add(envGuide("FFmpeg + FFprobe", "视频渲染、媒体探测和预览", "APP_FFMPEG / APP_FFPROBE", "安装后加入 PATH，或在 .env 填写两个可执行文件的绝对路径", "https://ffmpeg.org/download.html", "必须"));
        rows.add(envGuide("yt-dlp", "公开网页视频解析与下载", "APP_YTDLP_PATH", "Windows 可用 winget install yt-dlp.yt-dlp；完成后执行 yt-dlp --version", "https://github.com/yt-dlp/yt-dlp/releases", "抓取视频时需要"));
        rows.add(envGuide("本机媒体运行时", "应用专用 Python、缓存、临时文件和默认媒体依赖", "APP_LOCAL_PYTHON", "启动时使用 backend/.venv；缺失或损坏时查看 data/logs/dependency-bootstrap.log，并在能力中心明确执行受控修复", "", "基础能力"));
        rows.add(envGuide("faster-whisper / whisper.cpp", "本地语音转写和自动字幕；whisper.cpp 就绪时优先，失败回退 faster-whisper", "APP_LOCAL_PYTHON / HF_HOME", "发行包预置 Python 依赖和模型缓存；额外语言模型按官方说明下载到应用模型目录", "https://github.com/SYSTRAN/faster-whisper", "基础能力"));
        rows.add(envGuide("RapidOCR / OpenCV", "画面文字识别、模糊/暗帧/过曝和质量诊断", "APP_LOCAL_PYTHON", "发行包预置模块；手动环境按 requirements-windows.txt 固定版本安装，不要从浏览器传入脚本或路径", "https://github.com/RapidAI/RapidOCR", "基础能力"));
        rows.add(envGuide("Edge-TTS", "神经配音；实际生成依赖网络语音服务", "APP_LOCAL_PYTHON", "模块可随包预置；使用 AI 配音前确认网络可用，网络不可用时改用本地音频或明确回退", "https://github.com/rany2/edge-tts", "需要网络"));
        rows.add(envGuide("媒体增强工具", "Demucs 人声分离、Rembg 抠图、Auto-Editor 智能剪辑、ImageMagick 图片处理", "APP_LOCAL_PYTHON / APP_IMAGEMAGICK_PATH", "安装器优先提供固定版本；缺失时只在能力中心使用受控修复或按官方说明安装", "", "按需"));
        rows.add(envGuide("gallery-dl", "图片和图集批量抓取", "APP_LOCAL_PYTHON", "工具可随包预置；实际抓取需要网络、合法公开来源和来源许可", "https://github.com/mikf/gallery-dl", "抓取时需要网络"));
        rows.add(envGuide("C++ 编译工具", "仅用于从源码编译 whisper.cpp / OpenCV 等工具，发行包无需", "PATH", "Windows 可安装 Visual Studio Build Tools，勾选 Desktop development with C++", "https://visualstudio.microsoft.com/visual-cpp-build-tools/", "可选"));
        rows.add(envGuide("APP_MASTER_KEY", "加密保存 AI Provider 密钥", "APP_MASTER_KEY", "在项目 .env 设置随机长字符串后重启后端；不要写入前端或提交仓库", "", "安全必配"));
        rows.add(envGuide("API Key / 登录授权", "Freesound 等来源的官方接口权限", "APP_FREESOUND_API_KEY / APP_ALLOW_LOGIN_CRAWL", "仅从官方页面申请；登录来源需自行确认授权，应用不读取 Cookie、密码或绕过登录", "https://freesound.org/apiv2/apply/", "按需"));
        return rows;
    }

    private Map<String, Object> envGuide(String name, String purpose, String variable, String setup, String url, String requirement) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("purpose", purpose);
        row.put("variable", variable);
        row.put("setup", setup);
        row.put("url", url);
        row.put("requirement", requirement);
        row.put("installSteps", List.of(setup));
        row.put("configureSteps", guideConfigureSteps(name, variable));
        row.put("verifySteps", guideVerifySteps(name));
        row.put("networkRequired", guideNeedsNetwork(name));
        row.put("offlineCapable", !guideNeedsNetwork(name));
        row.put("restartRequired", Set.of("MySQL 8+", "FFmpeg + FFprobe", "本机媒体运行时", "APP_MASTER_KEY", "API Key / 登录授权").contains(name));
        return row;
    }

    private List<String> guideConfigureSteps(String name, String variable) {
        if (variable == null || variable.isBlank()) return List.of();
        return switch (name) {
            case "MySQL 8+" -> List.of("在项目 .env 填写 DB_URL、DB_USERNAME、DB_PASSWORD", "确认数据库用户只拥有项目所需权限");
            case "FFmpeg + FFprobe" -> List.of("优先使用安装包 portable 中的工具", "手动环境仅在 .env 填 APP_FFMPEG 和 APP_FFPROBE 的绝对路径");
            case "yt-dlp" -> List.of("使用 APP_YTDLP_PATH 指向受信任的工具", "只抓取已授权、免登录的公开 URL");
            case "本机媒体运行时" -> List.of("保持 APP_LOCAL_PYTHON 指向应用专用 venv", "缺失组件时在能力中心明确选择受控修复，不接受任意 pip 包名");
            case "APP_MASTER_KEY" -> List.of("在项目 .env 设置随机长字符串", "重启后端后再保存或更新 Provider 密钥");
            case "API Key / 登录授权" -> List.of("仅在官方页面申请自己的 API Key", "密钥只保存在本机服务端 .env，不读取 Cookie、密码或验证码");
            default -> List.of("确认配置项：" + variable);
        };
    }

    private List<String> guideVerifySteps(String name) {
        return switch (name) {
            case "Java 17+" -> List.of("运行 java -version", "重新检测环境中心并确认后端可启动");
            case "MySQL 8+" -> List.of("启动 MySQL", "重新检测环境中心并确认“后端与数据库”为已连接");
            case "FFmpeg + FFprobe" -> List.of("运行 ffmpeg -version 和 ffprobe -version", "重新检测环境中心并确认渲染运行时已就绪");
            case "yt-dlp" -> List.of("运行 yt-dlp --version", "仅用已授权公开素材执行一次抓取预检");
            case "本机媒体运行时" -> List.of("重新检测 ASR、OCR、配音和响度能力", "查看 data/logs/dependency-bootstrap.log 中的失败原因");
            case "媒体增强工具" -> List.of("重新检测 Demucs、rembg、Auto-Editor、ImageMagick 和 OpenCV", "在媒体工具页用测试素材执行单项操作");
            case "APP_MASTER_KEY" -> List.of("重启后端", "环境中心显示 APP_MASTER_KEY 已配置");
            case "API Key / 登录授权" -> List.of("保存后重启后端", "在能力中心测试当前官方来源配置");
            default -> List.of("重新检测环境状态", "确认对应能力显示为可用");
        };
    }

    private boolean guideNeedsNetwork(String name) {
        return Set.of("yt-dlp", "本机媒体运行时", "Edge-TTS", "媒体增强工具", "API Key / 登录授权").contains(name);
    }

    private Map<String, Object> toolState(boolean installed, boolean integrated) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("installed", installed);
        state.put("integrated", integrated);
        state.put("status", !installed ? "missing" : integrated ? "ready" : "detected_only");
        return state;
    }

    private static String firstTool(String portable, String fallback) {
        return portable != null && !portable.isBlank() ? portable : fallback;
    }
    private boolean executableAvailable(String executable, String versionFlag) {
        return runner.available(executable, versionFlag);
    }

    /** faster-whisper "small" 模型在 HF 缓存中的目录名（与 backend/tools/media_diagnose.py 的模型尺寸一致）。 */
    private static final String WHISPER_MODEL_DIR = "hub/models--Systran--faster-whisper-small";

    /** 离线转写模型就绪判定：data/hf-cache（start.bat 已从发行包预置）或发行包 portable/whisper-models 中已存在 small 模型。 */
    private boolean offlineWhisperModelsReady() {
        return offlineWhisperCacheReady(props.data(), props.portableTool("whisper-models/" + WHISPER_MODEL_DIR + "/refs/main"));
    }

    /** whisper.cpp 只有二进制和匹配的 ggml 模型同时存在才算可作为首选 ASR 运行。 */
    private boolean whisperCppRuntimeReady() {
        String cli = firstTool(props.portableTool("whisper/Release/whisper-cli.exe"), "whisper-cli");
        if (!executableAvailable(cli, "--help")) return false;
        for (String name : List.of("ggml-small.bin", "ggml-base.bin", "ggml-tiny.bin")) {
            String model = props.portableTool("whisper-models/" + name);
            if (model != null && Files.isRegularFile(Paths.get(model))) return true;
        }
        return false;
    }

    /** 供单元测试直接验证的纯路径判定：数据目录预置缓存优先，其次发行包内置模型标记文件。 */
    static boolean offlineWhisperCacheReady(Path dataDir, String portableModelMarker) {
        Path seeded = dataDir.resolve("hf-cache").resolve(WHISPER_MODEL_DIR);
        if (Files.isDirectory(seeded)) return true;
        return portableModelMarker != null && Files.isRegularFile(Paths.get(portableModelMarker));
    }

    private String truncateInstallLog(String logText) {
        if (logText == null || logText.isBlank()) return "";
        String redacted = logText.replaceAll("(?i)(token|password|secret)=\\S+", "$1=***");
        return redacted.length() <= INSTALL_LOG_LIMIT ? redacted : redacted.substring(0, INSTALL_LOG_LIMIT) + "\n[日志已截断]";
    }

        /** 用 importlib.util.find_spec 检测模块是否已安装（不真正 import，避免加载重型库如 torch 造成数秒冷启动）。
     *  实测 find_spec 检测单模块约 80ms，而 import faster_whisper/ChatTTS 需 4-6s。 */
    private boolean pythonModuleAvailable(String module) {
        String safe = module.replace("'", "");
        return runner.run(List.of(props.localPythonPath(), "-c",
                "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('" + safe + "') else 1)"), 12).ok();
    }

    /** 供 HTTP 状态接口使用的快速健康状态；由启动探测和定时探测更新。 */
    private boolean databaseConnected() {
        return databaseReady;
    }

    @Scheduled(fixedDelay = 30000)
    void refreshDatabaseHealth() {
        databaseReady = probeDatabase();
    }

    private boolean probeDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 内置工作流 ----------------

    private void seedWorkflows() {
        upsertWorkflow("抖音标准混剪（3秒切片·50-150秒）",
                "对标美妆/护肤/食品类爆款：AI钩子开场 + 实拍与明星素材3秒交替 + 产品段均匀插入3次 + 片尾引导。" +
                        "时长自动落在50-150秒，密集时约100秒。",
                skillEngine.defaultWorkflowDef());

        upsertWorkflow("快节奏种草（2秒切片·60秒）",
                "更快的剪辑节奏，适合信息密度高的种草视频，固定 60 秒左右。",
                buildDef(60, 60, 2.0, 0.3, 4, 0.35));

        upsertWorkflow("长图文口播（4秒切片·130秒）",
                "偏口播讲解，节奏慢、切换少，适合成分党/测评类内容。",
                buildDef(110, 150, 4.0, 0.5, 2, 0.15));
        upsertWorkflow("剧情切片（2秒·45秒）", "情绪抓人、反转密集，适合剧情/短剧类切片，固定 45 秒左右。", buildDef(40, 50, 2.0, 0.4, 1, 0.1));
        upsertWorkflow("商品带货硬广（3秒·30秒）", "密集卖点 + 产品强露出，适合电商短视频，固定 30 秒。", buildDef(25, 35, 3.0, 0.2, 6, 0.05));
        upsertWorkflow("探店/门店宣传（3.5秒·60秒）", "实拍环境 + 菜品/服务展示，适合本地生活。", buildDef(50, 75, 3.5, 0.3, 2, 0.1));
        upsertWorkflow("知识口播（5秒·120秒）", "慢节奏讲解，适合知识/职场/教育类。", buildDef(100, 150, 5.0, 0.5, 1, 0.05));
        upsertWorkflow("爆款拆解（2秒·40秒）", "对标爆款逐条拆解，信息密度高。", buildDef(35, 45, 2.0, 0.35, 2, 0.2));
        upsertWorkflow("短剧切片（1.8秒·35秒）", "高能片段拼接，卡点快切。", buildDef(30, 40, 1.8, 0.3, 1, 0.3));
        upsertWorkflow("明星同款安利（3秒·60秒）", "达人/明星素材 + 产品展示，种草转化。", buildDef(50, 70, 3.0, 0.3, 4, 0.4));
        upsertWorkflow("美食治愈系（2.5秒·50秒）", "食材特写 + 制作过程，治愈放松。", buildDef(40, 60, 2.5, 0.35, 2, 0.05));
        upsertWorkflow("健身干货（4秒·90秒）", "动作教学 + 效果对比，垂直实用。", buildDef(70, 110, 4.0, 0.4, 1, 0.1));
        upsertWorkflow("育儿/母婴（3秒·70秒）", "亲子互动 + 好物推荐，温馨转化。", buildDef(55, 85, 3.0, 0.3, 3, 0.05));
        upsertWorkflow("宠物萌宠（2秒·40秒）", "萌宠高光剪辑，轻松搞笑。", buildDef(30, 50, 2.0, 0.4, 1, 0.05));
        upsertWorkflow("旅行风光（4秒·90秒）", "风景大场景 + 攻略信息，治愈种草。", buildDef(70, 110, 4.0, 0.3, 1, 0.05));
        upsertWorkflow("时尚穿搭（2.5秒·60秒）", "搭配展示 + 时尚氛围，垂类种草。", buildDef(45, 75, 2.5, 0.3, 3, 0.2));
        upsertWorkflow("家居好物（3秒·80秒）", "居家场景 + 好物测评，实用转化。", buildDef(60, 100, 3.0, 0.3, 3, 0.05));
        upsertWorkflow("音乐卡点（1.5秒·35秒）", "节奏卡点混剪，视觉冲击强。", buildDef(30, 40, 1.5, 0.2, 1, 0.2));
        upsertWorkflow("批量矩阵（3秒·随机）", "多账号矩阵批量生产，参数随机化。", buildDef(30, 120, 3.0, 0.5, 3, 0.15));
        upsertWorkflow("直播切片（2秒·40秒）", "直播高光切片，卖点+互动。", buildDef(30, 50, 2.0, 0.3, 4, 0.1));
    }

    // ---------------- 内置项目模板 ----------------

    private void seedProjects() {
        seedProject("美妆测评模板", "Mework 美妆", "美妆", "护肤彩妆", "妆效自然；质地轻薄；上脸服帖", "18-35 岁美妆爱好者", "真实测评", "最强，第一，顶级，国家级，根治，永久，100%，绝对", "{\"minSec\":50,\"maxSec\":90,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.35,\"celebrityRatio\":0.35,\"productSlots\":4,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"beauty\",\"projectRelevantOnly\":true}");
        seedProject("食品种草模板", "Mework 食品", "食品饮料", "零食饮品", "口感层次；配料清晰；日常分享", "18-40 岁日常消费人群", "轻松种草", "最强，第一，顶级，国家级，治愈，永久，100%，绝对", "{\"minSec\":50,\"maxSec\":80,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.45,\"celebrityRatio\":0.1,\"productSlots\":4,\"productSec\":2.5,\"hookSec\":2.5,\"bgmVolume\":0.24,\"namePrefix\":\"food\",\"projectRelevantOnly\":true}");
        seedProject("3C 数码开箱模板", "Mework 数码", "3C 数码", "消费电子", "核心功能；使用场景；开箱体验", "18-40 岁数码爱好者", "理性测评", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":45,\"maxSec\":75,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":4,\"productSec\":2.5,\"hookSec\":2.5,\"bgmVolume\":0.2,\"namePrefix\":\"digital\",\"projectRelevantOnly\":true}");
        seedProject("护肤/美白模板", "Mework 护肤", "美妆护肤", "精华/面霜/防晒", "成分温和；提亮肤色；使用感清爽", "20-40 岁护肤人群", "专业护肤", "最强，第一，顶级，根治，永久，100%，绝对", "{\"minSec\":50,\"maxSec\":85,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.35,\"celebrityRatio\":0.2,\"productSlots\":4,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"skincare\",\"projectRelevantOnly\":true}");
        seedProject("母婴/育儿模板", "Mework 母婴", "母婴用品", "纸尿裤/辅食/玩具", "安全材质；宝宝舒适；妈妈省心", "25-38 岁新手父母", "温馨可信", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":55,\"maxSec\":85,\"dense\":true,\"sliceSec\":3,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":3,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.24,\"namePrefix\":\"baby\",\"projectRelevantOnly\":true}");
        seedProject("宠物好物模板", "Mework 宠物", "宠物用品", "猫粮/玩具/窝具", "适口性好；耐用易清洁；宠物爱用", "20-40 岁养宠人群", "轻松有爱", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":30,\"maxSec\":50,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.4,\"celebrityRatio\":0.05,\"productSlots\":3,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.2,\"namePrefix\":\"pet\",\"projectRelevantOnly\":true}");
        seedProject("健身/运动模板", "Mework 健身", "运动健身", "器械/补剂/装备", "燃脂效率；动作安全；效果可见", "18-40 岁健身人群", "热血专业", "最强，第一，顶级，根治，永久，100%，绝对", "{\"minSec\":60,\"maxSec\":100,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.35,\"celebrityRatio\":0.2,\"productSlots\":3,\"productSec\":2.5,\"hookSec\":2.5,\"bgmVolume\":0.25,\"namePrefix\":\"fitness\",\"projectRelevantOnly\":true}");
        seedProject("家居好物模板", "Mework 家居", "家居日用", "收纳/清洁/小家电", "实用省心；颜值在线；性价比高", "25-45 岁家庭用户", "生活化推荐", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":50,\"maxSec\":80,\"dense\":true,\"sliceSec\":3,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":4,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"home\",\"projectRelevantOnly\":true}");
        seedProject("时尚穿搭模板", "Mework 穿搭", "服饰鞋包", "女装/鞋履/配饰", "显瘦显高；百搭实穿；材质亲肤", "18-35 岁时尚人群", "潮流种草", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":40,\"maxSec\":65,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.3,\"celebrityRatio\":0.3,\"productSlots\":4,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.22,\"namePrefix\":\"fashion\",\"projectRelevantOnly\":true}");
        seedProject("旅行攻略模板", "Mework 旅行", "旅行出行", "机票/酒店/线路", "攻略实用；出片好看；性价比高", "18-45 岁旅行人群", "治愈种草", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":55,\"maxSec\":90,\"dense\":true,\"sliceSec\":4,\"sliceJitter\":0.35,\"celebrityRatio\":0.05,\"productSlots\":2,\"productSec\":2,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"travel\",\"projectRelevantOnly\":true}");
        seedProject("数码配件模板", "Mework 数码配件", "3C 配件", "耳机/充电/支架", "兼容性好；做工精致；体验提升", "18-35 岁数码人群", "理性种草", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":35,\"maxSec\":55,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.3,\"celebrityRatio\":0.1,\"productSlots\":4,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.2,\"namePrefix\":\"accessory\",\"projectRelevantOnly\":true}");
        seedProject("家电评测模板", "Mework 家电", "家用电器", "厨房/清洁/个护电器", "功能实测；噪音能耗；使用便利", "25-45 岁家庭用户", "专业实测", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":60,\"maxSec\":100,\"dense\":true,\"sliceSec\":3,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":3,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"appliance\",\"projectRelevantOnly\":true}");
        seedProject("汽车/车品模板", "Mework 汽车", "汽车用品", "车膜/脚垫/洗护", "安装方便；耐用防护；提升质感", "25-45 岁车主", "实用推荐", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":50,\"maxSec\":80,\"dense\":true,\"sliceSec\":3,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":3,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"auto\",\"projectRelevantOnly\":true}");
        seedProject("健康/养生模板", "Mework 健康", "健康养生", "保健品/养生器械", "成分科学；使用便捷；长期坚持", "30-55 岁健康人群", "专业可信", "最强，第一，顶级，根治，治愈，永久，100%，绝对", "{\"minSec\":55,\"maxSec\":90,\"dense\":true,\"sliceSec\":3.5,\"sliceJitter\":0.35,\"celebrityRatio\":0.1,\"productSlots\":3,\"productSec\":2.5,\"hookSec\":3,\"bgmVolume\":0.2,\"namePrefix\":\"health\",\"projectRelevantOnly\":true}");
        seedProject("教育/知识模板", "Mework 教育", "知识付费", "课程/图书/工具", "干货清晰；案例落地；学完能用", "18-45 岁学习人群", "专业讲师", "最强，第一，顶级，根治，永久，100%，绝对", "{\"minSec\":70,\"maxSec\":120,\"dense\":true,\"sliceSec\":4,\"sliceJitter\":0.4,\"celebrityRatio\":0.05,\"productSlots\":2,\"productSec\":2,\"hookSec\":3,\"bgmVolume\":0.18,\"namePrefix\":\"edu\",\"projectRelevantOnly\":true}");
        seedProject("剧情短剧模板", "Mework 短剧", "剧情内容", "品牌定制短剧", "情绪真实；反转自然；品牌软植入", "18-35 岁泛娱乐人群", "剧情向", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":40,\"maxSec\":60,\"dense\":true,\"sliceSec\":2,\"sliceJitter\":0.4,\"celebrityRatio\":0.4,\"productSlots\":2,\"productSec\":2,\"hookSec\":2,\"bgmVolume\":0.2,\"namePrefix\":\"drama\",\"projectRelevantOnly\":true}");
        seedProject("探店/本地模板", "Mework 探店", "本地生活", "餐饮/门店/服务", "环境真实；口味推荐；性价比点评", "20-40 岁本地人群", "真实探店", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":40,\"maxSec\":65,\"dense\":true,\"sliceSec\":3,\"sliceJitter\":0.35,\"celebrityRatio\":0.05,\"productSlots\":3,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.22,\"namePrefix\":\"local\",\"projectRelevantOnly\":true}");
        seedProject("礼盒/节庆模板", "Mework 礼盒", "礼赠场景", "礼盒/伴手礼/年货", "包装精美；寓意好；送人体面", "25-45 岁送礼人群", "节庆氛围", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":35,\"maxSec\":55,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.3,\"celebrityRatio\":0.05,\"productSlots\":4,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.24,\"namePrefix\":\"gift\",\"projectRelevantOnly\":true}");
        seedProject("日用清洁模板", "Mework 日用", "日用清洁", "洗护/清洁/纸品", "去污力强；温和不伤手；囤货划算", "25-45 岁家庭用户", "实用推荐", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":30,\"maxSec\":50,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.35,\"celebrityRatio\":0.05,\"productSlots\":5,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.2,\"namePrefix\":\"daily\",\"projectRelevantOnly\":true}");
        seedProject("农产品/生鲜模板", "Mework 生鲜", "食品生鲜", "水果/特产/生鲜", "产地直供；新鲜安全；回购率高", "25-50 岁家庭用户", "真诚质朴", "最强，第一，顶级，国家级，永久，100%，绝对", "{\"minSec\":35,\"maxSec\":60,\"dense\":true,\"sliceSec\":2.5,\"sliceJitter\":0.35,\"celebrityRatio\":0.05,\"productSlots\":4,\"productSec\":2,\"hookSec\":2.5,\"bgmVolume\":0.2,\"namePrefix\":\"fresh\",\"projectRelevantOnly\":true}");
    }

    private void seedProject(String name, String brand, String category, String product, String sellingPoints,
                             String audience, String tone, String bannedWords, String defaultParams) {
        if (projectRepo.findByName(name).isPresent()) return;
        Project project = new Project();
        project.setName(name);
        project.setBrand(brand);
        project.setCategory(category);
        project.setProduct(product);
        project.setSellingPoints(sellingPoints);
        project.setAudience(audience);
        project.setTone(tone);
        project.setBannedWords(bannedWords);
        project.setDefaultParams(defaultParams);
        project.setIsBuiltin(true);
        projectRepo.save(project);
    }

    private String buildDef(int min, int max, double slice, double jitter, int productSlots, double celebRatio) {
        ObjectNode root = om.createObjectNode();
        ArrayNode steps = root.putArray("steps");

        ObjectNode s1 = steps.addObject();
        s1.put("skill", "select_materials");
        ObjectNode a1 = s1.putObject("args");
        ArrayNode roles = a1.putArray("roles");
        for (String r : new String[]{"hook", "body", "celebrity", "product", "endcard", "voice", "bgm"}) roles.add(r);
        a1.put("limit", 500);

        ObjectNode s2 = steps.addObject();
        s2.put("skill", "set_duration");
        ObjectNode a2 = s2.putObject("args");
        a2.put("minSec", min);
        a2.put("maxSec", max);
        a2.put("dense", max - min <= 30);

        ObjectNode s3 = steps.addObject();
        s3.put("skill", "set_slice");
        ObjectNode a3 = s3.putObject("args");
        a3.put("sliceSec", slice);
        a3.put("jitter", jitter);
        a3.put("explode", true);
        a3.put("maxPerMaterial", 3);

        ObjectNode s4 = steps.addObject();
        s4.put("skill", "set_structure");
        ObjectNode a4 = s4.putObject("args");
        a4.put("hookSec", 3);
        a4.put("celebrityRatio", celebRatio);
        a4.put("productSlots", productSlots);
        a4.put("productSec", 3);
        a4.put("endcard", true);

        ObjectNode s5 = steps.addObject();
        s5.put("skill", "set_canvas");
        ObjectNode a5 = s5.putObject("args");
        a5.put("width", 1080);
        a5.put("height", 1920);
        a5.put("fps", 30);

        ObjectNode s6 = steps.addObject();
        s6.put("skill", "gen_hook");
        s6.putObject("args").put("extra", "");

        ObjectNode s7 = steps.addObject();
        s7.put("skill", "pick_audio");
        s7.putObject("args").put("bgmVolume", 0.22);

        try {
            return om.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"steps\":[]}";
        }
    }

    private void upsertWorkflow(String name, String desc, String def) {
        Workflow wf = workflowRepo.findByName(name).orElseGet(Workflow::new);
        boolean isNew = wf.getId() == null;
        wf.setName(name);
        wf.setDescription(desc);
        wf.setVersion("1.0");
        wf.setIsBuiltin(true);
        if (isNew || wf.getDef() == null || wf.getDef().isBlank()) {
            wf.setDef(def);
        }
        workflowRepo.save(wf);
    }

    // ---------------- 内置 skill 登记 ----------------

    private void seedSkills() {
        for (Map<String, Object> s : SkillEngine.BUILTIN) {
            String name = String.valueOf(s.get("name"));
            SkillDef d = skillRepo.findByName(name).orElseGet(SkillDef::new);
            d.setName(name);
            d.setDescription(String.valueOf(s.get("description")));
            d.setType(SkillType.builtin.name());
            d.setEnabled(true);
            try {
                d.setDef(om.writeValueAsString(s.get("params")));
            } catch (Exception ignore) {
            }
            skillRepo.save(d);
        }
    }

    // ---------------- 能力清单（capabilities.json） ----------------

    /**
     * 版本化能力清单：backend/src/main/resources/capabilities.json 的唯一加载与校验入口。
     * 清单是能力中心展示与「修复安装」边界的唯一数据源；解析失败（缺字段 / 重复 key /
     * 修复目标未固定版本）直接拒绝启动，避免"清单说可修复、但安装目标不可复现"的漂移。
     */
    static final class CapabilityManifest {
        private static final String RESOURCE = "capabilities.json";
        private static final Pattern PINNED_SPEC = Pattern.compile("^[A-Za-z0-9_.-]+==\\S+$");

        record Entry(String type, String key, String group, String name, String tool, String envKey,
                     boolean needsNetwork, boolean wired, String usedBy, String officialUrl,
                     boolean repairable, String pipRef, String installDisplay,
                     String installMode, String guide, String executionPolicy,
                     boolean offlineCapable, List<String> fallbackKeys, List<String> configVariables,
                     List<String> verifySteps, boolean restartRequired) {
            boolean isExternal() {
                return "external".equals(type);
            }
        }

        record PipSpec(String packageName, String spec, boolean repairable, String origin) {
        }

        private final int schemaVersion;
        private final String manifestVersion;
        private final Map<String, PipSpec> approvedPip;
        private final List<Entry> entries;
        private final Map<String, Entry> byKey;

        private CapabilityManifest(int schemaVersion, String manifestVersion, Map<String, PipSpec> approvedPip,
                                   List<Entry> entries) {
            this.schemaVersion = schemaVersion;
            this.manifestVersion = manifestVersion;
            this.approvedPip = approvedPip;
            this.entries = entries;
            this.byKey = new LinkedHashMap<>();
            for (Entry entry : entries) byKey.put(entry.key(), entry);
        }

        static CapabilityManifest load() {
            try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
                return parse((ObjectNode) new ObjectMapper().readTree(in));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("无法加载能力清单 classpath:" + RESOURCE + "：" + e, e);
            }
        }

        static CapabilityManifest parse(ObjectNode root) {
            if (root == null || !root.has("capabilities") || !root.path("capabilities").isArray()
                    || root.path("capabilities").isEmpty()) {
                throw new IllegalStateException("能力清单缺少 capabilities 数组");
            }
            int schemaVersion = root.path("schemaVersion").asInt(0);
            String manifestVersion = root.path("manifestVersion").asText("");
            if (schemaVersion < 1 || manifestVersion.isBlank()) {
                throw new IllegalStateException("能力清单缺少 schemaVersion/manifestVersion");
            }
            Map<String, PipSpec> approved = new LinkedHashMap<>();
            if (root.path("approvedPip").isObject()) {
                root.path("approvedPip").fields().forEachRemaining(field -> {
                    String name = field.getKey();
                    ObjectNode specNode = (ObjectNode) field.getValue();
                    String spec = specNode.path("spec").asText("");
                    if (!PINNED_SPEC.matcher(spec).matches() || !spec.startsWith(name + "==")) {
                        throw new IllegalStateException("能力清单 approvedPip." + name + " 必须固定版本（name==version）：" + spec);
                    }
                    approved.put(name, new PipSpec(name, spec,
                            specNode.path("repairable").asBoolean(false), specNode.path("origin").asText("")));
                });
            }
            List<Entry> entries = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (JsonNode node : root.path("capabilities")) {
                Entry entry = entryOf((ObjectNode) node);
                if (!keys.add(entry.key())) {
                    throw new IllegalStateException("能力清单存在重复 key：" + entry.key());
                }
                if (entry.repairable()) {
                    PipSpec pip = approved.get(entry.pipRef());
                    if (pip == null || !pip.repairable()) {
                        throw new IllegalStateException("能力 " + entry.key() + " 标记可修复，但 approvedPip." + entry.pipRef() + " 缺失或不允许修复");
                    }
                }
                entries.add(entry);
            }
            return new CapabilityManifest(schemaVersion, manifestVersion, approved, entries);
        }

        private static Entry entryOf(ObjectNode node) {
            String type = node.path("type").asText("");
            String key = node.path("key").asText("");
            if (!("env".equals(type) || "external".equals(type)) || key.isBlank()) {
                throw new IllegalStateException("能力清单条目缺少 type/key：" + key);
            }
            String group = node.path("group").asText("");
            String name = node.path("name").asText("");
            String tool = node.path("tool").asText("");
            String officialUrl = node.path("officialUrl").asText("");
            String envKey = node.path("envKey").asText("");
            String installMode = node.path("installMode").asText("");
            String guide = node.path("guide").asText("");
            if ("env".equals(type) && (group.isBlank() || name.isBlank() || tool.isBlank() || envKey.isBlank())) {
                throw new IllegalStateException("能力 " + key + " 缺少 env 条目必填字段");
            }
            if ("external".equals(type) && (group.isBlank() || name.isBlank() || tool.isBlank()
                    || installMode.isBlank() || officialUrl.isBlank() || guide.isBlank())) {
                throw new IllegalStateException("外部能力 " + key + " 缺少必填字段");
            }
            boolean needsNetwork = node.path("needsNetwork").asBoolean(false);
            String executionPolicy = node.path("executionPolicy").asText("");
            if (executionPolicy.isBlank()) executionPolicy = defaultExecutionPolicy(type, key);
            boolean offlineCapable = node.has("offlineCapable")
                    ? node.path("offlineCapable").asBoolean(false)
                    : defaultOfflineCapable(type, key, needsNetwork);
            List<String> fallbackKeys = stringList(node.path("fallbackKeys"));
            if (fallbackKeys.isEmpty()) fallbackKeys = defaultFallbackKeys(key);
            List<String> configVariables = stringList(node.path("configVariables"));
            if (configVariables.isEmpty() && "env".equals(type)) configVariables = defaultConfigVariables(key, envKey);
            List<String> verifySteps = stringList(node.path("verifySteps"));
            if (verifySteps.isEmpty()) verifySteps = defaultVerifySteps(type, key);
            boolean restartRequired = node.path("restartRequired").asBoolean(false);
            return new Entry(type, key, group, name, tool, envKey,
                    node.path("needsNetwork").asBoolean(false), node.path("wired").asBoolean(false),
                    node.path("usedBy").asText(""), officialUrl, node.path("repairable").asBoolean(false),
                    node.path("pipRef").asText(""), node.path("installDisplay").asText(""),
                    installMode, guide, executionPolicy, offlineCapable, fallbackKeys,
                    configVariables, verifySteps, restartRequired);
        }

        private static String defaultExecutionPolicy(String type, String key) {
            if ("external".equals(type)) return "externalActivation";
            if (Set.of("video-download", "video-download-2", "image-gallery", "tts").contains(key)) return "networkFetch";
            if (Set.of("database").contains(key)) return "readOnlyProbe";
            if (Set.of("video-render", "asr", "asr-local", "ocr", "loudness", "vocals", "matting",
                    "auto-editor", "opencv", "magick").contains(key)) return "mediaTransform";
            return "readOnlyProbe";
        }

        private static boolean defaultOfflineCapable(String type, String key, boolean needsNetwork) {
            if ("external".equals(type) || needsNetwork) return false;
            return !Set.of("chattts", "whisper-model", "nvenc", "pixabay-video", "pexels-video", "freesound").contains(key);
        }

        private static List<String> defaultFallbackKeys(String key) {
            return switch (key) {
                case "asr-local" -> List.of("asr");
                case "chattts" -> List.of("tts");
                case "magick" -> List.of("video-render");
                case "auto-editor" -> List.of("video-render");
                default -> List.of();
            };
        }

        private static List<String> defaultConfigVariables(String key, String envKey) {
            List<String> specific = switch (key) {
                case "video-render", "loudness" -> List.of("APP_FFMPEG", "APP_FFPROBE");
                case "video-download" -> List.of("APP_YTDLP_PATH");
                case "video-download-2" -> List.of("APP_YOU_GET_PATH");
                case "asr", "asr-local", "ocr", "tts", "chattts", "vocals", "matting", "auto-editor", "opencv" -> List.of("APP_LOCAL_PYTHON");
                case "magick" -> List.of("APP_IMAGEMAGICK_PATH");
                default -> List.of();
            };
            if (!specific.isEmpty()) return specific;
            return envKey == null || envKey.isBlank() ? List.of() : List.of(envKey);
        }

        private static List<String> defaultVerifySteps(String type, String key) {
            if ("external".equals(type)) return List.of("完成官方配置后重新检测能力状态");
            return switch (key) {
                case "video-render" -> List.of("运行 ffmpeg -version", "运行 ffprobe -version");
                case "database" -> List.of("检查 MySQL 连接", "读取系统环境状态");
                case "video-download", "video-download-2" -> List.of("运行工具版本检查", "使用已授权公开 URL 做一次抓取预检");
                case "asr", "asr-local" -> List.of("检查 Python/转写引擎", "用测试音频生成转写结果");
                case "ocr" -> List.of("检查 OCR 模块", "用测试图片生成文字识别结果");
                case "tts", "chattts" -> List.of("检查配音引擎", "生成一段测试语音并确认音频可播放");
                case "vocals" -> List.of("检查人声分离模块和模型", "用测试音频生成分离结果");
                default -> List.of("重新检测可执行文件、模块和运行状态");
            };
        }

        private static List<String> stringList(JsonNode node) {
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(value -> {
                String text = value.asText("").trim();
                if (!text.isBlank()) values.add(text);
            });
            return List.copyOf(values);
        }

        int schemaVersion() {
            return schemaVersion;
        }

        String manifestVersion() {
            return manifestVersion;
        }

        int size() {
            return entries.size();
        }

        List<Entry> entries() {
            return entries;
        }

        Entry byKey(String key) {
            return key == null ? null : byKey.get(key);
        }

        /** 修复安装用的固定 pip 目标（如 demucs==4.1.0）；仅 approvedPip 中允许修复的条目可解析。 */
        String approvedSpec(String pipRef) {
            if (pipRef == null || pipRef.isBlank()) return null;
            PipSpec pip = approvedPip.get(pipRef);
            return pip != null && pip.repairable() ? pip.spec() : null;
        }

        /** 全部 approvedPip 条目（含仅随包预置、不可修复的包），供打包脚本与测试做版本一致性校验。 */
        List<PipSpec> approvedPipEntries() {
            return new ArrayList<>(approvedPip.values());
        }
    }
}
