# VTJ 低代码开发平台 — 产品需求文档（PRD）

> **文档版本**: v1.0 | **最后更新**: 2026-05-12 | **状态**: 正式评审中

---

## 一、项目概述

VTJ（Visual Toolkit for Java）是一个面向企业级应用的低代码开发平台，通过可视化拖拽方式快速构建 **Web 应用（SPA/SSR）、H5 页面、UniApp 小程序**等多端应用，提供从设计、开发、测试到发布的一站式全生命周期管理能力。

### 核心目标
- **设计器**：可视化拖拽设计器，支持组件配置、数据源绑定、事件逻辑编排
- **多端发布**：一键发布为 Vue3 SPA、H5、UniApp 等多种形态
- **应用管理**：完整的应用 CRUD 生命周期管理，支持多应用协作
- **版本控制**：页面级和应用级版本管理与历史回溯
- **物料系统**：组件/区块/模板等物料的上传、管理与复用
- **插件扩展**：插件化架构，支持第三方插件接入

---

## 二、功能需求

### 2.1 应用管理模块

| 功能 | 说明 |
|------|------|
| 创建应用 | 表单填写 ID/名称/平台/描述，自动初始化默认页面和 DSL |
| 应用列表 | 分页展示，模糊搜索，按名称/ID 筛选 |
| 应用设计 | 点击进入可视化编辑器，加载页面树和 DSL |
| 应用发布 | 所有页面 DSL 转 Vue3 源码，保存到 `publish/{projectId}/` |
| 应用删除 | 级联删除所有关联数据（二次确认） |

### 2.2 设计器核心功能

- **画布编辑**：拖拽放置、自由移动、缩放、网格吸附、参考线、层级管理
- **组件系统**：基础布局（Row/Col/Grid）、文本、图片、按钮、表单、表格、图表等 + 第三方物料
- **属性面板**：Props / Style / Events 分组配置，支持表达式编辑
- **事件系统**：click/change/submit 等事件 + 动作链（导航/弹窗/请求/赋值/条件）
- **页面管理**：多页面树形管理，支持拖拽排序、复制、删除
- **全局状态**：全局变量/状态定义，跨页面共享，支持 localStorage 持久化

### 2.3 API 接口规范

#### 设计器核心 API（`/__vtj__/api/:type.json`）

| 接口 | 说明 |
|------|------|
| `init` | 初始化/加载项目 |
| `saveProject` | 保存整个项目 DSL |
| `saveFile` | 保存单个页面 |
| `getFile` | 获取单个页面 |
| `removeFile` | 删除页面 |
| `saveHistory` / `getHistory` | 版本管理 |
| `getHistoryItem` / `saveHistoryItem` / `removeHistoryItem` | 历史项管理 |
| `saveMaterials` | 保存项目素材 |
| `publish` | 发布应用（生成 Vue 源码） |
| `getExtension` | 获取扩展配置 |
| `getStaticFiles` / `removeStaticFile` / `clearStaticFiles` | 静态文件管理 |

#### 开放平台 API（`/api/open/*`）

| 接口 | 说明 |
|------|------|
| `/auth/{sign}` | 登录鉴权 |
| `/user/{token}` | 用户信息 |
| `/templates` | 模板列表 |
| `/template/{token}` | 模板详情/删除/发布 |
| `/dict/{code}` | 业务字典 |
| `/topic/*` | AI 话题 |
| `/chat/*` | AI 聊天 |
| `/completions/{token}` | AI 流式输出 (SSE) |
| `/order/*` | AI 订单 |
| `/skills/{platform}` | 技能列表 |

所有开放 API 支持 JSONP 跨域。

#### 文件上传

| 接口 | 说明 |
|------|------|
| `/__vtj__/api/uploader.json` | multipart/form-data 文件上传 |
| `/api/oss/file/{filename}` | 静态文件访问 |

### 2.4 文件管理

### 2.5 数据模型

| 表名 | 说明 |
|------|------|
| `vtj_projects` | 项目表（JSONB 存储 DSL） |
| `vtj_files` | 页面文件表 |
| `vtj_histories` | 历史记录表 |
| `vtj_history_items` | 历史记录项表 |
| `vtj_materials` | 素材表 |
| `vtj_static_files` | 静态文件表 |
| `vtj_templates` | 模板表 |

---

## 三、技术架构

### 技术栈

| 模块 | 技术选型 |
|------|---------|
| 前端框架 | Vue 3 + TypeScript + Vite 5 |
| 状态管理 | Pinia |
| 后端框架 | Spring Boot 3.3.4 + Java 17 |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | PostgreSQL 16（JSONB） |
| 缓存 | Redis 7 |
| 连接池 | HikariCP |
| 部署 | Docker Compose |

### 系统架构

```
前端 (Vue 3) → Vite Dev Server (9528)
                   ↓ 代理
后端 (Spring Boot) → 9527
                   ↓
            PostgreSQL (5432) + Redis (6379)
```

### 非功能需求

| 维度 | 要求 |
|------|------|
| 性能 | 页面初始化 ≤2s，保存 ≤1s，P99 查询 ≤100ms |
| 可用性 | ≥99.9%，7×24 运行 |
| 安全 | Token+Sign 鉴权、SQL 注入防护、XSS 防护 |
| 兼容 | Chrome/Firefox/Edge/Safari 最新版 |

---

## 四、项目结构

```
low-code/
├── vtj/                          # 前端项目
│   ├── apps/
│   │   ├── app/                  # 管理端主应用
│   │   ├── pro/                  # 设计器平台
│   │   ├── h5/                   # H5 应用
│   │   ├── uniapp/               # UniApp 小程序
│   │   ├── material/             # 物料市场
│   │   ├── extension/            # 扩展插件
│   │   └── ...
│   ├── packages/                 # 共享包 (@vtj/core, renderer, etc.)
│   ├── dev/                      # 开发工具
│   └── create-vtj/               # 项目脚手架
│
└── vtj-server/                   # 后端项目
    ├── src/main/java/cn/cai/vtjserver/
    │   ├── VtjServerApplication.java
    │   ├── config/               # 配置类
    │   ├── controller/           # Controller 层
    │   ├── service/              # Service 层
    │   ├── mapper/               # MyBatis Mapper
    │   ├── entity/               # 实体类
    │   └── util/                 # 工具类
    ├── src/main/resources/
    │   └── schema.sql            # 数据库初始化脚本
    ├── docker-compose.yml        # Docker 编排
    ├── pom.xml                   # Maven 配置
    └── maven-settings.xml        # Maven 镜像配置
```

---

## 五、部署方式

### 本地开发

```bash
# 1. 启动数据库和缓存
cd vtj-server && docker compose up -d

# 2. 初始化数据库
docker exec -i vtj-postgres psql -U postgres -d vtj < src/main/resources/schema.sql

# 3. 启动后端
./mvnw -s maven-settings.xml spring-boot:run

# 4. 启动前端
cd ../vtj && corepack pnpm --filter @vtj/pro dev
```

### Docker 部署

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  vtj-server:
    build: ./vtj-server
    ports: ["9527:9527"]
    depends_on: [postgres, redis]
```

---

## 六、验收标准

| 编号 | 功能点 | 验收标准 |
|------|--------|----------|
| F1 | 创建应用 | 可创建 Web/H5/UniApp 应用，自动初始化页面 |
| F2 | 删除应用 | 级联删除所有关联数据 |
| F3 | 页面文件保存 | 保存/读取页面 DSL 到 PostgreSQL |
| F4 | 历史版本管理 | 支持版本保存、查看、回溯 |
| F5 | 素材管理 | 支持项目素材保存/读取 |
| F6 | 文件上传 | 支持图片等静态文件上传与访问 |
| F7 | 应用发布 | 生成 Vue3 源码到 publish 目录 |
| F8 | 扩展配置下发 | getExtension 接口返回正确配置 |
| F9 | 设计器初始化 | 前端打开设计器后正常加载项目 |
| F10 | 多页面管理 | 支持创建/切换/删除多个页面 |
| F11 | 前端代理 | 开发时 API 请求正确转发到后端 |
| F12 | Open API 接口 | 登录/用户/模板/字典等接口返回正常 |

---

## 七、风险与后续迭代

- Redis 不可用时降级到数据库直连（已实现）
- 模板市场为占位实现，需对接真实数据源
- 多租户隔离、软删除、在线协作编辑、AI 辅助代码生成为后续迭代方向

---

*本文档由 VTJ 全栈开发 Agent 生成，已同步提交到前后端 Git 仓库并推送至 GitHub 远程仓库。*