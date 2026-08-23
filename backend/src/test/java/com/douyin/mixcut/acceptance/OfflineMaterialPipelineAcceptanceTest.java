package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.MediaCapabilityRouter;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.MaterialTranscriptStore;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import com.douyin.mixcut.service.AiService;
import com.douyin.mixcut.service.MaterialAnalysisService;
import com.douyin.mixcut.service.MaterialDiagnosisService;
import com.douyin.mixcut.service.MaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-3: runs the material import, diagnosis, and structured analysis pipeline on the checked-in
 * fixtures. It has no Spring context, database, HTTP client, or source-file writes.
 */
class OfflineMaterialPipelineAcceptanceTest {
    @TempDir Path tempDir;

    @Test
    void importsDiagnosesAndAnalysesFixturesWithoutMutatingInputsOrEscapingTempDir() throws Exception {
        Path projectRoot = OfflineAcceptanceSupport.projectRoot();
        AppProps props = OfflineAcceptanceSupport.props(tempDir, projectRoot);
        ProcRunner runner = new ProcRunner();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "bundled ffmpeg is unavailable");
        assumeTrue(runner.available(props.getFfprobe(), "-version"), "bundled ffprobe is unavailable");

        MaterialStore materials = mock(MaterialStore.class);
        Map<Long, Material> byId = OfflineAcceptanceSupport.stubStore(materials);
        MaterialFolderRepo folders = mock(MaterialFolderRepo.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = new FfmpegTool(props, runner);
        MediaCapabilityRouter router = new MediaCapabilityRouter(props, runner, folders);
        MaterialTranscriptStore transcripts = mock(MaterialTranscriptStore.class);
        when(transcripts.findByMaterialId(any())).thenReturn(Optional.empty());
        MaterialDiagnosisService diagnosis = new MaterialDiagnosisService(props, materials, transcripts, ffmpeg, runner, router);
        MaterialService service = new MaterialService(materials, folders, ffmpeg, props, ai, Runnable::run, diagnosis);

        Path inputRoot = tempDir.resolve("copied-fixtures");
        Map<String, Path> inputs = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String id : List.of("video_motion", "video_av", "video_black", "video_solid", "audio_voice", "cover")) {
            Path input = OfflineAcceptanceSupport.copyFixture(id, inputRoot);
            inputs.put(id, input);
            hashes.put(id, OfflineAcceptanceSupport.sha256(input));
        }

        Map<String, Material> registered = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : inputs.entrySet()) {
            Material material = service.register(entry.getValue().toString(), null, true, Material.Source.local, null);
            registered.put(entry.getKey(), material);
            assertEquals(hashes.get(entry.getKey()), OfflineAcceptanceSupport.sha256(entry.getValue()),
                    "register must not alter the fixture: " + entry.getKey());
            OfflineAcceptanceSupport.assertUnder(tempDir, entry.getValue());
        }

        assertEquals(Material.Status.ready, registered.get("video_motion").getStatus());
        assertEquals(Material.Status.ready, registered.get("video_av").getStatus());
        assertEquals(Material.Status.ready, registered.get("audio_voice").getStatus());
        assertEquals(Material.Status.ready, registered.get("cover").getStatus(), "images remain usable for manual/product work");
        assertEquals(Material.FileType.image, registered.get("cover").getFileType());
        assertEquals(Material.Status.failed, registered.get("video_black").getStatus());
        assertEquals(Material.Status.failed, registered.get("video_solid").getStatus());
        assertHasAdmissionReason(registered.get("video_black"));
        assertHasAdmissionReason(registered.get("video_solid"));

        for (Material material : registered.values()) {
            if (material.getThumbnail() != null) {
                Path thumbnail = props.thumbs().resolve(Path.of(material.getThumbnail()).getFileName());
                OfflineAcceptanceSupport.assertUnder(tempDir, thumbnail);
            }
        }

        InMemoryAnalysisStores analysisStores = new InMemoryAnalysisStores();
        MaterialAnalysisService analysis = new MaterialAnalysisService(materials, analysisStores.analysisStore,
                analysisStores.segmentStore, transcripts, diagnosis, ai, ffmpeg, props, Runnable::run);
        Material motion = registered.get("video_motion");
        MaterialAnalysis result = analysis.analyze(motion.getId());

        assertNotNull(result);
        MaterialAnalysis completed = analysisStores.analyses.get(motion.getId());
        assertNotNull(completed, "analysis result must remain in the in-memory test store");
        assertEquals("completed", completed.getStatus());
        assertTrue(List.of("scene", "fallback").contains(completed.getSource()), "source=" + completed.getSource());
        assertFalse(analysisStores.segments.isEmpty(), "analysis must create at least one segment");
        analysisStores.segments.forEach(segment -> {
            assertEquals(motion.getId(), segment.getMaterialId());
            if (segment.getRepresentativeFrameUrl() != null) {
                OfflineAcceptanceSupport.assertUnder(tempDir,
                        props.thumbs().resolve(Path.of(segment.getRepresentativeFrameUrl()).getFileName()));
            }
        });
        JsonNode sampleFrames = new ObjectMapper().readTree(completed.getSampleFramesJson());
        for (JsonNode frame : sampleFrames) {
            OfflineAcceptanceSupport.assertUnder(tempDir,
                    props.thumbs().resolve(Path.of(frame.path("url").asText()).getFileName()));
        }
        try (var files = Files.walk(tempDir)) {
            files.filter(Files::isRegularFile).forEach(path -> OfflineAcceptanceSupport.assertUnder(tempDir, path));
        }
        assertEquals(hashes.get("video_motion"), OfflineAcceptanceSupport.sha256(inputs.get("video_motion")),
                "analysis must not alter the registered input");
        assertTrue(byId.containsKey(motion.getId()));
    }

    private void assertHasAdmissionReason(Material material) {
        assertTrue(material.getTags() != null && material.getTags().contains("低质:"),
                "rejected material needs an actionable reason: " + material.getTags());
    }

    private static final class InMemoryAnalysisStores {
        private final Map<Long, MaterialAnalysis> analyses = new LinkedHashMap<>();
        private final List<MaterialSegment> segments = new ArrayList<>();
        private final AtomicLong analysisIds = new AtomicLong(1);
        private final MaterialAnalysisStore analysisStore = mock(MaterialAnalysisStore.class);
        private final MaterialSegmentStore segmentStore = mock(MaterialSegmentStore.class);

        private InMemoryAnalysisStores() {
            when(analysisStore.findByMaterialId(any())).thenAnswer(invocation ->
                    Optional.ofNullable(analyses.get(invocation.getArgument(0))));
            when(analysisStore.save(any(MaterialAnalysis.class))).thenAnswer(invocation -> {
                MaterialAnalysis analysis = invocation.getArgument(0);
                if (analysis.getId() == null) analysis.setId(analysisIds.getAndIncrement());
                analyses.put(analysis.getMaterialId(), analysis);
                return analysis;
            });
            when(segmentStore.findByMaterialId(any())).thenAnswer(invocation -> segments.stream()
                    .filter(segment -> invocation.getArgument(0).equals(segment.getMaterialId())).toList());
            org.mockito.Mockito.doAnswer(invocation -> {
                Long materialId = invocation.getArgument(0);
                segments.removeIf(segment -> materialId.equals(segment.getMaterialId()));
                return null;
            }).when(segmentStore).deleteByMaterialId(any());
            org.mockito.Mockito.doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                List<MaterialSegment> batch = invocation.getArgument(0);
                segments.addAll(batch);
                return null;
            }).when(segmentStore).insertBatch(any());
        }
    }
}
