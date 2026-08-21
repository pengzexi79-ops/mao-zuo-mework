package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.OutputEditSession;
import com.douyin.mixcut.domain.OutputVersion;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.Repositories.OutputEditSessionRepo;
import com.douyin.mixcut.repository.Repositories.OutputVersionRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists asynchronous editor work in transactions independent from the render worker. */
@Service
@RequiredArgsConstructor
class OutputEditorPersistenceService {
    private final OutputEditSessionRepo sessionRepo;
    private final OutputVersionRepo versionRepo;
    private final ObjectMapper om;

    @Transactional
    void persistCandidate(Long sessionId, MixPlanner.Plan plan, MixParams params, RenderService.RenderResult result) {
        OutputEditSession session = session(sessionId);
        int versionNo = versionRepo.findTopByJobIdAndIdxOrderByVersionNoDesc(session.getJobId(), session.getIdx())
                .map(v -> (v.getVersionNo() == null ? 0 : v.getVersionNo()) + 1).orElse(1);
        OutputVersion version = new OutputVersion();
        version.setJobId(session.getJobId());
        version.setIdx(session.getIdx());
        version.setVersionNo(versionNo);
        version.setParentVersionNo(session.getBaseVersionId() == null ? null : baseVersionNo(session.getBaseVersionId()));
        version.setStatus(result != null && result.isOk() ? "passed" : "qc_failed");
        version.setFilePath(result == null ? null : result.getFilePath());
        version.setDurationSec(result == null ? null : result.getDurationSec());
        version.setThumbnail(result == null ? null : result.getThumbnail());
        version.setPlanSnapshot(write(plan));
        version.setParamsSnapshot(write(params));
        version.setUsedMaterials(write(OutputEditorService.usedMaterials(plan)));
        version.setRepairStrategy("editor-candidate");
        version.setQcJson(result == null ? null : result.getQcJson());
        version.setQcReport(result == null ? null : result.getQcReport());
        version.setError(result == null ? "候选渲染未返回结果" : result.getError());
        OutputVersion saved = versionRepo.save(version);
        session.setCandidateVersionId(saved.getId());
        session.setStatus(result != null && result.isOk() ? "passed" : "qc_failed");
        session.setError(result != null && result.isOk() ? null : (result == null ? "候选渲染失败" : result.getError()));
        sessionRepo.save(session);
    }

    @Transactional
    void failSession(Long sessionId, String error) {
        OutputEditSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) return;
        session.setStatus("failed");
        session.setError(limit(error, 1200));
        sessionRepo.save(session);
    }

    private OutputEditSession session(Long id) {
        return sessionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("编辑会话不存在"));
    }

    private Integer baseVersionNo(Long id) {
        return versionRepo.findById(id).map(OutputVersion::getVersionNo).orElse(null);
    }

    private String write(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存编辑快照", e);
        }
    }

    private String limit(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
}
