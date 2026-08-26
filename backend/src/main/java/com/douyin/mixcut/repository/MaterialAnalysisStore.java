package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.mapper.MaterialAnalysisMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MaterialAnalysisStore {

    private final MaterialAnalysisMapper mapper;

    public Optional<MaterialAnalysis> findByMaterialId(Long materialId) {
        return mapper.selectList(query()
                .eq(MaterialAnalysis::getMaterialId, materialId)
                .orderByDesc(MaterialAnalysis::getId)
                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public List<MaterialAnalysis> findAllByMaterialId(Long materialId) {
        return mapper.selectList(query()
                .eq(MaterialAnalysis::getMaterialId, materialId)
                .orderByDesc(MaterialAnalysis::getId));
    }

    public List<MaterialAnalysis> findByStatusBefore(String status, LocalDateTime updatedBefore) {
        return mapper.selectList(query()
                .eq(MaterialAnalysis::getStatus, status)
                .lt(MaterialAnalysis::getUpdatedAt, updatedBefore));
    }

    public MaterialAnalysis save(MaterialAnalysis analysis) {
        if (analysis.getId() == null) {
            if (analysis.getCreatedAt() == null) analysis.setCreatedAt(LocalDateTime.now());
            if (analysis.getUpdatedAt() == null) analysis.setUpdatedAt(LocalDateTime.now());
            mapper.insert(analysis);
        } else {
            analysis.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(analysis);
        }
        return analysis;
    }

    public void deleteByMaterialId(Long materialId) {
        mapper.delete(query().eq(MaterialAnalysis::getMaterialId, materialId));
    }

    private LambdaQueryWrapper<MaterialAnalysis> query() {
        return new LambdaQueryWrapper<>();
    }
}
