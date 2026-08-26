package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.EditorialBrief;
import com.douyin.mixcut.mapper.EditorialBriefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EditorialBriefStore {

    private final EditorialBriefMapper mapper;

    public Optional<EditorialBrief> findByJobId(Long jobId) {
        return mapper.selectList(query()
                .eq(EditorialBrief::getJobId, jobId)
                .orderByDesc(EditorialBrief::getId)
                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public EditorialBrief save(EditorialBrief brief) {
        if (brief.getId() == null) {
            if (brief.getCreatedAt() == null) brief.setCreatedAt(LocalDateTime.now());
            if (brief.getUpdatedAt() == null) brief.setUpdatedAt(LocalDateTime.now());
            mapper.insert(brief);
        } else {
            brief.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(brief);
        }
        return brief;
    }

    public void deleteByJobId(Long jobId) {
        mapper.delete(query().eq(EditorialBrief::getJobId, jobId));
    }

    private LambdaQueryWrapper<EditorialBrief> query() {
        return new LambdaQueryWrapper<>();
    }
}
