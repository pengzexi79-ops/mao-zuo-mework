package com.douyin.mixcut.config;

import com.douyin.mixcut.security.AccessTokenFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Web 层配置：
 * - 开发期放开 CORS（Vite dev server 5173）
 * - 成片 / 缩略图 静态映射，前端可直接 <video src="/files/output/xxx.mp4">
 * - 出片线程池：视频渲染是 CPU 密集型，默认 2 并发，避免把机器打满
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppProps props;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = props.getCorsAllowedOrigins() == null
                ? List.of()
                : props.getCorsAllowedOrigins().stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("app.cors-allowed-origins cannot contain wildcard patterns when credentials are enabled");
        }
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/output/**")
                .addResourceLocations("file:" + props.output().toString().replace('\\', '/') + "/");
        registry.addResourceHandler("/files/materials/**")
                .addResourceLocations("file:" + props.materials().toString().replace('\\', '/') + "/");
        registry.addResourceHandler("/files/thumbs/**")
                .addResourceLocations("file:" + props.thumbs().toString().replace('\\', '/') + "/");
        // Hash 命名的构建资源可长期缓存；首页保持不缓存以便新版本立即生效。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(30 * 24 * 60 * 60);
        // 前端构建产物（mvn package 前把 dist 拷到 resources/static）
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }

    @Bean("mediaExecutor")
    public Executor mediaExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(120);
        ex.setThreadNamePrefix("media-");
        // Media requests must return quickly when saturated; persisted pending tasks can be retried by recovery.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    /** 素材结构化分析执行器：独立于上传/渲染，避免场景检测占用出片线程。 */
    @Bean("analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(30);
        ex.setThreadNamePrefix("analysis-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    @Bean("crawlExecutor")
    public Executor crawlExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        int poolSize = Math.max(1, Math.min(4, Math.min(props.getRenderMaxConcurrency(), props.getRenderPoolSize() > 0 ? props.getRenderPoolSize() : 2)));
        ex.setCorePoolSize(poolSize);
        ex.setMaxPoolSize(poolSize);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("crawl-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }

    @Bean("renderExecutor")
    public Executor renderExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        int requested = props.getRenderPoolSize();
        int configuredMax = Math.max(1, Math.min(50, props.getRenderMaxConcurrency()));
        int recommended = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        int poolSize = Math.max(1, Math.min(configuredMax, requested > 0 ? requested : recommended));
        ex.setCorePoolSize(poolSize);
        ex.setMaxPoolSize(poolSize);
        ex.setQueueCapacity(Math.max(1, props.getRenderQueueCapacity()));
        ex.setThreadNamePrefix("render-");
        // 饱和时不得让 HTTP 或 watchdog 线程亲自执行数小时渲染；JobService 会保留 pending 并重试派发。
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    @Bean
    public FilterRegistrationBean<AccessTokenFilter> accessTokenFilterRegistration() {
        FilterRegistrationBean<AccessTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AccessTokenFilter(props));
        registration.addUrlPatterns("/*");
        registration.setName("accessTokenFilter");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
