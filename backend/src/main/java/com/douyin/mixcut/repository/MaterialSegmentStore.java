package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.mapper.MaterialSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MaterialSegmentStore {

    private final MaterialSegmentMapper mapper;

    public List<MaterialSegment> findByMaterialId(Long materialId) {
        return mapper.selectList(query()
                .eq(MaterialSegment::getMaterialId, materialId)
                .orderByAsc(MaterialSegment::getIdx));
    }

    public List<MaterialSegment> findByAnalysisId(Long analysisId) {
        return mapper.selectList(query()
                .eq(MaterialSegment::getAnalysisId, analysisId)
                .orderByAsc(MaterialSegment::getIdx));
    }

    public void insertBatch(List<MaterialSegment> segments) {
        LocalDateTime now = LocalDateTime.now();
        for (MaterialSegment segment : segments) {
            if (segment.getCreatedAt() == null) segment.setCreatedAt(now);
            mapper.insert(segment);
        }
    }

    public void deleteByMaterialId(Long materialId) {
        mapper.delete(query().eq(MaterialSegment::getMaterialId, materialId));
    }

    public void deleteByAnalysisId(Long analysisId) {
        mapper.delete(query().eq(MaterialSegment::getAnalysisId, analysisId));
    }

    private LambdaQueryWrapper<MaterialSegment> query() {
        return new LambdaQueryWrapper<>();
    }
}
