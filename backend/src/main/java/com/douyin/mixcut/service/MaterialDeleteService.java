package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.CrawlTask;
import com.douyin.mixcut.domain.Job;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.MaterialTranscriptStore;
import com.douyin.mixcut.repository.Repositories.CrawlJobRepo;
import com.douyin.mixcut.repository.Repositories.CrawlTaskRepo;
import com.douyin.mixcut.repository.Repositories.JobRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控的永久删除：只删除应用管理的文件（props.materials() 下的上传/切片等）、
 * 缩略图与转写/分析/片段记录，以及素材库 DB 记录。绝不删除外部扫描目录里的原始文件，
 * 也不碰 output 目录里的成片。有进行中任务引用时阻止删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialDeleteService {

    private static final List<String> ACTIVE_STATUSES = List.of(
            JobStatus.pending.name(), JobStatus.running.name(), JobStatus.paused.name());
    private static final List<String> MATERIAL_ID_KEYS = List.of(
            "voiceMaterialId", "bgmMaterialId", "hookAudioMaterialId", "introMaterialId");

    private final MaterialStore materialStore;
    private final MaterialTranscriptStore transcriptStore;
    private final MaterialAnalysisStore analysisStore;
    private final MaterialSegmentStore segmentStore;
    private final JobRepo jobRepo;
    private final CrawlJobRepo crawlJobRepo;
    private final CrawlTaskRepo crawlTaskRepo;
    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();

    /** 删除影响预览（不执行任何删除）。 */
    @Data
    public static class DeleteImpact {
        private Long materialId;
        /** 源文件是否为应用管理（位于 props.materials() 之下）；false 表示外部扫描路径，不删除。 */
        private boolean appManaged;
        private String sourceFilePath;
        private List<String> filesToDelete = new ArrayList<>();
        /** Number of material rows sharing the same source path; shared files are never removed. */
        private long sharedFileReferenceCount;
        private String thumbnail;
        private int transcriptCount;
        private int analysisCount;
        private int segmentCount;
        private List<Map<String, Object>> activeJobRefs = new ArrayList<>();
        private boolean blocked;
    }

    /** 确认删除后的执行结果。 */
    @Data
    public static class DeleteResult {
        private Long materialId;
        private List<String> deletedFiles = new ArrayList<>();
        private boolean deletedThumbnail;
        private int deletedTranscripts;
        private int deletedAnalyses;
        private int deletedSegments;
    }

    public DeleteImpact preview(Long materialId) {
        Material material = loadOrThrow(materialId);
        DeleteImpact impact = new DeleteImpact();
        impact.setMaterialId(materialId);
        boolean appManaged = isAppManaged(material);
        impact.setAppManaged(appManaged);
        impact.setSourceFilePath(appManaged ? material.getFilePath() : null);
        impact.setSharedFileReferenceCount(material.getFilePath() == null ? 0 : materialStore.countByFilePath(material.getFilePath()));

        List<String> files = new ArrayList<>();
        if (appManaged && material.getFilePath() != null) {
            Path file = Path.of(material.getFilePath()).toAbsolutePath().normalize();
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file.toString());
        }
        impact.setFilesToDelete(files);

        Path thumb = thumbnailPath(materialId);
        impact.setThumbnail(Files.isRegularFile(thumb, LinkOption.NOFOLLOW_LINKS) ? thumb.toString() : null);
        impact.setTranscriptCount(transcriptStore.findAllByMaterialId(materialId).size());
        impact.setAnalysisCount(analysisStore.findAllByMaterialId(materialId).size());
        impact.setSegmentCount(segmentStore.findByMaterialId(materialId).size());

        List<Map<String, Object>> refs = activeJobRefs(materialId);
        impact.setActiveJobRefs(refs);
        impact.setBlocked(!refs.isEmpty());
        return impact;
    }

    /** 执行永久删除；有进行中任务引用时拒绝。 */
    public DeleteResult confirm(Long materialId) {
        Material material = loadOrThrow(materialId);
        DeleteImpact impact = preview(materialId);
        if (impact.isBlocked()) {
            throw new IllegalArgumentException("该素材正被进行中的任务引用，请先取消任务后再删除");
        }
        if (impact.getSharedFileReferenceCount() > 1) {
            throw new IllegalArgumentException("该文件仍被多个素材记录引用，已保留源文件；可先清理重复记录后再删除");
        }

        DeleteResult result = new DeleteResult();
        result.setMaterialId(materialId);

        // 仅删除应用管理的源文件（上传/切片等）；外部扫描路径一律不碰。
        if (impact.isAppManaged() && material.getFilePath() != null) {
            Path file = Path.of(material.getFilePath()).toAbsolutePath().normalize();
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.deleteIfExists(file);
                    result.getDeletedFiles().add(file.toString());
                } catch (Exception e) {
                    log.warn("无法删除素材文件 {}: {}", file, e.toString());
                }
            }
        }

        Path thumb = thumbnailPath(materialId);
        if (Files.isRegularFile(thumb, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.deleteIfExists(thumb);
                result.setDeletedThumbnail(true);
            } catch (Exception e) {
                log.warn("无法删除缩略图 {}: {}", thumb, e.toString());
            }
        }

        result.setDeletedTranscripts(transcriptStore.findAllByMaterialId(materialId).size());
        transcriptStore.deleteByMaterialId(materialId);

        result.setDeletedAnalyses(analysisStore.findAllByMaterialId(materialId).size());
        analysisStore.deleteByMaterialId(materialId);

        result.setDeletedSegments(segmentStore.findByMaterialId(materialId).size());
        segmentStore.deleteByMaterialId(materialId);

        materialStore.deleteById(materialId);
        return result;
    }

    // ---------------- 引用检测 ----------------

    /**
     * 找出所有引用该素材的进行中任务。渲染任务的引用来自 params 快照里的显式素材 id
     * （materialIds 数组或 voice/bgm/hookAudio/intro 单素材 id）；采集任务的引用来自
     * crawl_task.material_id。未限定素材范围（materialIds 为空）的任务不视为显式引用，
     * 避免删除任意素材时被无关任务误拦截。
     */
    private List<Map<String, Object>> activeJobRefs(Long materialId) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String status : ACTIVE_STATUSES) {
            for (Job job : jobRepo.findByStatus(status)) {
                if (jobParamsReference(job.getParams(), materialId)) {
                    refs.add(ref("render", job.getId(), job.getName()));
                }
            }
        }
        for (CrawlTask task : crawlTaskRepo.findByMaterialId(materialId)) {
            CrawlJob crawlJob = crawlJobRepo.findById(task.getJobId()).orElse(null);
            if (crawlJob != null && ACTIVE_STATUSES.contains(crawlJob.getStatus())) {
                refs.add(ref("crawl", crawlJob.getId(), crawlJob.getName()));
            }
        }
        return refs;
    }

    private boolean jobParamsReference(String paramsJson, Long materialId) {
        if (paramsJson == null || paramsJson.isBlank()) return false;
        try {
            JsonNode root = om.readTree(paramsJson);
            JsonNode materialIds = root.get("materialIds");
            if (materialIds != null && materialIds.isArray()) {
                for (JsonNode node : materialIds) {
                    if (node.isNumber() && node.asLong(-1) == materialId) return true;
                }
            }
            for (String key : MATERIAL_ID_KEYS) {
                JsonNode node = root.get(key);
                if (node != null && node.isNumber() && node.asLong(-1) == materialId) return true;
            }
        } catch (Exception ignore) {
            // 参数快照损坏时按"无显式引用"处理，避免误拦截。
        }
        return false;
    }

    private Map<String, Object> ref(String kind, Long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", id);
        row.put("name", name == null ? "" : name);
        return row;
    }

    // ---------------- 工具 ----------------

    private Material loadOrThrow(Long materialId) {
        return materialStore.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在"));
    }

    private boolean isAppManaged(Material material) {
        if (material == null || material.getFilePath() == null || material.getFilePath().isBlank()) return false;
        try {
            Path materialsRoot = props.materials().toAbsolutePath().normalize();
            Path file = Path.of(material.getFilePath()).toAbsolutePath().normalize();
            return file.startsWith(materialsRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private Path thumbnailPath(Long materialId) {
        return props.thumbs().resolve("m" + materialId + ".jpg");
    }
}
