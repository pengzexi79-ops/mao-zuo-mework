package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialFolder;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MediaCapabilityRouterTest {

    @TempDir
    Path temp;

    private MediaCapabilityRouter router() throws Exception {
        AppProps props = new AppProps();
        Path script = Files.createFile(temp.resolve("media_diagnose.py"));
        props.setMediaDiagnoseScript(script.toString());
        return new MediaCapabilityRouter(props, new ProcRunner());
    }

    private MediaCapabilityRouter folderRouter() throws Exception {
        AppProps props = new AppProps();
        Path script = Files.createFile(temp.resolve("media_diagnose-folder.py"));
        props.setMediaDiagnoseScript(script.toString());
        MaterialFolderRepo folders = Mockito.mock(MaterialFolderRepo.class);
        return new MediaCapabilityRouter(props, new ProcRunner(), folders);
    }

    @Test
    void buildsOnlyFixedAsrCommand() throws Exception {
        MediaCapabilityRouter router = router();
        Path audio = Files.createFile(temp.resolve("audio.wav"));

        List<String> command = router.asrCommand(audio);
        assertEquals(temp.resolve("media_diagnose.py").toString(), command.get(1));
        assertEquals(List.of("--audio", audio.toString(), "--asr-engine", "auto"), command.subList(2, command.size()));
    }

    @Test
    void rejectsDirectoryAndSymlinkInputs() throws Exception {
        MediaCapabilityRouter router = router();
        assertThrows(IllegalArgumentException.class, () -> router.internalInput(temp));

        Path source = Files.createFile(temp.resolve("source.mp4"));
        Path link = temp.resolve("link.mp4");
        try {
            Files.createSymbolicLink(link, source.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException error) {
            assumeTrue(false, "symbolic links unavailable: " + error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> router.internalInput(link));
    }

    @Test
    void materialInputRequiresFileInsideRegisteredFolder() throws Exception {
        Path root = Files.createDirectory(temp.resolve("materials"));
        Path inside = Files.createFile(root.resolve("inside.mp4"));
        Path outside = Files.createFile(temp.resolve("outside.mp4"));
        MaterialFolderRepo folders = Mockito.mock(MaterialFolderRepo.class);
        MaterialFolder folder = new MaterialFolder();
        folder.setId(7L);
        folder.setPath(root.toString());
        Mockito.when(folders.findById(7L)).thenReturn(Optional.of(folder));
        AppProps props = new AppProps();
        props.setMediaDiagnoseScript(Files.createFile(temp.resolve("media_diagnose-root.py")).toString());
        MediaCapabilityRouter router = new MediaCapabilityRouter(props, new ProcRunner(), folders);

        Material allowed = new Material();
        allowed.setFolderId(7L);
        allowed.setFilePath(inside.toString());
        assertEquals(inside.toAbsolutePath().normalize(), router.materialInput(allowed));

        Material denied = new Material();
        denied.setFolderId(7L);
        denied.setFilePath(outside.toString());
        assertThrows(IllegalArgumentException.class, () -> router.materialInput(denied));
    }

    @Test
    void materialInputRejectsMissingPath() throws Exception {
        MediaCapabilityRouter router = router();
        Material material = new Material();
        material.setFilePath(temp.resolve("missing.mp4").toString());
        assertThrows(IllegalArgumentException.class, () -> router.materialInput(material));
    }
}
