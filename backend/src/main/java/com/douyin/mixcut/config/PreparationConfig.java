package com.douyin.mixcut.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 出片准备任务的专用执行器。
 *
 * <p>准备任务可能等待公开素材抓取队列最长 90 秒；独立线程池保证等待期间不会占用
 * HTTP 请求线程，也不会挤占 crawl / render 池。饱和时拒绝并落盘 failed 状态，
 * 轮询端可见终态，而不是让调用方卡在提交阶段。</p>
 */
@Configuration
public class PreparationConfig {

    @Bean("prepareExecutor")
    public Executor prepareExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        // A preparation task can legitimately wait for crawl admission. Keep a bounded pool,
        // but do not serialize unrelated Studio submissions behind one slow public source.
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(20);
        ex.setThreadNamePrefix("prepare-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }
}
