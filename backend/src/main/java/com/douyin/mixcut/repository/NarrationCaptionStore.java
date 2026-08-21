package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.NarrationCaption;
import com.douyin.mixcut.mapper.NarrationCaptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NarrationCaptionStore {

    private final NarrationCaptionMapper mapper;

    public Optional<NarrationCaption> findByJobIdAndIdx(Long jobId, int idx) {
        return mapper.selectList(query()
                .eq(NarrationCaption::getJobId, jobId)
                .eq(NarrationCaption::getIdx, idx)
                .orderByDesc(NarrationCaption::getId)
                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public java.util.List<String> scriptsByJobId(Long jobId) {
        if (jobId == null) return java.util.List.of();
        return mapper.selectList(query().eq(NarrationCaption::getJobId, jobId))
                .stream().map(NarrationCaption::getScriptText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public NarrationCaption save(NarrationCaption caption) {
        if (caption.getId() == null) {
            if (caption.getCreatedAt() == null) caption.setCreatedAt(LocalDateTime.now());
            if (caption.getUpdatedAt() == null) caption.setUpdatedAt(LocalDateTime.now());
            mapper.insert(caption);
        } else {
            caption.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(caption);
        }
        return caption;
    }

    public void deleteByJobId(Long jobId) {
        mapper.delete(query().eq(NarrationCaption::getJobId, jobId));
    }

    private LambdaQueryWrapper<NarrationCaption> query() {
        return new LambdaQueryWrapper<>();
    }
}
