package com.douyin.mixcut.domain;

/** 批量生成任务的状态机。 */
public enum JobStatus {
    pending,    // 已入队，待执行
    running,    // 执行中
    done,       // 全部完成
    paused,     // 用户暂停，保留检查点，允许继续
    awaiting_decision, // 自动修复无安全候选，等待用户选择
    failed,     // 失败（全部或关键步骤出错)
    cancelled   // 已取消
}
