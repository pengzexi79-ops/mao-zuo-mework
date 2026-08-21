package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialFolder;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.service.AudioEngineService;
import com.douyin.mixcut.service.MaterialService;
import com.douyin.mixcut.service.MaterialDiagnosisService;
import com.douyin.mixcut.service.TtsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService service;
    private final MaterialDiagnosisService diagnosisService;
    private final TtsService ttsService;
    private final AudioEngineService audioEngine;
    private final MaterialStore repo;
    private final MaterialFolderRepo folderRepo;

    @GetMapping
    public R<List<Material>> list(@RequestParam(required = false) String role,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String kw,
                                  @RequestParam(required = false) Long folderId) {
        MaterialRole r = parseRole(role);
        Material.FileType t = parseType(type);
        List<Material> materials = service.search(r, t, kw);
        if (folderId != null) {
            var acceptedFolderIds = new HashSet<Long>();
            acceptedFolderIds.add(folderId);
            folderRepo.findAll().stream()
                    .filter(folder -> folderId.equals(folder.getParentId()))
                    .map(MaterialFolder::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(acceptedFolderIds::add);
            materials = materials.stream().filter(m -> acceptedFolderIds.contains(m.getFolderId())).toList();
        }
        return R.ok(materials);
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(service.stats());
    }

    /** 稳定预览入口：用素材 ID 映射文件，直接输出字节避免本机 MVC 转换器差异。 */
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id,
                        @RequestHeader(value = "Range", required = false) String rangeHeader,
                        HttpServletResponse response) throws java.io.IOException {
        Material material = repo.findById(id).orElse(null);
        if (material == null || (material.getFileType() != Material.FileType.image
                && material.getFileType() != Material.FileType.video
                && material.getFileType() != Material.FileType.audio)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        try {
            Path path = Path.of(material.getFilePath()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                response.sendError(HttpStatus.NOT_FOUND.value());
                return;
            }
            long size = Files.size(path);
            String filename = path.getFileName().toString();
            MediaType mediaType = MediaTypeFactory.getMediaType(filename)
                    .orElseGet(() -> fallbackMediaType(filename));
            if (!Set.of("image", "video", "audio").contains(mediaType.getType())) {
                mediaType = switch (material.getFileType()) {
                    case image -> detectedImageMediaType(path);
                    case audio -> audioMediaType(filename);
                    default -> MediaType.parseMediaType("video/mp4");
                };
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(1)).cachePublic().getHeaderValue());
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setContentType(mediaType.toString());
            long start = 0;
            long end = Math.max(0, size - 1);
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1) {
                    writeRangeNotSatisfiable(response, size);
                    return;
                }
                HttpRange range = ranges.get(0);
                start = range.getRangeStart(size);
                end = range.getRangeEnd(size);
                if (start < 0 || start >= size || end < start) {
                    writeRangeNotSatisfiable(response, size);
                    return;
                }
                end = Math.min(end, size - 1);
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            }
            long length = end - start + 1;
            response.setContentLengthLong(length);
            copyRange(path, start, length, response.getOutputStream());
        } catch (IllegalArgumentException e) {
            writeRangeNotSatisfiable(response, 0);
        } catch (Exception e) {
            if (!response.isCommitted()) response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }

    private void writeRangeNotSatisfiable(HttpServletResponse response, long size) {
        response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(0, size));
    }

    private void copyRange(Path path, long start, long length, java.io.OutputStream output) throws java.io.IOException {
        try (InputStream input = Files.newInputStream(path)) {
            input.skipNBytes(start);
            byte[] buffer = new byte[32 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private MediaType audioMediaType(String filename) {
        return switch (extension(filename)) {
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "ogg", "oga" -> MediaType.parseMediaType("audio/ogg");
            case "opus" -> MediaType.parseMediaType("audio/opus");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "aac" -> MediaType.parseMediaType("audio/aac");
            case "m4a", "alac" -> MediaType.parseMediaType("audio/mp4");
            case "webm" -> MediaType.parseMediaType("audio/webm");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    @GetMapping("/folders")
    public R<List<MaterialFolder>> folders() { return R.ok(folderRepo.findAllByOrderBySortOrderAscIdAsc()); }

    @Data public static class FolderReq { private String name; private String description; private Boolean enabled; private Integer sortOrder; }
    @PostMapping("/folders")
    public R<MaterialFolder> createFolder(@RequestBody FolderReq req) {
        if (req.getName() == null || req.getName().isBlank()) return R.fail("请填写文件夹名称");
        MaterialFolder folder = new MaterialFolder(); folder.setName(req.getName().trim()); folder.setDescription(req.getDescription()); folder.setEnabled(req.getEnabled() == null || req.getEnabled()); folder.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        return R.ok(folderRepo.save(folder));
    }
    @PutMapping("/folders/{id}")
    public R<MaterialFolder> updateFolder(@PathVariable Long id, @RequestBody FolderReq req) {
        MaterialFolder folder = folderRepo.findById(id).orElse(null); if (folder == null) return R.fail("文件夹不存在");
        if (req.getName() != null && !req.getName().isBlank()) folder.setName(req.getName().trim()); if (req.getDescription() != null) folder.setDescription(req.getDescription()); if (req.getEnabled() != null) folder.setEnabled(req.getEnabled()); if (req.getSortOrder() != null) folder.setSortOrder(req.getSortOrder());
        return R.ok(folderRepo.save(folder));
    }
    @DeleteMapping("/folders/{id}")
    public R<Void> deleteFolder(@PathVariable Long id) {
        if (folderRepo.findById(id).isEmpty()) return R.fail("文件夹不存在");
        if (repo.countByFolderId(id) > 0) return R.fail("文件夹仍有关联素材，请先移动素材后再删除");
        if (folderRepo.findAll().stream().anyMatch(folder -> id.equals(folder.getParentId()))) {
            return R.fail("文件夹仍有音频/视频子分类，请先移走素材后再删除");
        }
        folderRepo.deleteById(id); return R.ok();
    }

    @Data public static class MoveReq { private Long folderId; }
    @PostMapping("/{id}/move")
    public R<Material> move(@PathVariable Long id, @RequestBody MoveReq req) {
        Material material = repo.findById(id).orElse(null); if (material == null) return R.fail("素材不存在");
        if (req.getFolderId() != null && folderRepo.findById(req.getFolderId()).isEmpty()) return R.fail("目标文件夹不存在");
        material.setFolderId(req.getFolderId()); return R.ok(repo.save(material));
    }

    @Data
    public static class ScanReq {
        private String path;
        private Boolean autoRole = true;
    }

    /** 扫描本机目录导入 —— "调用所有电脑里面的素材"的入口 */
    @PostMapping("/scan")
    public R<MaterialService.ScanResult> scan(@RequestBody ScanReq req) {
        if (req.getPath() == null || req.getPath().isBlank()) return R.fail("请填写目录路径");
        return R.ok(service.scanFolder(req.getPath(), !Boolean.FALSE.equals(req.getAutoRole())));
    }

    @PostMapping("/upload")
    public R<Material> upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) Long folderId) throws Exception {
        if (file == null || file.isEmpty()) return R.fail("文件为空、仍在下载或无法从来源窗口读取；请先完整保存到本机后重试");
        if (folderId != null && folderRepo.findById(folderId).isEmpty()) return R.fail("目标文件夹不存在");
        return R.ok(service.upload(file, parseRole(role), folderId));
    }

    /** Imports a user-selected ZIP under the application-controlled materials directory. */
    @PostMapping("/import-archive")
    public R<MaterialService.ScanResult> importArchive(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(required = false) String role,
                                                        @RequestParam(required = false) Long folderId) throws Exception {
        if (file == null || file.isEmpty()) return R.fail("ZIP 素材包为空、仍在下载或无法读取");
        if (folderId != null && folderRepo.findById(folderId).isEmpty()) return R.fail("目标文件夹不存在");
        return R.ok(service.importArchive(file, parseRole(role), folderId));
    }

    /** 总包名称预检：不会创建文件夹，也不会写入素材。 */
    @GetMapping("/package-name-audit")
    public R<MaterialService.PackageNameAudit> auditPackageName(@RequestParam String name) {
        return R.ok(service.auditPackageName(name));
    }

    /** 浏览器文件夹总包导入；relativePaths 与 files 按同一顺序对应。 */
    @PostMapping("/import-package")
    public R<MaterialService.PackageImportResult> importPackage(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("packageName") String packageName,
            @RequestParam(required = false, name = "relativePaths") List<String> relativePaths,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long folderId) throws Exception {
        if (files == null || files.isEmpty()) return R.fail("没有收到文件夹中的可读文件");
        if (folderId != null && folderRepo.findById(folderId).isEmpty()) return R.fail("目标文件夹不存在");
        return R.ok(service.importPackage(packageName, files, relativePaths, parseRole(role), folderId));
    }

    /** ZIP 总包导入；不接受 RAR/7Z，不执行包内脚本。 */
    @PostMapping("/import-package-archive")
    public R<MaterialService.PackageImportResult> importPackageArchive(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String packageName,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long folderId) throws Exception {
        if (file == null || file.isEmpty()) return R.fail("ZIP 素材包为空、仍在下载或无法读取");
        if (folderId != null && folderRepo.findById(folderId).isEmpty()) return R.fail("目标文件夹不存在");
        return R.ok(service.importPackageArchive(file, packageName, parseRole(role), folderId));
    }

    @Data
    public static class UpdateReq {
        private String role;
        private String tags;
        private String name;
        private Boolean muteOriginalAudio;
        private Boolean transcribeForSubtitles;
    }

    @PutMapping("/{id}")
    public R<Material> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        Material m = repo.findById(id).orElse(null);
        if (m == null) return R.fail("素材不存在");
        if (req.getRole() != null) {
            MaterialRole r = parseRole(req.getRole());
            if (r != null) m.setRole(r);
        }
        if (req.getTags() != null) m.setTags(req.getTags());
        if (req.getName() != null && !req.getName().isBlank()) m.setName(req.getName());
        if (req.getMuteOriginalAudio() != null) m.setMuteOriginalAudio(req.getMuteOriginalAudio());
        if (req.getTranscribeForSubtitles() != null) m.setTranscribeForSubtitles(req.getTranscribeForSubtitles());
        return R.ok(repo.save(m));
    }

    @Data
    public static class BatchRoleReq {
        private List<Long> ids;
        private String role;
    }

    /** 批量改角色：客户导入几百条素材后靠这个 30 秒打完标 */
    @PostMapping("/batch-role")
    public R<Integer> batchRole(@RequestBody BatchRoleReq req) {
        MaterialRole r = parseRole(req.getRole());
        if (r == null) return R.fail("角色无效");
        if (req.getIds() == null || req.getIds().isEmpty()) return R.fail("未选择素材");
        int n = 0;
        for (Long id : req.getIds()) {
            Material m = repo.findById(id).orElse(null);
            if (m != null) {
                m.setRole(r);
                repo.save(m);
                n++;
            }
        }
        return R.ok(n);
    }

    @Data
    public static class BatchDeleteReq {
        private List<Long> ids;
    }

    /** 删除素材库记录，不删除用户本机的原始媒体文件。 */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/batch-delete")
    public R<Integer> batchDelete(@RequestBody BatchDeleteReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) return R.fail("未选择素材");
        int deleted = 0;
        for (Long id : req.getIds()) {
            if (id != null && repo.findById(id).isPresent()) {
                repo.deleteById(id);
                deleted++;
            }
        }
        return R.ok(deleted);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return R.ok();
    }

    @PostMapping("/{id}/reprobe")
    public R<Material> reprobe(@PathVariable Long id) {
        return R.ok(service.reprobe(id));
    }

    @PostMapping("/{id}/thumbnail/retry")
    public R<Material> retryThumbnail(@PathVariable Long id) {
        return R.ok(service.retryThumbnail(id));
    }

    /** 对单条素材做本机 OCR、音频和基础交付风险检查。 */
    @PostMapping("/{id}/diagnose")
    public R<MaterialDiagnosisService.Diagnosis> diagnose(@PathVariable Long id) {
        return R.ok(diagnosisService.inspect(id));
    }

    /** 重新对单条素材执行语音识别，忽略已有缓存结果。 */
    @PostMapping("/{id}/transcribe/retry")
    public R<MaterialDiagnosisService.Diagnosis> retryTranscription(@PathVariable Long id) {
        Material material = repo.findById(id).orElse(null);
        if (material == null) return R.fail("素材不存在");
        if (!Boolean.TRUE.equals(material.getTranscribeForSubtitles())) {
            return R.fail("请先明确开启“转录字幕”，再执行语音识别");
        }
        return R.ok(diagnosisService.retryTranscription(id));
    }

    /** 批量更新素材的静音原声和字幕转录授权标志。 */
    @Data
    public static class BatchFlagReq {
        private List<Long> ids;
        private Boolean muteOriginalAudio;
        private Boolean transcribeForSubtitles;
    }

    @PostMapping("/batch-flags")
    public R<Integer> batchFlags(@RequestBody BatchFlagReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) return R.fail("未选择素材");
        int n = 0;
        for (Long id : req.getIds()) {
            Material m = repo.findById(id).orElse(null);
            if (m != null) {
                if (req.getMuteOriginalAudio() != null) m.setMuteOriginalAudio(req.getMuteOriginalAudio());
                if (req.getTranscribeForSubtitles() != null) m.setTranscribeForSubtitles(req.getTranscribeForSubtitles());
                repo.save(m);
                n++;
            }
        }
        return R.ok(n);
    }

    @Data
    public static class TtsReq { private String text; private String voice; }

    /** 生成经过时长和静音检查的本机神经配音，并作为人声素材入库。 */
    @PostMapping("/tts")
    public R<Material> tts(@RequestBody TtsReq req) {
        return R.ok(ttsService.synthesize(req == null ? null : req.getText(), req == null ? null : req.getVoice()));
    }

    /** 当前统一音频引擎状态：只汇报现有能力，不触发安装或模型下载。 */
    @GetMapping("/audio-engine/status")
    public R<AudioEngineService.EngineStatus> audioStatus() {
        return R.ok(audioEngine.status());
    }

    /** 对已有素材执行人声/伴奏分离，输出作为可管理音频素材入库。 */
    @PostMapping("/{id}/audio/separate")
    public R<AudioEngineService.SeparationResult> separateAudio(@PathVariable Long id) {
        return R.ok(audioEngine.separateMaterial(id));
    }

    @Data
    public static class SplitReq { private Double clipSec = 3.0; }

    /** 原视频自动分成短片段，切出的新素材自动进入素材库。 */
    @PostMapping("/{id}/split")
    public R<List<Material>> split(@PathVariable Long id, @RequestBody(required = false) SplitReq req) {
        double clipSec = req == null || req.getClipSec() == null ? 3.0 : req.getClipSec();
        return R.ok(service.splitVideo(id, clipSec));
    }

    @PostMapping("/purge-missing")
    public R<Integer> purge() {
        return R.ok(service.purgeMissing());
    }

    private MediaType detectedImageMediaType(Path path) {
        try (var input = Files.newInputStream(path);
             var imageInput = javax.imageio.ImageIO.createImageInputStream(input)) {
            var readers = javax.imageio.ImageIO.getImageReaders(imageInput);
            if (readers.hasNext()) {
                String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
                return switch (format) {
                    case "png" -> MediaType.IMAGE_PNG;
                    case "gif" -> MediaType.IMAGE_GIF;
                    case "bmp" -> MediaType.parseMediaType("image/bmp");
                    case "webp" -> MediaType.parseMediaType("image/webp");
                    default -> MediaType.IMAGE_JPEG;
                };
            }
        } catch (Exception ignored) {
            // The material was already verified during import; use a broadly supported fallback.
        }
        return MediaType.IMAGE_JPEG;
    }

    private MediaType fallbackMediaType(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "mkv" -> MediaType.parseMediaType("video/x-matroska");
            case "flv" -> MediaType.parseMediaType("video/x-flv");
            case "ts", "mts", "m2ts" -> MediaType.parseMediaType("video/mp2t");
            case "wmv" -> MediaType.parseMediaType("video/x-ms-wmv");
            case "m4v" -> MediaType.parseMediaType("video/x-m4v");
            case "ogv" -> MediaType.parseMediaType("video/ogg");
            case "gif" -> MediaType.IMAGE_GIF;
            case "avif" -> MediaType.parseMediaType("image/avif");
            case "tif", "tiff" -> MediaType.parseMediaType("image/tiff");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private MaterialRole parseRole(String s) {
        if (s == null || s.isBlank() || "all".equalsIgnoreCase(s)) return null;
        try {
            return MaterialRole.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Material.FileType parseType(String s) {
        if (s == null || s.isBlank() || "all".equalsIgnoreCase(s)) return null;
        try {
            return Material.FileType.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }
}
