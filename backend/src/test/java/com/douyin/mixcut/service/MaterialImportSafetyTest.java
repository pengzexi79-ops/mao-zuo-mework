package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialFolder;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialImportSafetyTest {

    @Mock private MaterialStore materialRepo;
    @Mock private MaterialFolderRepo folderRepo;
    @Mock private FfmpegTool ffmpeg;
    @Mock private AiService aiService;
    @Mock private MaterialDiagnosisService diagnosisService;

    @Test
    void rejectsWechatEncryptedUploadBeforeWritingMedia() throws Exception {
        MaterialService service = service(Files.createTempDirectory("mixcut-material-test-"));
        MockMultipartFile file = new MockMultipartFile("file", "wechat.dat", "application/octet-stream", new byte[]{1, 2, 3});

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upload(file, MaterialRole.none, null));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("微信加密缓存"));
    }

    @Test
    void packageImportUsesExplicitArchiveFolderWithoutChangingItsPath() throws Exception {
        MaterialService service = service(Files.createTempDirectory("mixcut-material-test-"));
        MaterialFolder target = new MaterialFolder();
        target.setId(42L);
        target.setName("预设归档");
        target.setPath("D:/existing-source-folder");
        target.setEnabled(true);
        when(folderRepo.findById(42L)).thenReturn(Optional.of(target));

        FfmpegTool.MediaInfo info = new FfmpegTool.MediaInfo();
        info.setReadableImage(true);
        info.setWidth(20);
        info.setHeight(20);
        when(ffmpeg.probe(any())).thenReturn(info);
        when(materialRepo.findByFilePath(any())).thenReturn(Optional.empty());
        when(materialRepo.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MaterialService.PackageImportResult result = service.importPackage("原始文件夹",
                java.util.List.of(new MockMultipartFile("files", "cover.png", "image/png", new byte[]{1, 2, 3})),
                java.util.List.of("原始文件夹/cover.png"), MaterialRole.none, 42L);

        assertEquals(42L, result.getFolderId());
        assertEquals(42L, result.getAudioFolderId());
        assertEquals(42L, result.getVideoFolderId());
        assertEquals(42L, result.getImageFolderId());
        assertEquals(1, result.getImageImported());
        assertEquals("D:/existing-source-folder", target.getPath());
        ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
        verify(materialRepo, atLeastOnce()).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getAllValues().stream()
                .anyMatch(material -> Long.valueOf(42L).equals(material.getFolderId())));
    }

    @Test
    void packageImportRejectsUnknownExplicitArchiveFolder() throws Exception {
        MaterialService service = service(Files.createTempDirectory("mixcut-material-test-"));
        when(folderRepo.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importPackage("原始文件夹", java.util.List.of(new MockMultipartFile("files", "clip.mp4", "video/mp4", new byte[]{1})), java.util.List.of("原始文件夹/clip.mp4"), MaterialRole.none, 99L));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("目标文件夹不存在"));
    }

    @Test
    void rejectsZipSlipBeforeWritingOutsideArchiveRoot() throws Exception {
        MaterialService service = service(Files.createTempDirectory("mixcut-material-test-"));
        when(folderRepo.findByPath(any())).thenReturn(Optional.empty());
        when(folderRepo.save(any(MaterialFolder.class))).thenAnswer(invocation -> {
            MaterialFolder folder = invocation.getArgument(0);
            folder.setId(1L);
            return folder;
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../outside.mp4"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        MockMultipartFile archive = new MockMultipartFile("file", "materials.zip", "application/zip", bytes.toByteArray());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importArchive(archive, MaterialRole.none, null));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("越界路径"));
    }

    private MaterialService service(Path dataDir) {
        AppProps props = new AppProps();
        props.setDataDir(dataDir.toString());
        props.setMaterialsDir(dataDir.resolve("materials").toString());
        props.setCacheDir(dataDir.resolve("cache").toString());
        return new MaterialService(materialRepo, folderRepo, ffmpeg, props, aiService, Runnable::run, diagnosisService);
    }
}
