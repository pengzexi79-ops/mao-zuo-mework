package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.dto.MixParams;
import java.util.Collection;
import java.util.List;

/** Keeps public-source eligibility consistent across planning, gap analysis, and editing. */
public final class MaterialSourcePolicy {
    private MaterialSourcePolicy() { }

    public static boolean allows(Material material, MixParams params) {
        if (material == null) return false;
        MixParams normalized = params == null ? new MixParams().normalized() : params.normalized();
        return !"local".equalsIgnoreCase(normalized.getMaterialSourceMode())
                || material.getSource() != Material.Source.crawl;
    }

    public static List<Material> allowed(Collection<Material> materials, MixParams params) {
        if (materials == null) return List.of();
        return materials.stream().filter(material -> allows(material, params)).toList();
    }

    public static boolean localOnly(MixParams params) {
        MixParams normalized = params == null ? new MixParams().normalized() : params.normalized();
        return "local".equalsIgnoreCase(normalized.getMaterialSourceMode());
    }
}
