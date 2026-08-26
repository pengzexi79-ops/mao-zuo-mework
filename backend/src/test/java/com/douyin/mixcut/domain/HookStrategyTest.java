package com.douyin.mixcut.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HookStrategyTest {

    @Test
    void deriveMapsProjectToneToExpectedBaseStrategy() {
        Project project = new Project();
        project.setTone("真实测评");

        assertEquals(HookStrategy.RESULT, HookStrategy.derive(project));
    }

    @Test
    void deriveDefaultsToCounterintuitiveForNullProject() {
        assertEquals(HookStrategy.COUNTERINTUITIVE, HookStrategy.derive(null));
    }

    @Test
    void selectRotatesStrategiesAcrossVariants() {
        Project project = new Project();
        project.setTone("真实测评");

        HookStrategy first = HookStrategy.select(project, 0);
        HookStrategy second = HookStrategy.select(project, 1);

        assertNotEquals(first, second, "each variant must pick a distinct hook angle");
        // After a full cycle of 7 variants, the base strategy should repeat.
        assertEquals(first, HookStrategy.select(project, 7));
    }

    @Test
    void safeValueOfHandlesUnknownAndBlank() {
        assertEquals(HookStrategy.CONFLICT, HookStrategy.safeValueOf("conflict"));
        assertEquals(HookStrategy.CONFLICT, HookStrategy.safeValueOf("CONFLICT"));
        assertNull(HookStrategy.safeValueOf(null));
        assertNull(HookStrategy.safeValueOf(""));
        assertNull(HookStrategy.safeValueOf("not-a-strategy"));
    }
}
