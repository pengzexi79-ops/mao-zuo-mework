package com.douyin.mixcut;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI 自动抖音混剪工作流 - 应用入口
 *
 * <p>启动后即可访问：
 * <ul>
 *   <li>前端（由 Vue 构建后放入 backend/src/main/resources/static 或独立部署）：http://127.0.0.1:8760/</li>
 *   <li>OpenAPI 文档：http://127.0.0.1:8760/swagger 未内置，可用 /actuator 健康检查</li>
 *   <li>健康：http://127.0.0.1:8760/actuator/health</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.douyin.mixcut.mapper")
// 仓储接口集中声明在 Repositories 内部（嵌套接口），Spring Data 默认不扫描，必须显式打开
@EnableJpaRepositories(
        basePackages = "com.douyin.mixcut.repository",
        considerNestedRepositories = true
)
public class DouyinMixcutApplication {

    public static void main(String[] args) {
        SpringApplication.run(DouyinMixcutApplication.class, args);
    }
}
