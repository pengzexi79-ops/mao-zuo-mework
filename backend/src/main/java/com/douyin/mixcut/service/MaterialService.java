package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialFolder;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcessRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 素材库。
 *
 * 老板要的"调用所有电脑里面的素材"落在这里：给一个目录，递归扫，自动 probe 时长分辨率，
 * 按文件名关键词自动归类到 hook/product/celebrity/bgm/voice… 免得客户手动一条条打标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialService {

    private final MaterialStore materialRepo;
    private final MaterialFolderRepo folderRepo;
    private final FfmpegTool ffmpeg;
    private final AppProps props;
    private final AiService aiService;
    @Qualifier("mediaExecutor") private final java.util.concurrent.Executor mediaExecutor;
    private final MaterialDiagnosisService diagnosisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "mov", "mkv", "avi", "flv", "webm", "m4v", "wmv", "ts", "mts", "m2ts", "3gp", "3g2", "ogv",
            "vob", "mpg", "mpeg", "m2v", "mxf", "asf", "divx", "f4v", "rm", "rmvb", "qt", "dv");
    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "aiff", "aif", "amr",
            "ape", "alac", "ac3", "eac3", "dts", "caf", "au", "ra");
    private static final Set<String> IMAGE_EXT = Set.of(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "tif", "tiff");
    private static final long MIN_IMPORT_FREE_BYTES = 256L * 1024 * 1024;
    private static final long DISK_SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024;

    /** 文件名关键词 → 角色。命中即自动归类。 */
    private static final Map<MaterialRole, List<String>> ROLE_HINTS = new LinkedHashMap<>() {{
        put(MaterialRole.hook, List.of("钩子", "开头", "hook", "opening", "吸睛", "前3秒", "封面"));
        put(MaterialRole.product, List.of("产品", "商品", "product", "货", "卖点", "口播产品", "特写"));
        put(MaterialRole.celebrity, List.of("明星", "达人", "celeb", "kol", "网红", "背书", "代言"));
        put(MaterialRole.voice, List.of("配音", "人声", "口播", "voice", "vo_", "旁白", "解说"));
        put(MaterialRole.bgm, List.of("bgm", "音乐", "背景音", "music", "配乐", "纯音乐"));
        put(MaterialRole.endcard, List.of("结尾", "片尾", "endcard", "outro", "引导", "关注"));
        put(MaterialRole.body, List.of("实拍", "正片", "主体", "body", "素材", "场景", "使用"));
    }};

    @Data
    public static class ScanResult {
        private int scanned;
        private int imported;
        private int updated;   // 已存在但刷新过时长/宽高/角色的文件
        private int skipped;   // 不存在或当前未识别类型
        private int failed;
        private List<String> errors = new ArrayList<>();
    }

    // ---------------- 扫描本地目录 ----------------

    public ScanResult scanFolder(String dirPath, boolean autoRole) {
        ScanResult res = new ScanResult();
        String normalizedPath = dirPath == null ? "" : dirPath.trim().replaceFirst("^\\\"|\\\"$", "");
        Path root = Paths.get(normalizedPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            res.getErrors().add("目录不存在: " + normalizedPath);
            return res;
        }
        MaterialFolder folder = folderRepo.findByPath(root.toAbsolutePath().toString())
                .orElseGet(() -> {
                    MaterialFolder f = new MaterialFolder();
                    f.setName(root.getFileName() == null ? dirPath : root.getFileName().toString());
                    f.setPath(root.toAbsolutePath().toString());
                    return folderRepo.save(f);
                });
        if (Boolean.FALSE.equals(folder.getEnabled())) {
            res.getErrors().add("文件夹已停用，本次未扫描：" + folder.getName());
            return res;
        }

        final int maxFiles = 20_000;
        // Covers the typical WeChat Files/.../FileStorage/... hierarchy without scanning indefinitely.
        final int maxDepth = 12;
        final int maxErrors = 20;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                private int visited;
                private boolean capped;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path normalized = dir.toAbsolutePath().normalize();
                    if (!normalized.equals(root) && isAppManagedPath(normalized)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, java.io.IOException error) {
                    res.skipped++;
                    if (res.getErrors().size() < maxErrors) {
                        res.getErrors().add(file.getFileName() + ": 无法读取（" + error.getClass().getSimpleName() + "）");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visited > maxFiles) {
                        if (!capped) {
                            capped = true;
                            res.getErrors().add("目录文件过多：本次只扫描前 20000 个文件，请缩小目录后继续扫描");
                        }
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                        res.skipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    Path p = file.toAbsolutePath().normalize();
                    if (isAppManagedPath(p)) {
                        res.skipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    String ext = ext(p.getFileName().toString());
                    if (!isSupportedExtension(ext)) {
                        res.skipped++;
                        if (isWechatEncryptedExtension(ext) && res.getErrors().size() < maxErrors) {
                            res.getErrors().add(p.getFileName() + ": 微信加密缓存文件，请在微信中另存为正常媒体后再导入");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    res.scanned++;
                    String abs = p.toString();
                    try {
                        if (materialRepo.existsByFilePath(abs)) {
                            // 普通重扫只补缺失缩略图，避免每次扫描都启动 ffprobe/ffmpeg；“重新探测”仍会强制刷新。
                            if (refreshExistingIfNeeded(abs, folder.getId(), autoRole)) res.updated++;
                        } else {
                            register(abs, folder.getId(), autoRole, Material.Source.local, null);
                            res.imported++;
                        }
                    } catch (Exception e) {
                        res.failed++;
                        if (res.getErrors().size() < maxErrors) {
                            res.getErrors().add(p.getFileName() + ": " + conciseError(e));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            if (res.getErrors().size() < maxErrors) res.getErrors().add("扫描目录失败: " + conciseError(e));
        }
        log.info("scanFolder {} -> scanned={} imported={} updated={} skipped={} failed={}",
                dirPath, res.scanned, res.imported, res.updated, res.skipped, res.failed);
        return res;
    }

    // ---------------- 总包导入 ----------------

    @Data
    public static class PackageNameAudit {
        private String originalName;
        private String normalizedName;
        private boolean valid;
        private boolean aiAvailable;
        private boolean aiApproved;
        private String reason;
        private String suggestion;
    }

    @Data
    public static class PackageImportResult {
        private String packageName;
        private Long folderId;
        private Long audioFolderId;
        private Long videoFolderId;
        private Long imageFolderId;
        private boolean explicitTargetFolder;
        private PackageNameAudit nameAudit;
        private int audioImported;
        private int videoImported;
        private int imageImported;
        private int skipped;
        private int failed;
        private int workflowFiles;
        private List<Map<String, String>> workflowPacks = new ArrayList<>();
        private List<String> errors = new ArrayList<>();

        public int getImported() { return audioImported + videoImported + imageImported; }
    }

    /** 名称审核是建议层；最终是否允许使用永远由本地规则决定。 */
    public PackageNameAudit auditPackageName(String rawName) {
        PackageNameAudit audit = new PackageNameAudit();
        audit.setOriginalName(rawName == null ? "" : rawName.trim());
        String local = normalizePackageName(audit.getOriginalName());
        audit.setNormalizedName(local);
        audit.setSuggestion(local);
        if (local.isBlank()) {
            audit.setValid(false);
            audit.setReason("总包名称不能为空");
            return audit;
        }
        String aiReason = null;
        try {
            JsonNode node = aiService.askJson(UseCase.naming,
                    "你是素材库命名审核员。只审核显示名称，不生成路径，不执行任何操作。返回 JSON：{\\\"approved\\\":true/false,\\\"suggestion\\\":\\\"不超过80字的中文显示名\\\",\\\"reason\\\":\\\"简短原因\\\"}。",
                    "请审核总包名称：" + audit.getOriginalName(), 0.2, 220, null);
            audit.setAiAvailable(node != null);
            if (node != null) {
                audit.setAiApproved(node.path("approved").asBoolean(true));
                String suggestion = normalizePackageName(node.path("suggestion").asText(local));
                if (!suggestion.isBlank()) audit.setSuggestion(suggestion);
                aiReason = node.path("reason").asText("");
            }
        } catch (Exception e) {
            log.debug("package name AI audit unavailable: {}", e.toString());
            audit.setAiAvailable(false);
        }
        audit.setValid(isLegalPackageName(local));
        audit.setReason(audit.isValid()
                ? (audit.isAiAvailable() ? (aiReason == null || aiReason.isBlank() ? "本地规则通过，AI 建议可直接使用" : aiReason) : "AI 暂不可用，已使用本地规则继续")
                : localNameError(local));
        return audit;
    }

    /**
     * 导入浏览器选择的文件夹。relativePaths 与 files 按顺序对应；总包名只来自 packageName，
     * 不从内部子目录名称推导素材库名称。
     */
    public PackageImportResult importPackage(String packageName, List<MultipartFile> files,
                                              List<String> relativePaths, MaterialRole role) throws Exception {
        return importPackage(packageName, files, relativePaths, role, null);
    }

    /**
     * Imports a browser-selected folder into an explicit archive folder when provided. Without a
     * target, the top-level package name retains the existing create-or-merge behavior.
     */
    public PackageImportResult importPackage(String packageName, List<MultipartFile> files,
                                              List<String> relativePaths, MaterialRole role, Long targetFolderId) throws Exception {
        PackageNameAudit audit = auditPackageName(packageName);
        if (!audit.isValid()) throw new IllegalArgumentException("总包名称不合法：" + audit.getReason() + "。请修改后重新导入");
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("没有收到文件夹中的可读文件");
        PackageImportResult result = preparePackageResult(audit, targetFolderId);
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relative = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.getOriginalFilename();
            importPackageFile(result, file, relative, role);
        }
        return result;
    }

    private String zipTopLevelName(MultipartFile archive, String fallback) {
        try (InputStream raw = archive.getInputStream(); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.getName() == null) return fallback;
            String name = entry.getName().replace('\\', '/');
            int slash = name.indexOf('/');
            String top = slash >= 0 ? name.substring(0, slash) : name;
            return top.isBlank() ? fallback : top;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** ZIP 只接受 ZIP；顶层目录名优先，若 ZIP 没有顶层目录则使用压缩文件名。 */
    public PackageImportResult importPackageArchive(MultipartFile archive, String requestedName, MaterialRole role) throws Exception {
        return importPackageArchive(archive, requestedName, role, null);
    }

    public PackageImportResult importPackageArchive(MultipartFile archive, String requestedName, MaterialRole role, Long targetFolderId) throws Exception {
        if (archive == null || archive.isEmpty()) throw new IllegalArgumentException("ZIP 素材包为空或尚未完整保存");
        String archiveName = archive.getOriginalFilename() == null ? "" : Path.of(archive.getOriginalFilename()).getFileName().toString();
        if (!"zip".equals(ext(archiveName))) throw new IllegalArgumentException("目前只支持 ZIP 素材包；RAR/7Z 请先在本机解压后选择文件夹导入");
        String archiveStem = stripExtension(archiveName);
        String topLevelName = zipTopLevelName(archive, archiveStem);
        // The initial UI value is the archive stem. In that case prefer the real top-level package name;
        // a different value is an explicit user correction and is retained after local validation.
        String candidate = requestedName == null || requestedName.isBlank() || requestedName.trim().equals(archiveStem)
                ? topLevelName : requestedName;
        final int maxEntries = 2_000;
        final int maxDepth = 12;
        PackageNameAudit audit = auditPackageName(candidate);
        if (!audit.isValid()) throw new IllegalArgumentException("总包名称不合法：" + audit.getReason() + "。请修改后重新导入");
        PackageImportResult result = preparePackageResult(audit, targetFolderId);
        Path staging = Files.createTempDirectory(props.materials().resolve("uploads"), "package-zip-").toAbsolutePath().normalize();
        int entries = 0;
        try (InputStream raw = archive.getInputStream(); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IllegalArgumentException("ZIP 条目过多，最多允许 " + maxEntries + " 个文件");
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.startsWith("/") || entryName.indexOf('\0') >= 0) throw new IllegalArgumentException("ZIP 包含无效路径");
                Path relative = Paths.get(entryName).normalize();
                if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() > maxDepth) {
                    result.skipped++;
                    addPackageError(result, entryName + "：路径越界或目录层级过深");
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String extension = ext(relative.getFileName().toString());
                if (isJsonPackage(relative.getFileName().toString())) {
                    result.workflowFiles++;
                    String json = readSmallZipText(zip, 5 * 1024 * 1024);
                    if (json != null && isRecognizedWorkflowPack(json)) {
                        result.workflowPacks.add(Map.of("name", relative.toString(), "content", json));
                    } else {
                        addPackageError(result, relative + "：不是可识别的工作流/Skill 包，已跳过");
                    }
                    zip.closeEntry();
                    continue;
                }
                if (!isSupportedExtension(extension)) {
                    result.skipped++;
                    addPackageError(result, relative + "：不是可识别的图片、视频或音频，已跳过");
                    zip.closeEntry();
                    continue;
                }
                Path target = staging.resolve(relative).normalize();
                if (!target.startsWith(staging)) throw new IllegalArgumentException("ZIP 包含越界路径，已拒绝导入");
                Files.createDirectories(target.getParent());
                copyZipEntry(zip, target);
                importPackagePath(result, target, entryName, role);
                zip.closeEntry();
            }
        } finally {
            deleteTree(staging);
        }
        return result;
    }

    private PackageImportResult preparePackageResult(PackageNameAudit audit, Long targetFolderId) {
        PackageImportResult result = new PackageImportResult();
        result.setPackageName(audit.getNormalizedName());
        result.setNameAudit(audit);
        if (targetFolderId == null) return result;
        MaterialFolder target = folderRepo.findById(targetFolderId)
                .orElseThrow(() -> new IllegalArgumentException("目标文件夹不存在"));
        if (Boolean.FALSE.equals(target.getEnabled())) throw new IllegalArgumentException("目标文件夹已停用");
        result.setPackageName(target.getName());
        result.setFolderId(target.getId());
        result.setExplicitTargetFolder(true);
        // Explicit user selection wins: keep every media type directly in the selected archive folder.
        result.setAudioFolderId(target.getId());
        result.setVideoFolderId(target.getId());
        result.setImageFolderId(target.getId());
        return result;
    }

    /** 仅在确认至少有一条合格媒体后创建或合并素材库，避免 JSON-only 包留下空文件夹。 */
    private void ensurePackageFolders(PackageImportResult result) throws IOException {
        if (result.getFolderId() != null) return;
        MaterialFolder folder = folderRepo.findFirstByNameIgnoreCaseAndParentIdIsNull(result.getNameAudit().getNormalizedName()).orElseGet(() -> {
            MaterialFolder created = new MaterialFolder();
            created.setName(result.getNameAudit().getNormalizedName());
            created.setEnabled(true);
            return folderRepo.save(created);
        });
        result.setPackageName(folder.getName());
        result.setFolderId(folder.getId());
        MaterialFolder audio = getOrCreatePackageChild(folder, "音频");
        MaterialFolder video = getOrCreatePackageChild(folder, "视频");
        MaterialFolder image = getOrCreatePackageChild(folder, "图片");
        result.setAudioFolderId(audio.getId());
        result.setVideoFolderId(video.getId());
        result.setImageFolderId(image.getId());
        Path root = props.materials().resolve("packages").resolve("folder-" + folder.getId()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("audio"));
        Files.createDirectories(root.resolve("video"));
        Files.createDirectories(root.resolve("image"));
        updateFolderPathIfUnset(folder, root);
        updateFolderPathIfUnset(audio, root.resolve("audio"));
        updateFolderPathIfUnset(video, root.resolve("video"));
        updateFolderPathIfUnset(image, root.resolve("image"));
    }

    private MaterialFolder getOrCreatePackageChild(MaterialFolder parent, String name) {
        return folderRepo.findFirstByNameIgnoreCaseAndParentId(name, parent.getId()).orElseGet(() -> {
            MaterialFolder child = new MaterialFolder();
            child.setName(name);
            child.setParentId(parent.getId());
            child.setEnabled(true);
            return folderRepo.save(child);
        });
    }

    private void updateFolderPathIfUnset(MaterialFolder folder, Path path) {
        if (folder.getPath() == null || folder.getPath().isBlank()) {
            folder.setPath(path.toString());
            folderRepo.save(folder);
        }
    }

    private void importPackageFile(PackageImportResult result, MultipartFile file, String rawRelative, MaterialRole role) {
        if (file == null || file.isEmpty()) { result.skipped++; addPackageError(result, "空文件：已跳过"); return; }
        String relative = safeRelativeName(rawRelative == null ? file.getOriginalFilename() : rawRelative);
        if (relative == null) { result.skipped++; addPackageError(result, "文件路径无效：已跳过"); return; }
        if (isJsonPackage(relative)) { result.workflowFiles++; addPackageError(result, relative + "：JSON 配置已跳过，请在工作流模块导入"); return; }
        String extension = ext(relative);
        if (!isSupportedExtension(extension)) { result.skipped++; addPackageError(result, relative + "：不是可识别的图片、视频或音频，已跳过"); return; }
        try {
            Path uploads = props.materials().resolve("uploads");
            Files.createDirectories(uploads);
            Path staging = Files.createTempFile(uploads, ".package-file-", ".tmp");
            file.transferTo(staging.toFile());
            importPackagePath(result, staging, relative, role);
            Files.deleteIfExists(staging);
        } catch (Exception e) {
            result.failed++;
            addPackageError(result, relative + "：" + conciseError(e));
        }
    }

    private void importPackagePath(PackageImportResult result, Path source, String relative, MaterialRole role) {
        try {
            ensurePackageFolders(result);
            String extension = ext(relative);
            Material.FileType fileType = extensionFileType(extension);
            boolean audio = fileType == Material.FileType.audio;
            boolean image = fileType == Material.FileType.image;
            Long folderId = image ? result.imageFolderId : (audio ? result.audioFolderId : result.videoFolderId);
            String typeDirectory = image ? "image" : (audio ? "audio" : "video");
            Path root = props.materials().resolve("packages").resolve("folder-" + result.folderId)
                    .resolve(result.isExplicitTargetFolder() ? "imported" : typeDirectory).toAbsolutePath().normalize();
            String stem = Integer.toHexString(relative.toLowerCase(Locale.ROOT).hashCode());
            Path target = root.resolve(stem + "_" + safe(Path.of(relative).getFileName().toString())).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("文件名包含越界路径");
            Files.createDirectories(root);
            if (!target.equals(source)) {
                if (!Files.exists(target)) {
                    try {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileSystemException unsupportedAtomicMove) {
                        Files.move(source, target);
                    }
                } else Files.deleteIfExists(source);
            }
            if (materialRepo.existsByFilePath(target.toString())) {
                result.skipped++;
                addPackageError(result, relative + "：同一总包路径已存在，已去重");
                return;
            }
            Material imported = register(target.toString(), folderId, role == null || role == MaterialRole.none, Material.Source.local, null);
            if (role != null && role != MaterialRole.none) {
                imported.setRole(role);
                materialRepo.save(imported);
            }
            if (image) result.imageImported++; else if (audio) result.audioImported++; else result.videoImported++;
        } catch (Exception e) {
            result.failed++;
            addPackageError(result, relative + "：" + conciseError(e));
        }
    }

    private String normalizePackageName(String raw) {
        // This is display-name trimming only. Path separators remain visible so local validation rejects them.
        return raw == null ? "" : raw.trim().replaceAll("[\\u0000-\\u001F]", "").trim();
    }

    private boolean isLegalPackageName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.equals(".") || name.equals("..")) return false;
        if (name.matches(".*[<>:\\\"|?*].*") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.endsWith(".") || name.endsWith(" ")) return false;
        String base = name.replaceAll("[ .]+$", "").toUpperCase(Locale.ROOT);
        return !Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3").contains(base);
    }

    private String localNameError(String name) {
        if (name == null || name.isBlank()) return "名称不能为空";
        if (name.length() > 80) return "名称不能超过 80 个字符";
        if (name.matches(".*[<>:\\\"|?*].*") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return "名称含 Windows 不允许的字符或路径分隔符";
        if (name.endsWith(".") || name.endsWith(" ")) return "名称不能以点或空格结尾";
        if (name.equals(".") || name.equals("..")) return "名称不能是路径保留段";
        return "名称包含系统保留字或非法路径意图";
    }

    private boolean isAudioOrVideo(String extension) { return AUDIO_EXT.contains(extension) || VIDEO_EXT.contains(extension); }
    private boolean isJsonPackage(String name) { String lower = name == null ? "" : name.toLowerCase(Locale.ROOT); return lower.endsWith(".json") || lower.endsWith(".mixcut-workflow") || lower.endsWith(".mixcut-skill"); }
    private String readSmallZipText(ZipInputStream zip, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) return null;
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
    private boolean isRecognizedWorkflowPack(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String format = node.path("format").asText("");
            return ("mixcut-workflow".equals(format) || "mixcut-skill".equals(format)) && node.path("schemaVersion").asInt(0) == 1;
        } catch (Exception e) { return false; }
    }
    private String safeRelativeName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.replace('\\', '/');
        Path path = Paths.get(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..")) return null;
        return path.toString().replace('\\', '/');
    }
    private void addPackageError(PackageImportResult result, String message) { if (result.errors.size() < 30) result.errors.add(message); }
    private void deleteTree(Path root) { if (root == null || !Files.exists(root)) return; try (var stream = Files.walk(root)) { stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignore) {} }); } catch (Exception ignore) {} }

    // ---------------- 上传导入 ----------------

    /**
     * Imports a ZIP that was explicitly selected by the user. Files are extracted only under the
     * application materials directory and every entry is checked before it can be registered.
     */
    public ScanResult importArchive(MultipartFile archive, MaterialRole role, Long folderId) throws Exception {
        String archiveName = archive.getOriginalFilename() == null ? "" : Path.of(archive.getOriginalFilename()).getFileName().toString();
        if (!"zip".equals(ext(archiveName))) {
            throw new IllegalArgumentException("目前只支持 ZIP 素材包；RAR/7Z 请先在本机解压后选择文件夹导入");
        }
        if (archive.isEmpty()) throw new IllegalArgumentException("ZIP 素材包为空或尚未完整保存");

        final int maxEntries = 2_000;
        final int maxDepth = 12;
        final int maxErrors = 20;
        ScanResult res = new ScanResult();
        Path uploads = props.materials().resolve("uploads");
        Files.createDirectories(uploads);
        Path root = Files.createTempDirectory(uploads, "archive-").toAbsolutePath().normalize();
        MaterialFolder folder = null;
        if (folderId != null) folder = folderRepo.findById(folderId).orElseThrow(() -> new IllegalArgumentException("目标文件夹不存在"));
        else {
            MaterialFolder existing = folderRepo.findByPath(root.toString()).orElse(null);
            if (existing == null) {
                existing = new MaterialFolder();
                existing.setName(stripExtension(archiveName));
                existing.setPath(root.toString());
                existing = folderRepo.save(existing);
            }
            folder = existing;
        }

        int entries = 0;
        try (InputStream raw = archive.getInputStream(); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IllegalArgumentException("ZIP 条目过多，最多允许 " + maxEntries + " 个文件");
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\u0000")) {
                    throw new IllegalArgumentException("ZIP 包含无效路径");
                }
                Path target = root.resolve(entryName).normalize();
                if (!target.startsWith(root)) throw new IllegalArgumentException("ZIP 包含越界路径，已拒绝导入");
                if (target.getNameCount() - root.getNameCount() > maxDepth) {
                    res.skipped++;
                    if (res.getErrors().size() < maxErrors) res.getErrors().add(entryName + ": 目录层级超过 " + maxDepth + " 层，已跳过");
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zip.closeEntry();
                    continue;
                }
                String extension = ext(target.getFileName().toString());
                if (!isSupportedExtension(extension)) {
                    res.skipped++;
                    if (isWechatEncryptedExtension(extension) && res.getErrors().size() < maxErrors) {
                        res.getErrors().add(target.getFileName() + ": 微信加密缓存文件，请先在微信中另存为正常媒体");
                    }
                    zip.closeEntry();
                    continue;
                }
                Files.createDirectories(target.getParent());
                copyZipEntry(zip, target);
                res.scanned++;
                try {
                    register(target.toString(), folder.getId(), role == null || role == MaterialRole.none, Material.Source.local, null);
                    if (role != null && role != MaterialRole.none) {
                        Material imported = materialRepo.findByFilePath(target.toAbsolutePath().toString()).orElse(null);
                        if (imported != null) { imported.setRole(role); materialRepo.save(imported); }
                    }
                    res.imported++;
                } catch (Exception error) {
                    res.failed++;
                    if (res.getErrors().size() < maxErrors) res.getErrors().add(target.getFileName() + ": " + conciseError(error));
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException securityViolation) {
            throw securityViolation;
        } catch (Exception error) {
            if (res.getErrors().isEmpty()) throw error;
            if (res.getErrors().size() < maxErrors) res.getErrors().add("ZIP 导入中断: " + conciseError(error));
        }
        return res;
    }

    private void copyZipEntry(ZipInputStream zip, Path target) throws IOException {
        long written = 0;
        long nextDiskCheck = 0;
        byte[] buffer = new byte[8192];
        ensureImportDiskSpace(target);
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = zip.read(buffer)) >= 0) {
                if (written >= nextDiskCheck) {
                    ensureImportDiskSpace(target);
                    nextDiskCheck = written + DISK_SPACE_CHECK_INTERVAL_BYTES;
                }
                output.write(buffer, 0, read);
                written += read;
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(target);
            throw error;
        }
    }

    private void ensureImportDiskSpace(Path target) throws IOException {
        Path probe = Files.exists(target) ? target : target.getParent();
        if (probe == null) throw new IOException("无法确认素材导入目录所在磁盘");
        if (Files.getFileStore(probe).getUsableSpace() < MIN_IMPORT_FREE_BYTES) {
            throw new IllegalArgumentException("应用数据盘剩余空间不足，已停止导入；请清理磁盘或调整 APP_DATA_DIR 后重试");
        }
    }

    public Material upload(MultipartFile file, MaterialRole role) throws Exception { return upload(file, role, null); }

    public Material upload(MultipartFile file, MaterialRole role, Long folderId) throws Exception {
        String name = file.getOriginalFilename() == null ? "upload" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String extension = ext(name);
        // Browser MIME and suffixes are only hints. Unknown suffixes are probed after landing
        // in the controlled uploads directory, except known encrypted WeChat cache formats.
        if (name.isBlank()) throw new IllegalArgumentException("上传文件名无效，请重新选择完整保存到本机的文件");
        if (isWechatEncryptedExtension(extension)) {
            throw new IllegalArgumentException("微信加密缓存文件无法直接导入，请在微信中另存为正常图片、视频或音频后重试");
        }
        Path dir = props.materials().resolve("uploads");
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".upload-", ".tmp");
        Path dst = dir.resolve(System.currentTimeMillis() + "_" + safe(name));
        try {
            file.transferTo(tmp.toFile());
            if (Files.size(tmp) <= 0) throw new IllegalArgumentException("上传文件为空");
            try {
                Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            Path normalized = dst.toAbsolutePath().normalize();
            if (!isSupportedExtension(extension) && detectMediaType(normalized) == null) {
                Files.deleteIfExists(normalized);
                throw new IllegalArgumentException("文件不是可识别的图片、视频或音频；请确认已从微信完整另存到本机后再导入");
            }
            Material m = createProcessingMaterial(normalized, role, folderId);
            attachBrowserUrls(m);
            try {
                mediaExecutor.execute(() -> processUploadedMaterial(m.getId(), normalized.toString(), role, folderId));
            } catch (RuntimeException unavailable) {
                // Keep the durable processing row and original file for the existing recovery flow.
                // A temporary executor outage must not turn a completed upload into a retry storm.
                log.warn("media processing dispatch deferred for {}: {}", m.getId(), unavailable.toString());
            }
            return m;
        } catch (Exception error) {
            Files.deleteIfExists(tmp);
            if (!materialRepo.existsByFilePath(dst.toAbsolutePath().toString())) Files.deleteIfExists(dst);
            throw error;
        }
    }

    private Material createProcessingMaterial(Path path, MaterialRole role, Long folderId) {
        String name = path.getFileName().toString();
        String extension = ext(name);
        Material.FileType detected = isSupportedExtension(extension) ? extensionFileType(extension) : detectMediaType(path);
        if (detected == null) throw new IllegalArgumentException("文件不是可识别的图片、视频或音频");
        Material material = new Material();
        material.setName(name);
        material.setFilePath(path.toString());
        material.setFolderId(folderId);
        material.setSource(Material.Source.local);
        material.setFileType(detected);
        material.setRole(role == null || role == MaterialRole.none ? guessRole(name, material.getFileType()) : role);
        material.setTags(guessTags(name));
        material.setStatus(Material.Status.processing);
        return materialRepo.save(material);
    }

    private void processUploadedMaterial(Long id, String path, MaterialRole requestedRole, Long folderId) {
        try {
            Material material = materialRepo.findById(id).orElse(null);
            if (material == null) return;
            FfmpegTool.MediaInfo info = ffmpeg.probe(path);
            Material.FileType detected = detectMediaType(Path.of(path));
            if (detected == Material.FileType.image) {
                material.setFileType(Material.FileType.image);
                material.setDurationSec(3.0);
                material.setWidth(info.getWidth());
                material.setHeight(info.getHeight());
                material.setStatus(info.isReadableImage() ? Material.Status.ready : Material.Status.failed);
            } else if (info.isHasAudio() || info.isHasVideo()) {
                material.setFileType(info.isHasVideo() ? Material.FileType.video : Material.FileType.audio);
                material.setDurationSec(info.getDuration());
                material.setWidth(info.getWidth());
                material.setHeight(info.getHeight());
                material.setStatus(info.getDuration() > 0 ? Material.Status.ready : Material.Status.failed);
            } else {
                material.setStatus(Material.Status.failed);
                log.warn("uploaded file has no readable audio/video stream: {}", path);
            }
            if (requestedRole != null && requestedRole != MaterialRole.none) material.setRole(requestedRole);
            else material.setRole(guessRole(material.getName(), material.getFileType()));
            if (folderId != null) material.setFolderId(folderId);
            applyQualityAdmission(material);
            materialRepo.save(material);
            if (material.getStatus() == Material.Status.ready) updateThumbnail(material);
        } catch (Exception e) {
            materialRepo.findById(id).ifPresent(material -> {
                material.setStatus(Material.Status.failed);
                materialRepo.save(material);
            });
            log.warn("uploaded media processing failed for {}: {}", id, e.toString());
        }
    }

    /**
     * Requeue interrupted asynchronous probes after a restart. Only database rows that
     * explicitly remain in processing are touched; registered materials and outputs are never removed.
     */
    public int recoverProcessingUploads() {
        int recovered = 0;
        for (Material material : materialRepo.findByStatus(Material.Status.processing)) {
            try {
                Path path = Paths.get(material.getFilePath()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    material.setStatus(Material.Status.failed);
                    materialRepo.save(material);
                    continue;
                }
                mediaExecutor.execute(() -> processUploadedMaterial(material.getId(), path.toString(), material.getRole(), material.getFolderId()));
                recovered++;
            } catch (RuntimeException queueFull) {
                log.warn("media recovery queue is full; leaving material {} in processing", material.getId());
                break;
            } catch (Exception e) {
                material.setStatus(Material.Status.failed);
                materialRepo.save(material);
                log.warn("unable to recover processing material {}: {}", material.getId(), e.toString());
            }
        }
        return recovered;
    }

    /** 抓取下来的文件登记入库 */
    public Material registerDownloaded(String absPath, String sourceUrl, MaterialRole role) {
        Material m = register(absPath, null, role == null || role == MaterialRole.none,
                Material.Source.crawl, sourceUrl);
        if (role != null && role != MaterialRole.none) {
            m.setRole(role);
            materialRepo.save(m);
        }
        return m;
    }

    /** 核心登记：probe + 自动打标 + 缩略图 */
    public Material register(String absPath, Long folderId, boolean autoRole,
                             Material.Source source, String sourceUrl) {
        return register(absPath, folderId, autoRole, source, sourceUrl, ProcessRegistry.CancellationContext.none());
    }

    public Material register(String absPath, Long folderId, boolean autoRole,
                             Material.Source source, String sourceUrl,
                             ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        Material exist = materialRepo.findByFilePath(absPath).orElse(null);
        if (exist != null) return exist;

        Path p = Paths.get(absPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) {
            throw new IllegalArgumentException("素材必须是可读取的普通文件，禁止符号链接");
        }
        String fname = p.getFileName().toString();
        String ext = ext(fname);
        if (!isSupportedExtension(ext)) {
            throw new IllegalArgumentException("不支持的素材格式: ." + (ext.isBlank() ? "未知" : ext));
        }

        Material m = new Material();
        m.setName(fname);
        m.setFilePath(p.toString());
        m.setFolderId(folderId);
        m.setSource(source == null ? Material.Source.local : source);
        m.setSourceUrl(sourceUrl);

        if (AUDIO_EXT.contains(ext)) m.setFileType(Material.FileType.audio);
        else if (IMAGE_EXT.contains(ext)) m.setFileType(Material.FileType.image);
        else m.setFileType(Material.FileType.video);

        FfmpegTool.MediaInfo info = context != null && context.isTracked()
                ? ffmpeg.probe(p.toString(), context)
                : ffmpeg.probe(p.toString());
        context.throwIfCancelled();
        if (m.getFileType() == Material.FileType.image) {
            // 图片在时间线上按静帧处理，默认 3 秒；ffprobe 负责确认尺寸/文件可读。
            m.setDurationSec(3.0);
            m.setWidth(info.getWidth());
            m.setHeight(info.getHeight());
            if (!info.isReadableImage()) m.setStatus(Material.Status.failed);
        } else {
            m.setDurationSec(info.getDuration());
            m.setWidth(info.getWidth());
            m.setHeight(info.getHeight());
            if (info.getDuration() <= 0) m.setStatus(Material.Status.failed);
        }

        MaterialRole role = autoRole ? guessRole(fname, m.getFileType()) : MaterialRole.none;
        m.setRole(role);
        m.setTags(guessTags(fname));
        applyQualityAdmission(m);

        context.throwIfCancelled();
        Material saved = materialRepo.save(m);
        context.throwIfCancelled();
        updateThumbnail(saved, context);
        context.throwIfCancelled();
        return saved;
    }

    private void updateThumbnail(Material m) {
        updateThumbnail(m, ProcessRegistry.CancellationContext.none());
    }

    private void updateThumbnail(Material m, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        if (m.getFileType() == Material.FileType.audio) return;
        if (m.getDurationSec() == null || m.getDurationSec() <= 0.5) return;
        try {
            Path th = props.thumbs().resolve("m" + m.getId() + ".jpg");
            double at = m.getFileType() == Material.FileType.image ? 0 : Math.min(1.0, m.getDurationSec() / 3);
            boolean thumbnailOk = context != null && context.isTracked()
                    ? ffmpeg.thumbnail(m.getFilePath(), th, at, context)
                    : ffmpeg.thumbnail(m.getFilePath(), th, at);
            if (thumbnailOk) {
                context.throwIfCancelled();
                m.setThumbnail("/files/thumbs/" + th.getFileName());
                // 质量闸门拒绝的素材保持 failed，缩略图成功不得把状态翻回可用。
                if (m.getStatus() != Material.Status.failed) {
                    m.setStatus(Material.Status.ready);
                    materialRepo.save(m);
                }
            } else if (m.getFileType() == Material.FileType.image) {
                // 原图已通过 ffprobe，缩略图失败只影响列表预览，不应阻止该图片进入渲染计划。
                if (m.getStatus() != Material.Status.failed) {
                    m.setStatus(Material.Status.ready);
                    materialRepo.save(m);
                }
                log.warn("image thumbnail generation failed, keeping material usable: {}", m.getFilePath());
            }
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("thumbnail generation failed for {}: {}", m.getName(), e.toString());
        }
    }

    /** 普通目录重扫只处理未就绪或缺失缩略图的素材，避免反复启动 ffmpeg。 */
    private boolean refreshExistingIfNeeded(String absPath, Long folderId, boolean autoRole) {
        Material m = materialRepo.findByFilePath(absPath).orElse(null);
        if (m == null) return false;
        if (m.getFileType() == Material.FileType.audio
                || (m.getStatus() == Material.Status.ready && thumbnailExistsOrNotRequired(m))) return false;
        refreshExisting(absPath, folderId, autoRole);
        return true;
    }

    private boolean thumbnailExistsOrNotRequired(Material m) {
        if (m.getFileType() == Material.FileType.audio) return true;
        if (m.getThumbnail() == null || m.getThumbnail().isBlank()) return false;
        return Files.exists(props.thumbs().resolve(Path.of(m.getThumbnail()).getFileName()));
    }

    /** 手动重新探测时强制刷新时长/宽高/角色/缩略图。 */
    private void refreshExisting(String absPath, Long folderId, boolean autoRole) {
        Path path = Paths.get(absPath).toAbsolutePath().normalize();
        Material m = materialRepo.findByFilePath(path.toString()).orElse(null);
        if (m == null) return;
        FfmpegTool.MediaInfo info = ffmpeg.probe(path.toString());
        if (m.getFileType() == Material.FileType.image) {
            m.setDurationSec(3.0);
            m.setWidth(info.getWidth());
            m.setHeight(info.getHeight());
            m.setStatus(info.isReadableImage() ? Material.Status.ready : Material.Status.failed);
        } else {
            m.setDurationSec(info.getDuration());
            m.setWidth(info.getWidth());
            m.setHeight(info.getHeight());
            m.setStatus(info.getDuration() > 0 ? Material.Status.ready : Material.Status.failed);
        }
        if (folderId != null) m.setFolderId(folderId);
        if (autoRole) {
            m.setRole(guessRole(Path.of(absPath).getFileName().toString(), m.getFileType()));
        }
        m.setTags(guessTags(Path.of(absPath).getFileName().toString()));
        // 重新探测时重跑质量准入：内容被替换/修复后会自动恢复可用并清除低质标记（自愈）。
        applyQualityAdmission(m);
        materialRepo.save(m);
        updateThumbnail(m);
    }

    private String conciseError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** 关键词猜角色；音频没命中就默认 bgm，视频没命中默认 body —— 保证永远能用上 */
    public MaterialRole guessRole(String fileName, Material.FileType type) {
        String n = fileName.toLowerCase();
        for (var e : ROLE_HINTS.entrySet()) {
            for (String k : e.getValue()) {
                if (n.contains(k.toLowerCase())) {
                    MaterialRole r = e.getKey();
                    // 音频不可能是 hook/product 画面，纠偏
                    if (type == Material.FileType.audio && r != MaterialRole.voice && r != MaterialRole.bgm) {
                        return MaterialRole.bgm;
                    }
                    if (type != Material.FileType.audio && (r == MaterialRole.voice || r == MaterialRole.bgm)) {
                        return MaterialRole.body;
                    }
                    return r;
                }
            }
        }
        return type == Material.FileType.audio ? MaterialRole.bgm : MaterialRole.body;
    }

    private String guessTags(String fileName) {
        String n = fileName.toLowerCase();
        List<String> tags = new ArrayList<>();
        if (n.contains("美妆") || n.contains("makeup")) tags.add("美妆");
        if (n.contains("护肤") || n.contains("skincare")) tags.add("护肤");
        if (n.contains("食品") || n.contains("food")) tags.add("食品");
        if (n.contains("竖屏") || n.contains("9x16") || n.contains("vertical")) tags.add("竖屏");
        return tags.isEmpty() ? null : String.join(",", tags);
    }

    // ---------------- 查询 / 维护 ----------------

    /**
     * 组合查询。刻意不写成一条带 null 判断的 JPQL —— Hibernate 对 enum 类型的
     * null 参数推断不稳，启动期校验就可能挂。分支派发 + 内存过滤更稳，
     * 素材量级（万级以内）也完全撑得住。
     */
    public List<Material> search(MaterialRole role, Material.FileType type, String kw) {
        List<Material> base;
        if (role != null && type != null) base = materialRepo.findByRoleAndFileType(role, type);
        else if (role != null) base = materialRepo.findByRole(role);
        else if (type != null) base = materialRepo.findByFileType(type);
        else base = materialRepo.findAllByOrderByIdDesc();

        base = filterEnabledFolders(base);
        if (kw == null || kw.isBlank()) {
            base.forEach(this::attachBrowserUrls);
            return base;
        }
        String k = kw.toLowerCase();
        List<Material> out = new ArrayList<>();
        for (Material m : base) {
            String n = m.getName() == null ? "" : m.getName().toLowerCase();
            String t = m.getTags() == null ? "" : m.getTags().toLowerCase();
            if (n.contains(k) || t.contains(k)) {
                attachBrowserUrls(m);
                out.add(m);
            }
        }
        return out;
    }

    private List<Material> filterEnabledFolders(List<Material> base) {
        if (base == null || base.isEmpty()) return base == null ? List.of() : base;
        Set<Long> disabled = new HashSet<>();
        for (MaterialFolder folder : folderRepo.findAll()) {
            if (Boolean.FALSE.equals(folder.getEnabled()) && folder.getId() != null) disabled.add(folder.getId());
        }
        if (disabled.isEmpty()) return base;
        return base.stream().filter(material -> material.getFolderId() == null || !disabled.contains(material.getFolderId())).toList();
    }

    private boolean isAppManagedPath(Path path) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        // Uploaded media is user-owned application input. Allow an explicit rescan to restore
        // metadata after a record-only removal, while excluding generated/cache directories.
        Path uploads = props.materials().resolve("uploads").toAbsolutePath().normalize();
        if (normalized.startsWith(uploads)) return false;
        for (Path root : List.of(props.downloads(), props.output(), props.cache(), props.slices(), props.thumbs(),
                props.materials().resolve("slices"))) {
            Path managed = root.toAbsolutePath().normalize();
            if (normalized.startsWith(managed)) return true;
        }
        return false;
    }

    /** 前端只能拿稳定 API URL，不允许从本机绝对路径推导资源位置。 */
    public void attachBrowserUrls(Material m) {
        if (m == null || m.getId() == null) return;
        if (m.getFileType() == Material.FileType.image || m.getFileType() == Material.FileType.video
                || m.getFileType() == Material.FileType.audio) {
            m.setPreviewUrl("/api/materials/" + m.getId() + "/preview");
        }
        if (m.getThumbnail() != null && !m.getThumbnail().isBlank()) {
            m.setThumbnailUrl("/files/thumbs/" + Path.of(m.getThumbnail()).getFileName());
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", materialRepo.count());
        m.put("video", materialRepo.countByFileType(Material.FileType.video));
        m.put("audio", materialRepo.countByFileType(Material.FileType.audio));
        m.put("image", materialRepo.countByFileType(Material.FileType.image));
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (MaterialRole r : MaterialRole.values()) {
            byRole.put(r.name(), materialRepo.countByRole(r));
        }
        m.put("byRole", byRole);
        return m;
    }

    public Material reprobe(Long id) {
        Material material = materialRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        refreshExisting(material.getFilePath(), material.getFolderId(), false);
        Material refreshed = materialRepo.findById(id).orElseThrow();
        attachBrowserUrls(refreshed);
        return refreshed;
    }

    public Material retryThumbnail(Long id) {
        Material material = materialRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        updateThumbnail(material);
        Material refreshed = materialRepo.findById(id).orElseThrow();
        attachBrowserUrls(refreshed);
        return refreshed;
    }

    /**
     * 将一个原视频切成可直接参与混剪的短片段。原素材不会移动、覆盖或删除。
     */
    public List<Material> splitVideo(Long id, double clipSec) {
        return splitVideo(id, clipSec, ProcessRegistry.CancellationContext.none());
    }

    public List<Material> splitVideo(Long id, double clipSec, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        Material source = materialRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        if (source.getFileType() != Material.FileType.video) throw new IllegalArgumentException("只有视频素材可以自动切片");
        if (source.getStatus() != Material.Status.ready) throw new IllegalArgumentException("素材尚未准备完成，请重新探测后再切片");
        if (clipSec < 1 || clipSec > 15) throw new IllegalArgumentException("每段时长请设置在 1 到 15 秒之间");

        FfmpegTool.MediaInfo info = ffmpeg.probe(source.getFilePath(), context);
        if (!info.isHasVideo() || info.getDuration() < 1) throw new IllegalArgumentException("无法读取原视频，请重新探测后再试");
        double duration = info.getDuration();
        int width = info.getWidth() > 0 ? info.getWidth() : 1080;
        int height = info.getHeight() > 0 ? info.getHeight() : 1920;
        double fps = info.getFps() >= 12 && info.getFps() <= 60 ? info.getFps() : 30;
        Path dir = props.mediaToolsOutput().resolve("clips");
        try { Files.createDirectories(dir); } catch (Exception e) { throw new IllegalStateException("无法创建切片目录", e); }

        List<Material> created = new ArrayList<>();
        List<Path> outputs = new ArrayList<>();
        int sequence = 1;
        try {
        for (double start = 0; start < duration - 0.15; start += clipSec, sequence++) {
            context.throwIfCancelled();
            double partDuration = Math.min(clipSec, duration - start);
            if (partDuration < 1) break;
            String stem = safe(stripExtension(source.getName()));
            Path output = dir.resolve(stem + "_切片_" + String.format(Locale.ROOT, "%03d", sequence) + "_" + System.currentTimeMillis() + ".mp4");
            if (!ffmpeg.cutNormalize(source.getFilePath(), start, partDuration, width, height, fps, output, context)) {
                log.warn("auto slice failed for material {} at {}s", id, start);
                continue;
            }
            outputs.add(output);
            context.throwIfCancelled();
            Material clip = register(output.toString(), source.getFolderId(), false, Material.Source.generated, null, context);
            clip.setRole(MaterialRole.body);
            clip.setTags(joinTags(source.getTags(), "自动切片"));
            materialRepo.save(clip);
            attachBrowserUrls(clip);
            context.throwIfCancelled();
            created.add(clip);
        }
        context.throwIfCancelled();
        if (created.isEmpty()) throw new IllegalStateException("自动切片失败，请检查 FFmpeg 是否可用以及原视频是否完整");
        return created;
        } catch (RuntimeException | Error e) {
            for (Path output : outputs) {
                try { Files.deleteIfExists(output); } catch (Exception cleanup) { log.debug("slice cleanup failed: {}", cleanup.toString()); }
            }
            throw e;
        }
    }

    private String stripExtension(String name) {
        if (name == null) return "video";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String joinTags(String existing, String extra) {
        if (existing == null || existing.isBlank()) return extra;
        return existing.contains(extra) ? existing : existing + "," + extra;
    }

    /**
     * 自动生产质量准入：对视频/音频执行质量闸门。
     * 拒绝 → 标记 failed（自动混剪候选池按现有规则跳过 failed 素材）并附可执行原因标签；
     * 通过 → 清除历史低质标记（内容修复后重新探测可自愈）。绝不删除或改写用户原文件。
     * 静态图片不参与硬性拒绝（产品图/人工导入仍可用，自动 B-roll 排除由规划层负责）。
     * 闸门自身异常 fail-open，不影响素材可用性。
     */
    private void applyQualityAdmission(Material m) {
        if (m == null || m.getFilePath() == null) return;
        if (m.getFileType() == Material.FileType.image) return;
        if (m.getStatus() == Material.Status.failed) return;
        try {
            MaterialDiagnosisService.QualityGateResult gate = diagnosisService.qualityGate(m);
            String clean = stripAdmissionTags(m.getTags());
            if (gate.isAdmitted()) {
                m.setTags(clean);
            } else {
                m.setStatus(Material.Status.failed);
                String reason = gate.getReasons().isEmpty() ? "质量准入未通过" : gate.getReasons().get(0);
                m.setTags(appendAdmissionTag(clean, reason));
                log.info("quality admission rejected material {} ({}): {}", m.getId(), m.getName(), reason);
            }
        } catch (Exception e) {
            log.warn("quality admission gate failed for {}: {}", m.getFilePath(), e.toString());
        }
    }

    private String stripAdmissionTags(String tags) {
        if (tags == null || tags.isBlank()) return tags;
        String kept = java.util.Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty() && !tag.startsWith("低质"))
                .collect(java.util.stream.Collectors.joining(","));
        return kept.isBlank() ? null : kept;
    }

    private String appendAdmissionTag(String tags, String reason) {
        String shortReason = reason == null ? "" : reason.replaceAll("\\s+", " ");
        if (shortReason.length() > 24) shortReason = shortReason.substring(0, 24) + "…";
        String tag = "低质:" + shortReason;
        if (tags == null || tags.isBlank()) return tag;
        return tags.contains(tag) ? tags : tags + "," + tag;
    }

    /** Persists metadata changes made by controlled local media services. */
    public Material save(Material material) {
        Material saved = materialRepo.save(material);
        attachBrowserUrls(saved);
        return saved;
    }

    /** 清理数据库里已经不存在的文件记录 */
    public int purgeMissing() {
        int n = 0;
        for (Material m : materialRepo.findAll()) {
            if (m.getFilePath() == null || !Files.exists(Paths.get(m.getFilePath()))) {
                materialRepo.delete(m);
                n++;
            }
        }
        return n;
    }

    public boolean isSupportedExtension(String extension) {
        return VIDEO_EXT.contains(extension) || AUDIO_EXT.contains(extension) || IMAGE_EXT.contains(extension);
    }

    private boolean isWechatEncryptedExtension(String extension) {
        return "dat".equals(extension) || "silk".equals(extension);
    }

    private Material.FileType extensionFileType(String extension) {
        if (AUDIO_EXT.contains(extension)) return Material.FileType.audio;
        if (IMAGE_EXT.contains(extension)) return Material.FileType.image;
        return VIDEO_EXT.contains(extension) ? Material.FileType.video : null;
    }

    /** Uses a local image read and FFprobe only after a file is inside the controlled uploads directory. */
    private Material.FileType detectMediaType(Path path) {
        try {
            if (javax.imageio.ImageIO.read(path.toFile()) != null) return Material.FileType.image;
        } catch (IOException ignored) {
            // Not a readable image; FFprobe below can still identify audio or video.
        }
        FfmpegTool.MediaInfo info = ffmpeg.probe(path.toString());
        if (info.isHasVideo()) return Material.FileType.video;
        return info.isHasAudio() ? Material.FileType.audio : null;
    }

    private String ext(String f) {
        int i = f.lastIndexOf('.');
        return i > 0 ? f.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String safe(String value) {
        String cleaned = value == null ? "material" : value.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]+", "_");
        cleaned = cleaned.replaceAll("[. ]+$", "").trim();
        if (cleaned.isBlank()) cleaned = "material";
        String stem = cleaned.contains(".") ? cleaned.substring(0, cleaned.lastIndexOf('.')) : cleaned;
        if (stem.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")) cleaned = "_" + cleaned;
        return cleaned.length() <= 180 ? cleaned : cleaned.substring(0, 180);
    }
}
