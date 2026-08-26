package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SemanticEvidenceMatcherTest {
    @Test
    void completedSceneWithRoleOrStructuredTextMatches() {
        Material m = material(1L, "filename-only.mp4", MaterialRole.product);
        MaterialAnalysis a = analysis(1L, "completed", "scene");
        a.setTagsJson("[\"护肤\"]");
        MaterialSegment s = segment(1L, 0, 0, 3);
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(project("护肤"), List.of(m),
                Map.of(1L, a), Map.of(1L, List.of(s)), "product", false);
        assertEquals(1, result.matched().size());
        assertEquals(SemanticEvidenceMatcher.Status.matched, result.matched().get(0).status());
        assertEquals(0.0, result.matched().get(0).startSec());
    }

    @Test
    void missingAnalysisFallsBackAndDoesNotBlock() {
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(project("x"), List.of(material(1L, "x.mp4", MaterialRole.body)),
                Map.of(), Map.of(), "body", true);
        assertTrue(result.matched().isEmpty());
        assertEquals(1, result.fallback().size());
        assertTrue(result.blockers().isEmpty());
    }

    @Test
    void invalidSegmentFallsBack() {
        Material m = material(1L, "x.mp4", MaterialRole.body);
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(project("x"), List.of(m),
                Map.of(1L, analysis(1L, "completed", "scene")), Map.of(1L, List.of(segment(1L, 0, 8, 3))), "body", false);
        assertTrue(result.matched().isEmpty());
        assertTrue(result.fallback().get(0).reason().contains("合法时间段"));
    }

    @Test
    void filenameAloneCannotMatch() {
        Material m = material(1L, "护肤-product.mp4", MaterialRole.none);
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(project("完全不同"), List.of(m),
                Map.of(1L, analysis(1L, "completed", "scene")), Map.of(1L, List.of(segment(1L, 0, 0, 3))), "body", false);
        assertTrue(result.matched().isEmpty());
        assertFalse(result.missing().isEmpty());
    }

    @Test
    void emptyCandidatesAreMissing() {
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(null, List.of(), Map.of(), Map.of(), "body", false);
        assertEquals(1, result.missing().size());
        assertTrue(result.fallback().isEmpty());
    }

    @Test
    void strictBlocksOnlyConfirmedNoSceneEvidenceWithUsableMaterial() {
        Material m = material(1L, "x.mp4", MaterialRole.none);
        SemanticEvidenceMatcher.Result result = SemanticEvidenceMatcher.match(project("x"), List.of(m),
                Map.of(1L, analysis(1L, "completed", "fallback")), Map.of(), "body", true);
        assertTrue(result.blockers().isEmpty(), "analysis unavailable/fallback must not hard stop");
        result = SemanticEvidenceMatcher.match(project("x"), List.of(m),
                Map.of(1L, analysis(1L, "completed", "scene")), Map.of(1L, List.of()), "body", true);
        assertEquals(1, result.blockers().size());
    }

    private static Material material(Long id, String name, MaterialRole role) {
        Material m = new Material(); m.setId(id); m.setName(name); m.setFilePath("C:/fixtures/" + name);
        m.setRole(role); m.setFileType(Material.FileType.video); m.setDurationSec(10.0); m.setStatus(Material.Status.ready); return m;
    }
    private static MaterialAnalysis analysis(Long id, String status, String source) {
        MaterialAnalysis a = new MaterialAnalysis(); a.setMaterialId(id); a.setStatus(status); a.setSource(source); return a;
    }
    private static MaterialSegment segment(Long materialId, long id, double start, double end) {
        MaterialSegment s = new MaterialSegment(); s.setId(id); s.setMaterialId(materialId); s.setIdx((int) id); s.setStartSec(start); s.setEndSec(end); s.setDurationSec(end - start); return s;
    }
    private static Project project(String text) { Project p = new Project(); p.setProduct(text); return p; }
}
