# vtj-server

`vtj-server` 是 VTJ.PRO 开源低代码前端源码的自建 Java 后端。它用于替代官方闭源后端，给 VTJ 设计器提供项目 DSL 持久化、静态资源上传、模板接口、Open API 兼容入口，以及可配置的 OpenAI 兼容 AI Agent 网关。

本文档说明如何把本仓库和 VTJ.PRO 前端源码放在一起本地运行。下面示例假设：

- 后端仓库：`D:\work\self\vtj-server`
- 前端源码仓库：`D:\work\self\vtj`
- 后端端口：`9527`
- 前端 Vite 端口：`5173`

## 运行架构

```text
Browser
  |
  | http://localhost:5173/#/
  v
VTJ.PRO frontend source (Vite)
  |  proxy /__vtj__ and /api
  v
vtj-server (Spring Boot, http://localhost:9527)
  |-- PostgreSQL: projects, files, histories, templates, LLM configs, AI topics
  |-- Redis: optional cache
  `-- OpenAI-compatible model provider: /chat/completions
```

主要接口分组：

- `POST /__vtj__/api/{type}.json`：VTJ 设计器本地服务协议，负责 `init`、`saveProject`、`saveFile`、`getFile`、`publish` 等操作。
- `POST /__vtj__/api/uploader.json`：设计器静态资源上传。
- `GET /api/oss/file/{filename}`：访问已上传资源。
- `GET/POST /api/open/*`：VTJ.PRO 开放接口、模板接口、登录伪鉴权、AI topic/chat/completions。
- `GET/POST/PUT/DELETE /api/llm`：后端模型配置管理接口。

## 环境要求

后端：

- JDK 17
- Maven 3.9+，也可以使用仓库内 Maven wrapper
- PostgreSQL 16+
- Redis 7.x，可选；Redis 不可用时后端会降级为无缓存

前端：

- Node.js 20+
- pnpm 8+，前端仓库当前 devDependency 使用 pnpm 10.x

## 启动后端

1. 准备 PostgreSQL 数据库。

   ```sql
   CREATE DATABASE vtj;
   ```

2. 在后端仓库设置环境变量。下面是 PowerShell 示例，按你的本地数据库信息修改。

   ```powershell
   $env:VTJ_DB_URL = "jdbc:postgresql://localhost:5432/vtj"
   $env:VTJ_DB_USERNAME = "postgres"
   $env:VTJ_DB_PASSWORD = "<your-db-password>"
   $env:VTJ_AI_SECRET = "<your-local-random-secret>"
   $env:VTJ_REMOTE = "http://localhost:9527"
   $env:SERVER_PORT = "9527"
   ```

   可选 Redis 配置：

   ```powershell
   $env:VTJ_REDIS_HOST = "localhost"
   $env:VTJ_REDIS_PORT = "6379"
   $env:VTJ_REDIS_PASSWORD = ""
   ```

   `VTJ_DB_PASSWORD` 没有安全的通用默认值，请按你的本地 PostgreSQL 配置显式设置。

   `VTJ_AI_SECRET` 用于加密存储模型 API Key。生产环境必须固定且保密；如果更换该值，数据库里已有的密文 API Key 将无法解密，需要重新保存模型配置。开发环境不设置时后端会使用临时开发密钥并打印警告，生产环境不要依赖这个兜底值。

3. 编译、测试并启动后端。

   ```powershell
   cd D:\work\self\vtj-server
   mvn -s maven-settings.xml clean compile
   mvn -s maven-settings.xml test
   mvn -s maven-settings.xml spring-boot:run
   ```

   后端启动后会根据 `src/main/resources/schema.sql` 自动创建缺失表。

## 配置 AI 模型

AI 网关不托管模型，只转发到 OpenAI Chat Completions 兼容接口。通过 `/api/llm` 动态写入配置，API Key 只写入不回显。

PowerShell 示例：

```powershell
Invoke-RestMethod `
  -Uri "http://localhost:9527/api/llm" `
  -Method Post `
  -ContentType "application/json" `
  -Body (@{
    name = "VTJ Agent"
    type = "CODING"
    provider = "OpenAI Compatible"
    apiKey = "<your-api-key>"
    baseUrl = "https://your-openai-compatible-host/v1"
    model = "your-model"
    enabled = $true
  } | ConvertTo-Json)
```

如果你还需要多模态设计稿场景，可以再创建一条 `type = "MULTIMODAL"` 的配置。当前 Agent 生成页面、绑定事件、绑定接口、维护页面/模板等前端 DSL 操作通常走 `CODING` 场景。

查看配置：

```powershell
Invoke-RestMethod -Uri "http://localhost:9527/api/llm" -Method Get
```

返回中只会出现 `hasApiKey`，不会返回明文密钥。

## 配置 VTJ.PRO 前端代理

前端源码仓库当前有两个常用运行入口：

- `pnpm run dev`：进入 `dev` 目录运行本地设计器，通常访问 `http://localhost:5173/#/`。
- `pnpm run pro:dev`：进入 `platforms/pro` 目录运行 VTJ.PRO 平台。

运行哪个入口，就修改哪个目录下的 `proxy.config.ts`。

### 本地设计器入口

编辑 `D:\work\self\vtj\dev\proxy.config.ts`：

```ts
export default {
  '/__vtj__': {
    target: 'http://localhost:9527',
    changeOrigin: true,
    ws: true
  },
  '/api': {
    target: 'http://localhost:9527',
    changeOrigin: true,
    ws: true
  }
};
```

### VTJ.PRO 平台入口

编辑 `D:\work\self\vtj\platforms\pro\proxy.config.ts`，使用同样配置：

```ts
export default {
  '/__vtj__': {
    target: 'http://localhost:9527',
    changeOrigin: true,
    ws: true
  },
  '/api': {
    target: 'http://localhost:9527',
    changeOrigin: true,
    ws: true
  }
};
```

这两个代理都需要保留：

- `/__vtj__` 对接设计器本地持久化协议。
- `/api` 对接 open-api、AI Agent、模型配置和静态资源访问。

## 启动前端

首次安装依赖：

```powershell
cd D:\work\self\vtj
pnpm run setup
```

启动本地设计器：

```powershell
pnpm run dev
```

打开：

```text
http://localhost:5173/#/
```

如果需要运行 VTJ.PRO 平台入口：

```powershell
pnpm run pro:dev
```

## 联调检查

后端启动、前端代理配置完成后，可以按下面顺序检查：

1. 打开 `http://localhost:5173/#/`，前端应该能请求 `POST /__vtj__/api/init.json` 并加载默认项目。
2. 如果页面出现登录提示，确认 `/api/open/auth/local-dev` 和 `/api/open/user/local-dev` 走到了 `http://localhost:9527`。后端会返回本地开发用户，不需要登录 VTJ.PRO 官方服务。
3. 在设计器内新建或保存页面，后端会写入 PostgreSQL 的 `vtj_projects`、`vtj_files` 等表。
4. 使用 AI Agent 前，先确认 `/api/llm` 至少有一条 `enabled = true` 且 `type = "CODING"` 的配置。
5. Agent 发起生成时，前端会先调用 `/api/open/topic/post/{token}` 保存 topic，再通过 `/api/open/completions/{token}?tid=...&id=...` 接收 SSE 流。

## 常见问题

### 前端仍然提示未登录

检查前端当前运行入口对应的 `proxy.config.ts` 是否同时代理了 `/api` 和 `/__vtj__`，并确认浏览器请求不是打到官方域名或旧的 `localhost:3000`。

### AI 返回为空或立即结束

后端在没有可用模型配置时会优雅返回一个空的结束帧，避免前端报错。请检查：

- `GET http://localhost:9527/api/llm` 是否存在 `enabled = true` 的配置。
- `type` 是否匹配当前场景，页面生成通常需要 `CODING`。
- `VTJ_AI_SECRET` 是否和保存模型配置时一致。
- `baseUrl` 是否是 OpenAI 兼容接口根路径，例如 `https://example.com/v1`，后端会请求 `{baseUrl}/chat/completions`。

### 页面生成后画布没有变化

确认前端收到了 `/api/open/completions` 的 SSE 内容，并且模型输出的是 VTJ Agent 支持的 `A:` 工具调用、Vue SFC 或 diff。后端已支持将 Vue SFC 中的 `template` 和 `style` 转成可见 VTJ DSL 节点。

### 上传文件不可访问

检查：

- `VTJ_STORAGE_ROOT`，默认 `./data`
- `VTJ_STATIC_DIR`，默认 `./data/static`
- 前端资源 URL 是否通过 `/api/oss/file/{filename}` 访问

## 打包

后端打包：

```powershell
cd D:\work\self\vtj-server
mvn -s maven-settings.xml clean package
```

生成物在 `target/` 目录。

## 安全注意

- 不要把真实数据库密码、模型 API Key 或 `VTJ_AI_SECRET` 提交到仓库。
- README、脚本和示例中统一使用占位符。
- `/api/llm` 的 `apiKey` 字段只用于写入，查询接口不会回显明文。
