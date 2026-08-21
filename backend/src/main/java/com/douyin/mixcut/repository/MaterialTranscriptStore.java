package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.MaterialTranscript;
import com.douyin.mixcut.mapper.MaterialTranscriptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MaterialTranscriptStore {

    private final MaterialTranscriptMapper mapper;

    public Optional<MaterialTranscript> findByMaterialId(Long materialId) {
        return mapper.selectList(query()
                .eq(MaterialTranscript::getMaterialId, materialId)
                .orderByDesc(MaterialTranscript::getId)
                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public List<MaterialTranscript> findAllByMaterialId(Long materialId) {
        return mapper.selectList(query()
                .eq(MaterialTranscript::getMaterialId, materialId)
                .orderByDesc(MaterialTranscript::getId));
    }

    public MaterialTranscript save(MaterialTranscript transcript) {
        if (transcript.getId() == null) {
            if (transcript.getCreatedAt() == null) transcript.setCreatedAt(LocalDateTime.now());
            if (transcript.getUpdatedAt() == null) transcript.setUpdatedAt(LocalDateTime.now());
            mapper.insert(transcript);
        } else {
            transcript.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(transcript);
        }
        return transcript;
    }

    public void deleteByMaterialId(Long materialId) {
        mapper.delete(query().eq(MaterialTranscript::getMaterialId, materialId));
    }

    private LambdaQueryWrapper<MaterialTranscript> query() {
        return new LambdaQueryWrapper<>();
    }
}
