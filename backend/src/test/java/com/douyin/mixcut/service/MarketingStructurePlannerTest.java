package com.douyin.mixcut.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketingStructurePlannerTest {

    @Test
    void buildsExplainableSequencePreviewForCommerceTemplates() {
        var stages = MarketingStructurePlanner.stages("123234", 74);

        assertEquals(6, stages.size());
        assertEquals("1", stages.get(0).code());
        assertEquals("钩子", stages.get(0).role());
        assertEquals("4", stages.get(5).code());
        assertTrue(stages.get(2).allowedMaterialRoles().contains("product"));
        assertEquals(74.0, stages.stream().mapToDouble(MarketingStructurePlanner.Stage::targetSec).sum(), 0.01);
        assertEquals("1(7.0s) → 2(13.9s) → 3(15.2s) → 2(13.9s) → 3(15.2s) → 4(8.8s)",
                MarketingStructurePlanner.preview(stages));
    }

    @Test
    void normalizesCommonTemplateInputAndRejectsInvalidSequences() {
        assertEquals("12234", MarketingStructurePlanner.normalizePattern("1-2-2-3-4"));
        assertEquals("123234", MarketingStructurePlanner.normalizePattern(""));
        assertThrows(IllegalArgumentException.class, () -> MarketingStructurePlanner.normalizePattern("12"));
        assertThrows(IllegalArgumentException.class, () -> MarketingStructurePlanner.normalizePattern("abcdef"));
    }
}
