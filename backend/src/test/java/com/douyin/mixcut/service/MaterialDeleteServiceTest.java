package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialDeleteServiceTest {

    @Mock private MaterialStore materialStore;
    @Mock private MaterialTranscriptStore transcriptStore;
    @Mock private MaterialAnalysisStore analysisStore;
    @Mock private MaterialSegmentStore segmentStore;
    @Mock private JobRepo jobRepo;
    @Mock private CrawlJobRepo crawlJobRepo;
    @Mock private CrawlTaskRepo crawlTaskRepo;

    private AppProps props(Path root) {
        AppProps p = new AppProps();
        p.setDataDir(root.toString());
        p.setMaterialsDir(root.resolve("materials").toString());
        p.setCacheDir(root.resolve("cache").toString());
        return p;
    }

    private MaterialDeleteService service(AppProps props) {
        return new MaterialDeleteService(materialStore, transcriptStore, analysisStore, segmentStore,
                jobRepo, crawlJobRepo, crawlTaskRepo, props);
    }

    private Material material(Path filePath) {
        Material m = new Material();
        m.setId(100L);
        m.setName(filePath.getFileName().toString());
        m.setFilePath(filePath.toString());
        m.setFileType(Material.FileType.video);
        m.setStatus(Material.Status.ready);
        m.setSource(Material.Source.local);
        return m;
    }

    @Test
    void previewFlagsExternalScannedPathAsNotAppManaged() throws Exception {
        Path root = Files.createTempDirectory("mixcut-delete-");
        Path external = root.resolve("external").resolve("ext.mp4");
        Files.createDirectories(external.getParent());
        Files.write(external, new byte[]{1});

        Material material = material(external);
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(transcriptStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(analysisStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(segmentStore.findByMaterialId(100L)).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.pending.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.running.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.paused.name())).thenReturn(List.of());
        when(crawlTaskRepo.findByMaterialId(100L)).thenReturn(List.of());

        MaterialDeleteService.DeleteImpact impact = service(props(root)).preview(100L);

        assertFalse(impact.isAppManaged(), "外部扫描路径不属于应用管理，不应删除源文件");
        assertTrue(impact.getFilesToDelete().isEmpty());
        assertFalse(impact.isBlocked());
    }

    @Test
    void previewBlocksWhenActiveRenderJobReferencesMaterial() throws Exception {
        Path root = Files.createTempDirectory("mixcut-delete-");
        Path appManaged = root.resolve("materials").resolve("uploads").resolve("a.mp4");
        Files.createDirectories(appManaged.getParent());
        Files.write(appManaged, new byte[]{1});

        Material material = material(appManaged);
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(transcriptStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(analysisStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(segmentStore.findByMaterialId(100L)).thenReturn(List.of());

        Job active = new Job();
        active.setId(9L);
        active.setName("批量出片 08-11 12:00");
        active.setStatus(JobStatus.running.name());
        active.setParams("{\"materialIds\":[100]}");
        when(jobRepo.findByStatus(JobStatus.pending.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.running.name())).thenReturn(List.of(active));
        when(jobRepo.findByStatus(JobStatus.paused.name())).thenReturn(List.of());
        when(crawlTaskRepo.findByMaterialId(100L)).thenReturn(List.of());

        MaterialDeleteService.DeleteImpact impact = service(props(root)).preview(100L);

        assertTrue(impact.isBlocked(), "进行中任务引用时应阻止删除");
        assertFalse(impact.getActiveJobRefs().isEmpty());
    }

    @Test
    void confirmDeletesOnlyAppManagedFileAndDerivedRecords() throws Exception {
        Path root = Files.createTempDirectory("mixcut-delete-");
        Path appManaged = root.resolve("materials").resolve("slices").resolve("clip.mp4");
        Files.createDirectories(appManaged.getParent());
        Files.write(appManaged, new byte[]{1});

        Material material = material(appManaged);
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(transcriptStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(analysisStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(segmentStore.findByMaterialId(100L)).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.pending.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.running.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.paused.name())).thenReturn(List.of());
        when(crawlTaskRepo.findByMaterialId(100L)).thenReturn(List.of());

        MaterialDeleteService.DeleteResult result = service(props(root)).confirm(100L);

        assertFalse(result.getDeletedFiles().isEmpty(), "应用管理的切片文件应被删除");
        assertFalse(Files.exists(appManaged), "应用管理的源文件应已从磁盘移除");
        verify(materialStore).deleteById(100L);
        verify(transcriptStore).deleteByMaterialId(100L);
        verify(analysisStore).deleteByMaterialId(100L);
        verify(segmentStore).deleteByMaterialId(100L);
    }

    @Test
    void confirmNeverDeletesExternalScannedFile() throws Exception {
        Path root = Files.createTempDirectory("mixcut-delete-");
        Path external = root.resolve("external").resolve("original.mp4");
        Files.createDirectories(external.getParent());
        Files.write(external, new byte[]{1});

        Material material = material(external);
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(transcriptStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(analysisStore.findAllByMaterialId(100L)).thenReturn(List.of());
        when(segmentStore.findByMaterialId(100L)).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.pending.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.running.name())).thenReturn(List.of());
        when(jobRepo.findByStatus(JobStatus.paused.name())).thenReturn(List.of());
        when(crawlTaskRepo.findByMaterialId(100L)).thenReturn(List.of());

        MaterialDeleteService.DeleteResult result = service(props(root)).confirm(100L);

        assertTrue(result.getDeletedFiles().isEmpty());
        assertTrue(Files.exists(external), "外部扫描路径的原始文件必须保留");
        verify(materialStore).deleteById(100L);
    }
}
