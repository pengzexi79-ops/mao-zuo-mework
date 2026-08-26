package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialFolder;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal-only routing for local media capabilities. Browser input never reaches this class.
 * Each command is assembled from application-owned executable/script paths and fixed arguments.
 */
@Component
public class MediaCapabilityRouter {
    private final AppProps props;
    private final ProcRunner runner;
    private final MaterialFolderRepo folderRepo;

    public MediaCapabilityRouter(AppProps props, ProcRunner runner) {
        this(props, runner, null);
    }

    @Autowired
    public MediaCapabilityRouter(AppProps props, ProcRunner runner, MaterialFolderRepo folderRepo) {
        this.props = props;
        this.runner = runner;
        this.folderRepo = folderRepo;
    }

    public Path materialInput(Material material) {
        if (material == null) throw new IllegalArgumentException("素材不存在");
        Path input = regularFile(material.getFilePath(), "素材文件不可读取");
        if (material.getFolderId() != null && folderRepo != null) {
            MaterialFolder folder = folderRepo.findById(material.getFolderId())
                    .orElseThrow(() -> new IllegalArgumentException("素材所属文件夹不存在"));
            Path root = regularDirectory(folder.getPath(), "素材所属文件夹不可读取");
            if (!input.startsWith(root)) throw new IllegalArgumentException("素材文件不在所属文件夹范围内");
        }
        return input;
    }

    public Path internalInput(Path path) {
        return regularFile(path == null ? null : path.toString(), "媒体输入文件不可读取");
    }

    public Path diagnosticScript() {
        Path script = props.mediaDiagnoseScriptPath();
        if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(script)) {
            throw new IllegalStateException("媒体诊断脚本不可用");
        }
        return script.toAbsolutePath().normalize();
    }

    public String ffmpeg() {
        return checkedExecutable(props.getFfmpeg(), "FFmpeg");
    }

    public String localPython() {
        return checkedExecutable(props.localPythonPath(), "应用 Python");
    }

    public List<String> asrCommand(Path audio) {
        Path input = internalInput(audio);
        return List.of(localPython(), diagnosticScript().toString(), "--audio", input.toString(), "--asr-engine", "auto");
    }

    public List<String> ocrCommand(List<Path> frames) {
        if (frames == null || frames.isEmpty() || frames.size() > 12) {
            throw new IllegalArgumentException("OCR 帧数量不在受控范围内");
        }
        List<String> command = new ArrayList<>(List.of(localPython(), diagnosticScript().toString()));
        for (Path frame : frames) {
            command.add("--image");
            command.add(internalInput(frame).toString());
        }
        return List.copyOf(command);
    }

    public List<String> videoQualityCommand(Path video) {
        return List.of(localPython(), diagnosticScript().toString(), "--video-quality", internalInput(video).toString());
    }

    private Path regularFile(String raw, String message) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(message);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(message);
        }
        return path;
    }

    private Path regularDirectory(String raw, String message) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(message);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(message);
        }
        return path;
    }

    private String checkedExecutable(String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException(label + "路径未配置");
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (Files.exists(path) && (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException(label + "路径不可用");
        }
        // PATH names such as "ffmpeg" remain valid; only configured filesystem paths are checked.
        return raw;
    }
}
