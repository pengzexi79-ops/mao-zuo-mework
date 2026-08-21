# 喵作 · Mework - 架构设计

> 角色：架构师、应用开发、AI 视频混剪、流量风向观察师、实际实用者。
> 目标：交付给甲方及市场、真实可用的软件。
> 版本：v1.0.0（已跑通端到端）

---

## 1. 需求本质

老板原话可抽象为一条「抖音美妆/护肤/食品类混剪」的工业化管线：

```text
AI 钩子开场（前 3 秒）
  + 中间实拍/明星音视频片段（按 3 秒切片打散、交替、不重复）
  + 自身产品片段（穿插）
  + 结尾行动号召
  + BGM + 口播/字幕
  → 批量输出 50-150 秒（密集时约 100 秒）的竖屏视频
```

核心约束：

| 维度 | 约束 |
|------|------|
| 时长 | 50-150 秒，优选 100 秒 |
| 画幅 | 1080×1920 / 30fps / 竖屏 |
| 素材 | 本地导入 + 网页爬取（音频可爬，登录站点默认关闭） |
| 操作 | 简单、可批量、可半自动 AI 规划 |
| 交付 | Vue 前端 + Java 后端 + MySQL |

---

## 2. 技术栈

| 层 | 选型 | 原因 |
|----|------|------|
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.8 + vue-router 4 + axios | 组件成熟、开发快、企业交付接受度高 |
| 后端 | Spring Boot 3.3.2 + Java 17 + JPA(Hibernate 6.5) | 甲方技术栈主流、招人运维成本低 |
| 数据库 | MySQL 8.0 | 用户指定、稳定 |
| 音视频 | ffmpeg 6/7 + Java ProcessBuilder | 混剪唯一可复用的工业标准 |
| 爬虫 | yt-dlp / you-get / 直链 HTTP | 开源、覆盖广、可降级 |
| AI | 任意兼容 OpenAI / Anthropic / Gemini 的 HTTP 端点 | 不绑死供应商，可本地、可云端 |

---

## 3. 模块划分

```text
frontend/                    Vue 单页应用
  src/
    api.js                   axios + 统一响应处理
    router.js                8 个视图
    views/
      Dashboard.vue          自检、环境、入门
      Materials.vue          素材库、扫描、打标
      Crawl.vue              网页爬取、音效搜索
      AiSettings.vue         AI 供应商/路由/日志
      Projects.vue           项目/产品信息
      Workflows.vue          工作流/Skill 编排
      Studio.vue             控制台：dry-run + 批量渲染
      Outputs.vue            成片库

backend/                     Spring Boot
  src/main/java/com/douyin/mixcut/
    config/                  AppProps, WebConfig, 数据库/线程池
    domain/                  Material, Project, Job, Workflow… 实体
    repository/              Spring Data JPA Repository（嵌套接口）
    dto/                     请求/响应 DTO、MixParams
    service/                 业务核心
      MaterialService        扫描/入库/角色识别
      MixPlanner             剪辑计划生成（老板算法）
      SkillEngine            内置 Skill + AI 规划
      RenderService          调用 ffmpeg 渲染
      JobService             异步任务调度
      AiService              文案/脚本/AI 路由
    external/                外部能力封装
      FfmpegTool             ffmpeg 命令构造
      CrawlerGateway         爬虫/音频搜索
      AiClient               大模型 HTTP 客户端
    web/                     REST API 控制器
  src/main/resources/
    static/                  前端构建产物（随 jar 一起发布）
    db/schema.sql            建表脚本
    application.yml          配置
```

---

## 4. 核心算法：混剪计划（MixPlanner）

### 4.1 输入

- 项目信息（品牌、品类、卖点、受众、语气、禁用词、额外 prompt）
- 素材池（按 hook/body/celebrity/product/endcard/voice/bgm 自动分类）
- 参数 `MixParams`：
  - `minSec/maxSec/dense/targetSec`：时长控制
  - `sliceSec/sliceJitter/explodeLongClips/maxSlicesPerMaterial`：3 秒切片打散
  - `celebrityRatio`：明星/网红片段占比
  - `productSlots/productSec`：产品段数量与单段长度
  - `width/height/fps/bgmVolume`：输出规格
  - `hookText/burnHookText`：钩子文案与烧录

### 4.2 关键规则

1. **目标时长**：
   - `dense=true` 时偏向 `minSec + (maxSec-minSec)*0.6`（约 110 秒）
   - 否则偏向 `minSec + (maxSec-minSec)*0.35`
2. **切片打散（老板原话核心）**：
   - 长素材按 `sliceSec` 切，每段起点递增，不重复取同一段
   - 同一个素材的多个切片被当成「不同素材」参与轮询，避免大段重复
3. **相邻不重复**：
   - 时间线上相邻两段不能是同一个「原始素材」
4. **明星占比控制**：
   - 按 `celebrityRatio` 交错插入，不连续堆叠
5. **产品段均匀分布**：
   - 从后往前计算插入点，避免全部挤在开头或结尾
6. **越界保护**：
   - 每个切片记录 `sourceDuration`，起点+时长不能超出素材尾部

### 4.3 输出

`Plan` 对象：目标时长、规划时长、片段序列（含素材、起点、时长、slot、源时长）、BGM/口播、钩子文案、随机种子、说明 notes。

AI 不直接生成 ffmpeg 命令；它只能修改 `MixParams` 字段表，确保：

- 可解释、可审计
- 不会导致任意命令执行
- 甲方运营人员能看懂改了什么

---

## 5. Skill 工作流引擎

### 5.1 三层能力

1. **内置 Skill（10 个）**：
   - `select_materials` / `set_duration` / `set_slice` / `set_structure`
   - `gen_hook` / `gen_script` / `pick_audio`
   - `set_canvas` / `fetch_web_video` / `fetch_audio_library`
2. **Workflow（步骤编排）**：
   - 内置默认工作流把老板算法参数化
   - 用户可在前端增删改步骤
3. **AI 规划（aiPlan）**：
   - 大模型根据项目描述，输出修改后的 `MixParams`
   - 后端做 schema 校验 + 范围 clamp，拒绝危险字段

### 5.2 默认工作流

```text
set_canvas      → 1080×1920 / 30fps
set_duration    → 50-150s, dense
set_slice       → 3 秒切片、最多 3 段/素材
set_structure   → 明星 25%、产品 3 段
select_materials → 从项目/文件夹选池
pick_audio      → BGM + 口播
gen_hook        → 若用户没手写，AI 生成钩子
run_mixcut      → MixPlanner 生成 Plan
render          → ffmpeg 输出成片
```

---

## 6. 数据模型

关键表：

- `material_folder` / `material`：本地与爬取素材
- `project`：项目 + 默认参数 JSON
- `workflow` / `skill_def`：工作流与内置 skill
- `job` / `job_output`：批量渲染任务与成片
- `ai_provider` / `ai_route` / `ai_log`：AI 供应商、路由、日志

---

## 7. 安全与合规

1. **登录站点默认关闭**：ear0 / tosound 等需登录站点受 `app.allow-login-crawl` 控制，缺省 false。
2. **AI 不 emit 原始命令**：只能改参数表。
3. **文件名清洗**：Windows 非法字符 + ffmpeg/shell 敏感字符全部替换。
4. **素材路径隔离**：所有下载/上传/输出均在 `backend/data/` 子目录内。
5. **降级设计**：
   - AI 不可用时回模板文案
   - freesound/pixabay 缺 Key 时给出明确提示
   - mixkit 免费商用音乐自动兜底

---

## 8. 部署拓扑

单机部署：

```text
[用户浏览器] → http://127.0.0.1:8760
                      ↓
            [Spring Boot jar]  ← 内嵌 static 前端
                      ↓
            [MySQL 3306]  +  [ffmpeg]  +  [data/]
```

可扩展：

- 后端可拆出独立渲染 worker（JobService 已用线程池，可改为 MQ）
- 素材可上对象存储（Material.filePath 改为 URL，FfmpegTool 支持 http 输入）
- AI 供应商可横向加 provider

---

## 9. 已验证的交付标准

- [x] `mvn -DskipTests clean package` BUILD SUCCESS
- [x] `java -jar target/mixcut.jar --server.port=8760` 正常启动
- [x] 前端 `npm run build` 产物写入 `backend/src/main/resources/static`
- [x] MySQL 10 张表初始化成功，服务能连库
- [x] 13 个只读 API 全部 200
- [x] 本地扫描自动识别角色、时长、分辨率
- [x] mixkit 音效搜索真实可用，缺 Key 来源给出提示
- [x] 批量渲染 2 条成片：时长 81.4s / 84.3s，1080×1920 / 30fps / AAC
- [x] 批量成片画面 MD5 不同，确实互不相同
- [x] 钩子文案正确烧录
- [x] 爬取 BGM 真实混入成片（波形相关 0.51）
