package com.douyin.mixcut.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 成片交付质检报告：把「能不能发」拆成九个可解释维度，逐项给出通过/提示/拦截与原因。
 *
 * <p>audio/video 是硬门槛（fail 会拦截输出），subtitle/duplicate/semantic/hook/exposure/rhythm/subtitleSync 是
 * 提示性维度（warn 不会拦截，但会写入报告供运营复核）。整体状态取最严重的一项：
 * fail &gt; warn &gt; pass。</p>
 */
@Data
public class DeliveryQc {

    /** pass / warn / fail */
    private String status = "pass";
    /** 一行可读摘要，例如「6 项质检：5 通过，1 提示」。 */
    private String summary;
    /** 九个维度的逐项结果，顺序固定：audio/video/subtitle/duplicate/semantic/hook/exposure/rhythm/subtitleSync。 */
    private List<CategoryResult> categories = new ArrayList<>();

    @Data
    public static class CategoryResult {
        /** audio / video / subtitle / duplicate / semantic / hook / exposure / rhythm / subtitleSync */
        private String category;
        /** pass / warn / fail */
        private String status = "pass";
        /** 该维度下每一条可解释的检查结论。 */
        private List<String> checks = new ArrayList<>();
        /** 需要人工关注的问题（与 checks 对应，可为空）。 */
        private List<String> issues = new ArrayList<>();

        public void check(String text) {
            checks.add(text);
        }

        public void issue(String text) {
            issues.add(text);
        }
    }

    public CategoryResult category(String name) {
        CategoryResult result = new CategoryResult();
        result.setCategory(name);
        categories.add(result);
        return result;
    }

    /** 依据六个维度重算整体状态，并在状态不为 pass 时补一条摘要。 */
    public void resolve() {
        String overall = "pass";
        int warn = 0, fail = 0;
        for (CategoryResult category : categories) {
            if ("fail".equals(category.getStatus())) {
                overall = "fail";
                fail++;
            } else if ("warn".equals(category.getStatus()) && !"fail".equals(overall)) {
                overall = "warn";
                warn++;
            }
        }
        this.status = overall;
        int passed = categories.size() - warn - fail;
        this.summary = categories.size() + " 项质检：" + passed + " 通过"
                + (warn > 0 ? "，" + warn + " 提示" : "")
                + (fail > 0 ? "，" + fail + " 拦截" : "");
    }
}
