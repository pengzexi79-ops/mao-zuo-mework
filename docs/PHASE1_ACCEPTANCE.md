# 阶段 1 验收报告

日期：2026-08-10
项目：喵作 · Mework
项目路径：`<legacy-workspace>\ai-douyin-mixcut`

## 交付内容

- MySQL 幂等建表脚本：`backend/src/main/resources/db/schema.sql`
- Spring Boot 3 配置：`backend/src/main/resources/application.yml`
- MyBatis-Plus 素材 Mapper：`backend/src/main/java/com/douyin/mixcut/mapper/MaterialMapper.java`
- MyBatis-Plus 素材适配层：`backend/src/main/java/com/douyin/mixcut/repository/MaterialStore.java`
- 素材扫描接口：`POST /api/materials/scan`
- 素材列表接口：`GET /api/materials`
- FFmpeg 切片封装：`backend/src/main/java/com/douyin/mixcut/external/FfmpegTool.java`
- Axios TypeScript 片段：`frontend/src/api/material.ts`

现有 Vue 3 + Element Plus 页面未修改布局，未接入爬虫、Remotion、趋势雷达或 Hyperframes。

## 配置核对

默认 JDBC 配置已指向：`127.0.0.1:3306/ai_mix_video`，用户名为 `root`，并保留 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量覆盖能力。

> 本机 MySQL 实例由 Windows 服务 `MySQL80` 使用 `C:\DevTools\mysql-8.0.43-winx64\my.ini` 启动。

## 自动化验证

| 验收项 | 结果 | 说明 |
| --- | --- | --- |
| Maven 编译打包 | 通过 | `mvn -Ddelivery.jar.name=mixcut-phase1 clean package`，生成 `backend/target/mixcut-phase1.jar` |
| Maven 测试 | 通过 | 6 tests，0 failures，0 errors |
| FFmpeg 真实切片 | 通过 | 生成 4 秒测试视频，调用 `cutNormalize` 输出 360x640、约 1.5 秒片段，ffprobe 可读 |
| 前端构建 | 通过 | `npm run build`，1660 modules transformed；仅有 Rollup 注释警告 |
| 前端开发服务器 | 通过 | `http://127.0.0.1:5173/` 可访问，页面主要导航和概览内容正常渲染 |
| 后端启动 | 已通过 | 当前后端已使用已配置凭据启动并监听 127.0.0.1:8760 |
| SQL 执行与 material 表核验 | 已通过 | root 与 mixcut 账号均可认证，项目库已初始化 |
| 扫描接口实测 | 可执行 | 后端已建立数据库连接，需提供本地素材目录进行实际扫描 |
| 素材列表接口实测 | 已通过 | `/api/materials` 可正常访问 |

## 历史阻塞记录

以下 1045 错误来自早期启动日志，已经通过重新配置本机账号和项目 `.env` 修复，不代表当前运行状态：

```text
ERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: YES)
```

当前已验证：

```text
root@localhost
mixcut@localhost / ai_mix_video
MySQL80: Running
Tomcat: 127.0.0.1:8760
HTTP: 200
```

启动时使用项目根目录 `.env` 中的本地配置，不需要再次向用户索要账号密码。

## 当前启动方式

```bash
cd backend
java -jar target/mixcut-phase1.jar --server.port=8760 --server.address=127.0.0.1
```

然后验收：

```bash
curl -X POST http://127.0.0.1:8760/api/materials/scan \
  -H "Content-Type: application/json" \
  -d '{"path":"<项目绝对路径>/sample-materials","autoRole":true}'

curl http://127.0.0.1:8760/api/materials
```

## 结论

阶段 1 的代码、SQL、前端接口、构建、数据库连接和 FFmpeg 环境检查均已通过。历史 1045 错误仅保留为故障记录，不能作为当前环境结论。
