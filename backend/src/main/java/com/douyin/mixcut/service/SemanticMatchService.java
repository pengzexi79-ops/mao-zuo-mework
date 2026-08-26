package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** Repository adapter for deterministic semantic evidence; analysis failures remain fallbacks. */
@Service
@RequiredArgsConstructor
public class SemanticMatchService {
    private final MaterialAnalysisStore analysisStore;
    private final MaterialSegmentStore segmentStore;

    public SemanticEvidenceMatcher.Result match(Project project, List<Material> materials,
                                                 String slot, boolean strictDelivery) {
        Map<Long, MaterialAnalysis> analyses = new HashMap<>();
        Map<Long, List<MaterialSegment>> segments = new HashMap<>();
        if (materials != null) for (Material material : materials) {
            if (material == null || material.getId() == null) continue;
            analysisStore.findByMaterialId(material.getId()).ifPresent(a -> {
                analyses.put(material.getId(), a);
                segments.put(material.getId(), segmentStore.findByMaterialId(material.getId()));
            });
        }
        return SemanticEvidenceMatcher.match(project, materials, analyses, segments, slot, strictDelivery);
    }

    public SemanticEvidenceMatcher.Result matchInMemory(Project project, List<Material> materials,
                                                         Map<Long, MaterialAnalysis> analyses,
                                                         Map<Long, List<MaterialSegment>> segments,
                                                         String slot, boolean strictDelivery) {
        return SemanticEvidenceMatcher.match(project, materials, analyses, segments, slot, strictDelivery);
    }
}
