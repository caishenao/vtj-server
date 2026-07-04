# AGENTS.md

> 本文件用于指导 AI 编码代理（如 Claude Code、Cursor、Codex 等）在本仓库中生成、修改与验证代码。
> 所有代理在提交任何变更前，必须阅读并遵守本文件的全部约定。
>
> **文档状态说明**：本文件已按仓库**实际实现**校准。凡标注「✅ 已实现」的为当前代码现状，
> 以现有代码为准；标注「🚧 规划中」的为后续增量目标，尚未落地。二者冲突时，一律**以现有代码为准**。

## 1. 项目概述

本项目是 **VTJ.PRO 开源低代码前端引擎的自建后端服务（Java 实现）**。

VTJ.PRO 的前端（设计器、渲染器、出码引擎）以 MIT 协议开源，但其官方后端为闭源商业交付物。
本仓库的目标是：用完全自主、可私有化部署的 Java 后端，替代官方闭源后端，为前端提供
持久化、权限与 AI 编排能力，并支持接入任意符合 OpenAI 规范的大模型服务。

### 后端承担的核心职责
1. **低代码数据持久化**（✅ 已实现）：存取 DSL、项目、页面、区块模板、历史记录、物料、静态文件。
2. **身份与权限（RBAC）**（🚧 规划中）：当前仅有 open-api 的 sign 伪登录，尚无角色/权限校验。
3. **AI 代理网关**（✅ 已实现）：配置驱动的 OpenAI 兼容协议转发，WebClient SSE 流式回传，场景路由，apiKey AES-GCM 加密。

> 代理注意：后端不训练也不托管模型，AI 能力一律通过 OpenAI 兼容协议转发实现。

## 2. 技术栈与版本约束（不得擅自升级或替换）

| 类别 | 选型 | 版本 | 状态 |
| --- | --- | --- | --- |
| 语言 | Java | **17 (LTS)** | ✅ 现状（`pom.xml` `java.version=17`） |
| 框架 | Spring Boot | 3.3.4 | ✅ |
| 构建 | Maven | 3.9+（含 `mvnw` wrapper） | ✅ |
| 持久层 | MyBatis-Plus | 3.5.7 | ✅ |
| 数据库 | **PostgreSQL**（DSL 存于 `JSONB`） | 16+ | ✅ 现状（非 MySQL） |
| 缓存 | Redis（Spring Data Redis，尽力而为，非权威） | 7.x | ✅ |
| Web | Spring MVC（`spring-boot-starter-web`） | - | ✅ |
| 参数校验 | Jakarta Validation | - | ✅ |
| 工具 | Lombok | - | ✅ |
| 鉴权 | Spring Security + JWT (jjwt) | - | 🚧 规划中 |
| AI 调用 | Spring WebFlux WebClient（SSE 流式） | - | ✅ 已实现（`spring-boot-starter-webflux`，与 MVC 共存） |
| 文档 | springdoc-openapi (Swagger UI) | 2.x | 🚧 规划中 |
| 对象映射 | MapStruct | - | 🚧 规划中（当前用手写 `Map`/`Jsons` DTO） |
| 数据迁移 | Flyway | - | 🚧 规划中（当前用 `schema.sql` 初始化） |

- 当前基线为 **JDK 17**；引入 record / sealed / pattern matching 可以，但**虚拟线程等 21 专属特性暂不可用**。
- 禁止引入未在上表列出的重量级框架，除非在提交说明中给出充分理由。

## 3. 目录结构（生成代码必须遵循此分层）

当前实际包名为 `cn.cai.vtjserver`（非 `pro.vtj.backend`）。以现有代码为准，新增代码遵循同一分层：

```
src/main/java/cn/cai/vtjserver/
├── VtjServerApplication.java        # 启动类（@MapperScan 覆盖 mapper 与 module.ai.mapper）
├── config/                          # VtjProperties、WebConfig(CORS)、WebClientConfig 等配置
├── controller/                      # DesignerController、OpenApiController
├── service/                         # VtjDesignerService、OpenApiService、RedisCacheService
├── mapper/ entity/ dto/             # 低代码持久化的 Mapper/实体/DTO
├── exception/                       # BusinessException、GlobalExceptionHandler(@RestControllerAdvice)
├── mybatis/                         # JsonbTypeHandler（PostgreSQL JSONB <-> Map）
├── util/                            # Jsons、AesGcmCipher（apiKey 加密）
└── module/ai/                       # ✅ AI 代理网关
    ├── controller/                  # LlmController（/api/llm CRUD）
    ├── service/                     # LlmConfigService（CRUD+路由）、AiAgentService（SSE 桥接）
    ├── provider/                    # OpenAiCompatClient（WebClient 流式）
    ├── entity/ mapper/              # LlmConfigEntity、AiTopicEntity 及其 Mapper
    └── dto/                         # ChatCompletionRequest、LlmConfig*、AiScene、AiStreamEvent
src/main/resources/
├── application.properties           # 基础配置（默认端口 9527）
└── schema.sql                       # 建表脚本，启动时由 spring.sql.init 执行
```

> 🚧 规划中：`security/`（jwt、rbac）尚未创建。
> 新增业务模块时严格遵循 `controller -> service -> mapper` 三层，禁止跨层调用；
> Controller 不得包含业务逻辑，只做参数接收与结果封装。

## 4. 接口契约（最高优先级：以前端为准）

**VTJ.PRO 前端的真实请求协议是唯一权威契约来源。** 本仓库的接口即由该协议反推而来，
生成/修改接口前必须先核对前端实际调用路径与载荷，切勿臆造 RESTful 路径。

### 4.1 统一返回体
所有接口返回 `ApiResponse<T>`（`dto/ApiResponse.java`），字段如下（与前端约定一致，**勿改动字段名**）：
```json
{ "code": 0, "msg": "success", "data": {}, "stack": null, "success": true }
```
- `code = 0` 且 `success = true` 表示成功；`code != 0` / `success = false` 表示业务错误。
- 业务/校验错误默认以 **HTTP 200 + 上述失败信封** 返回（前端按信封判定，而非 HTTP 状态码）。
  这一约定由 `GlobalExceptionHandler` 统一保证；仅上传超限（413）与未预期异常（500）返回非 200。

### 4.2 已实现的核心接口（真实协议路径）
| 分组 | 路径 | 说明 |
| --- | --- | --- |
| 设计器本地服务 | `POST /__vtj__/api/{type}.json` | 单入口分发：`init/getProjects/saveProject/saveFile/getFile/removeFile/saveHistory/getHistory/saveHistoryItem/saveMaterials/publish/genVueContent/getStaticFiles` 等（见 `VtjDesignerService.dispatch`） |
| 文件上传 | `POST /__vtj__/api/uploader.json` | multipart 上传静态资源 |
| 资源访问 | `GET /api/oss/file/{filename}` | 读取已上传静态文件 |
| 云端开放接口 | `GET/POST /api/open/*` | `auth/user/templates/template/dsl/dict/template/publish/topic/*/chat/*/completions` 等（`OpenApiController`），支持 JSONP `callback` |
| AI 完成流 | `GET /api/open/completions/{token}?tid=&id=` | 按 `tid` 取回 topic → 路由模型 → SSE 流式回传 `{id,topicId,content,finish}`（`AiAgentService`） |
| AI 主题 | `POST /api/open/topic/post/{token}` | 持久化会话主题（prompt/scene），供 completions 取用 |
| 模型配置管理 | `GET/POST/PUT/DELETE /api/llm[/{id}]` | `llm_config` 增删改查 + 启停（`LlmController`）；apiKey 只写不回显 |

> 说明：`/api/llm` 为**后端管理面**（模型动态增删，§5.1），非 VTJ 前端设计器流程路径；它是附加接口，不影响 `/__vtj__` 与 `/api/open` 真实协议。
> 🚧 早期草案中的 `/api/dsl`、`/api/apps`、`/api/agent`、`/api/users` 等理想化 REST 路径**并非真实前端契约**，不得据此新建接口；如需新增须先对齐真实前端调用。

### 4.3 字段命名
- JSON 字段统一使用 **camelCase**，与前端 TypeScript 保持一致（`mybatis-plus map-underscore-to-camel-case=true`）。
- 时间字段以 `OffsetDateTime` 序列化为 ISO-8601 字符串。

## 5. AI 代理网关规范（✅ 本项目核心增量，已实现）

已落地于 `module/ai`。真实入口为 `GET /api/open/completions`（SSE）与 `POST /api/open/topic/post`（建主题），
配置管理在 `/api/llm`。以下约定为**现行实现的约束**，修改时必须保持：

1. **配置驱动**（✅）：`vtj_llm_config` 表，字段 `name, type(MULTIMODAL/CODING), provider, apiKey(密文), baseUrl, model, enabled`。
   模型经 `/api/llm` 动态增删启停，禁止硬编码 API Key 或模型名。
2. **场景路由**（✅）：`AiScene` 区分 MULTIMODAL(设计稿/UI) 与 CODING(逻辑/代码)；`LlmConfigService.resolveForScene`
   取该场景下最近更新的 enabled 配置。topic 的 `scene` 决定走哪类模型。
3. **协议统一**（✅）：`OpenAiCompatClient` 以 OpenAI Chat Completions 兼容协议 POST 到 `{baseUrl}/chat/completions`
   （OpenAI、DeepSeek、智谱、openrouter 及私有端点）。
4. **流式返回**（✅）：WebClient `bodyToFlux` 解析 OpenAI SSE `data:` 分片为增量 delta，桥接到 MVC `SseEmitter`，
   逐片下发 `{id,topicId,content,finish:false}`，终止发 `finish:true`。订阅跑在 `boundedElastic`，不阻塞 servlet 线程；
   emitter 超时/断开时 dispose 上游（取消机制）。
5. **超时与兜底**（✅）：多模态默认 60s、编程 45s（`vtj.ai.timeout-*`，多模态 ≥ 30s）。**无 enabled 配置或无 topic 时优雅回退
   为单条空 finish**（等同旧 stub），保证未配置部署不报错。🚧 待补：对模型返回 Vue SFC 的合法性校验与落库。
6. **敏感信息**（✅）：`apiKey` 以 AES-256-GCM 加密存储（`AesGcmCipher`，密钥取 `VTJ_AI_SECRET`）；DTO 只回 `hasApiKey`
   布尔，绝不回显明文；日志不打印密钥。

## 6. 数据库规范

- 当前数据库为 **PostgreSQL**；DSL/历史/物料等以 `JSONB` 列存储，通过 `mybatis/JsonbTypeHandler` 转换。
- 表名使用 `snake_case` 并加 `vtj_` 前缀（`vtj_projects`、`vtj_files`、`vtj_histories`、`vtj_history_items`、
  `vtj_materials`、`vtj_static_files`、`vtj_templates`、`vtj_llm_config`、`vtj_ai_topic`）。
- `vtj_llm_config.api_key` 存 AES-GCM 密文；`vtj_ai_topic` 持久化会话 prompt/scene（completions 仅带 tid，须据此取回）。
- 现有表含 `created_at / updated_at` 审计字段（✅）。🚧 规划中：`created_by`、`is_deleted`（逻辑删除）等 RBAC 审计字段尚未统一加入。
- 建表当前由 `src/main/resources/schema.sql` 在启动时经 `spring.sql.init` 执行（**非 Flyway**）。
- 🚧 规划中：迁移至 `db/migration/` 下 Flyway 脚本（`V{版本}__{描述}.sql`）。在此之前，表结构变更改 `schema.sql`（保持 `IF NOT EXISTS` 幂等）。

## 7. 编码规范

- 遵循阿里巴巴 Java 开发规约；Controller 层参数使用 Jakarta Validation 注解校验。
- DTO 与 Entity 分离，禁止 Entity 直接暴露给前端（当前经 `Map`/`Jsons` 手工装配；MapStruct 为规划项）。
- 优先使用构造器注入（`@RequiredArgsConstructor`），禁止字段注入。
- 异常统一交由全局 `@RestControllerAdvice`（`GlobalExceptionHandler`）处理；业务代码禁止静默吞异常。
- 所有公共方法需有 Javadoc；复杂逻辑需行内注释说明「为什么」而非「做什么」。
- 使用 SLF4J 记录日志（`@Slf4j`），禁止 `System.out.println`；日志不得输出敏感信息。

## 8. 构建、运行与验证命令

代理在完成任何代码生成后，须依次执行并确保通过（本机可用 `mvn` 或 `./mvnw`；离线镜像见 `maven-settings.xml`）：

```bash
# 编译
mvn -s maven-settings.xml clean compile

# 单元测试（新增功能必须附带测试）
mvn -s maven-settings.xml test

# 本地启动（默认端口 9527）
mvn -s maven-settings.xml spring-boot:run

# 打包
mvn -s maven-settings.xml clean package
```

- 启动前需可连通 PostgreSQL（`VTJ_DB_URL`，默认 `jdbc:postgresql://localhost:5432/vtj`）；Redis 可选（不可用时降级为无缓存）。
- 数据库表由 `schema.sql` 在启动时自动创建。
- 🚧 规划中：`spotless` 代码风格插件、springdoc Swagger UI（`/api/docs`）尚未接入。

## 9. 测试要求

- 每个 Service 公共方法应有对应单元测试（JUnit 5 + Mockito）；参见 `VtjDesignerServiceTest`、`LlmConfigServiceTest`、`AiAgentServiceTest`（Mapper/Redis/Provider 全部 Mock，无需真实 DB）。
- Controller 建议补充 `@WebMvcTest` + MockMvc 集成测试（🚧 待补）。
- AI 代理网关的第三方调用必须隔离，禁止在测试中发起真实模型请求；`OpenAiCompatClientTest` 用 stub `ExchangeFunction` 注入预置 SSE，不引 WireMock、不联网。

## 10. 代理工作准则（务必遵守）

1. **先读契约再动手**：任何接口开发前，先核对 VTJ 前端真实调用（`/__vtj__/api`、`/api/open`），不以本文件早期理想化路径为准。
2. **以现有代码为准，增量拓展**：本文件与现有代码冲突时以代码为准；发现文档与实现不一致应同步修正本文件。
3. **最小改动原则**：仅修改与当前任务相关的文件，禁止大范围重构无关代码。
4. **不臆造字段**：前端未定义的返回字段不得擅自新增；不确定时在提交说明中提出疑问。
5. **提交前自检**：确保 `mvn clean package` 与全部测试通过后方可提交。
6. **安全红线**：禁止硬编码密钥、密码；禁止关闭鉴权；禁止在日志输出敏感信息。
7. **变更说明**：每次提交需说明「改了什么、为什么、如何验证」。
