package cn.cai.vtjserver.service;

import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.entity.TemplateEntity;
import cn.cai.vtjserver.mapper.TemplateMapper;
import cn.cai.vtjserver.util.Jsons;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OpenApiService {
    private static final String TOKEN_PREFIX = "vtj:auth:";

    private final TemplateMapper templateMapper;
    private final RedisCacheService cacheService;

    public ApiResponse<?> login(String sign) {
        String token = sign == null || sign.isBlank() ? UUID.randomUUID().toString().replace("-", "") : sign;
        Map<String, Object> user = localUser(token);
        cacheService.put(TOKEN_PREFIX + token, Jsons.string(user), Duration.ofDays(7));
        return ApiResponse.ok(user);
    }

    public ApiResponse<?> user(String token) {
        return cacheService.get(TOKEN_PREFIX + token)
                .map(Jsons::parseMap)
                .<ApiResponse<?>>map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.ok(localUser(token)));
    }

    public ApiResponse<?> templates(String platform) {
        List<Map<String, Object>> rows = templateMapper.selectList(new LambdaQueryWrapper<TemplateEntity>()
                        .eq(platform != null && !platform.isBlank(), TemplateEntity::getPlatform, platform)
                        .orderByDesc(TemplateEntity::getUpdatedAt))
                .stream()
                .map(this::templateDto)
                .toList();
        return ApiResponse.ok(rows);
    }

    public ApiResponse<?> template(String id) {
        TemplateEntity entity = templateMapper.selectById(id);
        return ApiResponse.ok(entity == null ? null : templateDto(entity));
    }

    public ApiResponse<?> templateDsl(String id) {
        TemplateEntity entity = templateMapper.selectById(id);
        return ApiResponse.ok(entity == null ? null : entity.getDsl());
    }

    public ApiResponse<?> removeTemplate(String id) {
        if (id != null) {
            templateMapper.deleteById(id);
        }
        return ApiResponse.ok(true);
    }

    public ApiResponse<?> dict(String code) {
        if ("LLM".equalsIgnoreCase(code)) {
            return ApiResponse.ok(List.of(
                    Map.of("label", "VTJ Agent", "value", "gpt-5.5"),
                    Map.of("label", "Coding Agent", "value", "coding"),
                    Map.of("label", "Design Agent", "value", "multimodal")
            ));
        }
        if ("TemplateCategory".equalsIgnoreCase(code)) {
            return ApiResponse.ok(List.of(
                    Map.of("label", "Page", "value", "page"),
                    Map.of("label", "Block", "value", "block"),
                    Map.of("label", "Form", "value", "form"),
                    Map.of("label", "Modal", "value", "modal"),
                    Map.of("label", "List", "value", "list"),
                    Map.of("label", "Chart", "value", "chart")
            ));
        }
        if ("PlatformType".equalsIgnoreCase(code)) {
            return ApiResponse.ok(List.of(
                    Map.of("label", "Web (PC)", "value", "web"),
                    Map.of("label", "H5 (Mobile)", "value", "h5"),
                    Map.of("label", "UniApp", "value", "uniapp"),
                    Map.of("label", "WeChat Mini", "value", "weapp")
            ));
        }
        if ("ProjectStatus".equalsIgnoreCase(code)) {
            return ApiResponse.ok(List.of(
                    Map.of("label", "Development", "value", "developing"),
                    Map.of("label", "Published", "value", "published"),
                    Map.of("label", "Offline", "value", "offline")
            ));
        }
        return ApiResponse.ok(List.of());
    }

    public ApiResponse<?> publishTemplate(Map<String, String> form, MultipartFile cover) {
        String id = form.getOrDefault("id", UUID.randomUUID().toString().replace("-", ""));
        TemplateEntity entity = new TemplateEntity();
        entity.setId(id);
        entity.setPlatform(form.getOrDefault("platform", "web"));
        entity.setCategory(form.getOrDefault("category", "page"));
        entity.setTitle(form.getOrDefault("title", form.getOrDefault("name", "Template")));
        entity.setDescription(form.getOrDefault("description", ""));
        entity.setCover(cover == null ? form.get("cover") : cover.getOriginalFilename());
        entity.setCreator("local");
        entity.setDsl(form.containsKey("dsl") ? Jsons.parseMap(form.get("dsl")) : Map.of());
        entity.setUpdatedAt(OffsetDateTime.now());
        if (templateMapper.selectById(id) == null) {
            entity.setCreatedAt(OffsetDateTime.now());
            templateMapper.insert(entity);
        } else {
            templateMapper.updateById(entity);
        }
        return ApiResponse.ok(templateDto(entity));
    }

    public ApiResponse<?> report() {
        return ApiResponse.ok(true);
    }

    public ApiResponse<?> skills(String platform, Object ids) {
        Set<String> requested = skillIds(ids);
        Map<String, String> docs = skillDocs(platform);
        List<String> selected = new ArrayList<>();
        if (requested.isEmpty()) {
            selected.add(catalog(docs));
        } else {
            requested.forEach(id -> {
                String key = normalizeSkillId(id);
                selected.add(docs.getOrDefault(key, docs.get("vtj-agent")));
            });
        }
        return ApiResponse.ok(String.join("\n\n---\n\n", selected));
    }

    public ApiResponse<?> successObject(Map<String, Object> data) {
        return ApiResponse.ok(data);
    }

    private Map<String, Object> localUser(String token) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "local");
        user.put("name", "Local Developer");
        user.put("avatar", "");
        user.put("token", token);
        user.put("permissions", Map.of());
        return user;
    }

    private Set<String> skillIds(Object ids) {
        Set<String> result = new LinkedHashSet<>();
        if (ids instanceof List<?> list) {
            list.forEach(item -> result.add(String.valueOf(item)));
        } else if (ids instanceof String text && !text.isBlank()) {
            result.add(text);
        } else if (ids instanceof Map<?, ?> map) {
            Object value = map.get("ids");
            if (value instanceof List<?> list) {
                list.forEach(item -> result.add(String.valueOf(item)));
            }
        }
        return result;
    }

    private String normalizeSkillId(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "dsl", "vtj-dsl", "schema", "component" -> "vtj-dsl";
            case "event", "events", "binding", "bindings" -> "events";
            case "api", "apis", "request", "data-source", "datasource" -> "apis";
            case "page", "pages", "route", "menu" -> "pages";
            case "template", "templates", "block", "blocks" -> "templates";
            case "pinia", "store", "state" -> "pinia";
            case "access", "auth", "permission" -> "access";
            case "axios", "http" -> "axios";
            case "globals", "global", "env", "i18n" -> "globals";
            case "selected", "selection", "selected-component", "component-edit" -> "selected-component";
            case "protocol", "agent", "vtj-agent" -> "vtj-agent";
            default -> value;
        };
    }

    private String catalog(Map<String, String> docs) {
        return """
                # VTJ Agent Skill Catalog

                可用技能 ID：
                - vtj-agent：输出协议、工具调用方式、Agent 执行约束
                - vtj-dsl：VTJ 页面/节点 DSL 结构、属性、事件、指令、数据源
                - selected-component：选中组件的局部设计、NodeSchema 输出与安全应用约束
                - events：组件事件绑定、JSFunction / JSExpression 写法
                - apis：接口定义、请求配置、组件绑定接口数据
                - pages：页面、目录、布局、首页和菜单维护
                - templates：页面模板、区块模板维护建议
                - pinia：全局状态 / Pinia 配置
                - access：权限控制配置
                - axios：全局请求、请求/响应拦截器配置
                - globals：全局 CSS、环境变量、i18n、路由守卫

                调用 getSkills 时传入上述 ID 可获取详细说明。
                """;
    }

    private Map<String, String> skillDocs(String platform) {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("vtj-agent", """
                # VTJ Agent Output Protocol

                你是 VTJ.PRO 设计器 Agent。用户要求修改页面、模板、接口、事件、权限或全局配置时，优先输出工具调用，而不是普通说明。

                输出格式：
                - 需要执行前端工具：输出 `A:` 加一个 json 代码块：
                  ```json
                  {"action":"工具名","parameters":[参数1, 参数2]}
                  ```
                - 需要全量生成 Vue SFC：输出 `A:` 加 `vue` 代码块。
                - 需要增量修改当前源码：输出 `A:` 加 `diff` 代码块。
                - 只是说明或任务已完成：输出 `F:` 开头的最终答复。
                - 需要先规划再执行：输出 `P:`，后续必须继续给出 `A:`。

                一次回复只执行一个明确动作。多个动作按“创建/读取 -> 更新 -> 刷新/保存”的顺序分多轮完成。
                """);
        docs.put("vtj-dsl", """
                # VTJ DSL Skill

                页面文件由 PageFile 维护，页面内容由 BlockSchema/NodeSchema 维护。
                常见字段：
                - PageFile：id, name, title, icon, dir, layout, hidden, needLogin, pure, raw, children
                - BlockSchema：id, name, state, methods, computed, watch, css, props, emits, slots, dataSources, nodes
                - NodeSchema：id, name, from, props, directives, events, children, invisible, locked

                生成组件配置时：
                - props 写静态值、JSExpression 或 JSFunction。
                - events 绑定事件处理器，value 通常是函数体或方法名。
                - dataSources 用于接口数据源，组件 props 再通过 JSExpression 引用状态。
                - 复杂页面建议用 `createPage` 创建页面，再用 Vue SFC 或 diff 生成结构。
                """);
        docs.put("selected-component", """
                # VTJ Selected Component Design Skill

                当请求上下文包含 selection 时，selection 是不可变的修改边界。只允许修改其中 nodeId
                对应的 NodeSchema 子树，禁止修改页面根 DSL、兄弟节点、父节点、全局样式、路由、接口或其他文件。

                输出必须是 `A:` 加一个完整的 `vtj-node` JSON 代码块：
                ```vtj-node
                {
                  "name": "ElButton",
                  "from": "element-plus",
                  "props": { "type": "primary" },
                  "events": {
                    "click": {
                      "name": "click",
                      "handler": { "type": "JSFunction", "value": "function () { this.$message.success('done') }" }
                    }
                  },
                  "directives": [],
                  "children": "保存"
                }
                ```

                约束：
                - 根节点 name 和 from 必须与 selection.dsl 一致，根 id 由前端保留，不得改组件类型。
                - 返回完整 NodeSchema；未修改的 props/events/directives/children 也要保留。
                - 动态属性使用 `{ "type": "JSExpression", "value": "state.value" }`。
                - 事件处理器使用 `{ "type": "JSFunction", "value": "function (...) { ... }" }`。
                - children 可以是字符串、JSExpression 或 NodeSchema 数组；子节点必须使用真实物料组件名和来源。
                - 不得返回整页 `vue`、`diff` 或会修改页面其他区域的工具调用。
                """);
        docs.put("events", """
                # VTJ Events Skill

                组件事件绑定目标是 NodeSchema.events 或组件 methods。
                推荐：
                - 简单事件：绑定到 methods 中的方法名。
                - 复杂逻辑：在 BlockSchema.methods 新增 JSFunction，再在 events 中引用。
                - 表单、按钮、表格操作要明确事件名，如 click, change, submit, selection-change。
                - 事件内调用接口时优先通过项目 api/dataSources，而不是硬编码 fetch。
                """);
        docs.put("apis", """
                # VTJ API Skill

                接口维护使用 `setApi/getApis/removeApi/removeApis` 工具。ApiSchema 应包含：
                - name：接口标识
                - label/description：人类可读说明
                - method：GET/POST/PUT/DELETE 等
                - url：接口地址
                - settings：请求配置，如 type, loading, failMessage, validSuccess, originResponse
                - mock/headers/query/body：按需求补充

                组件绑定接口数据：
                - 先创建 api，再在页面 dataSources/state/methods 中消费。
                - 表格列表通常绑定 loading、data、pagination；按钮事件触发 query/save/remove。
                """);
        docs.put("pages", """
                # VTJ Pages Skill

                页面维护工具：
                - getMenus/getPages：查看页面与菜单树
                - createPage：新建页面、目录、布局
                - updatePage：修改名称、标题、图标、登录需求、隐藏状态
                - movePage/removePage/setHomepage/active：维护层级、删除、设置首页、激活页面

                web 平台支持目录和布局页；uniapp 平台不支持目录/布局，创建时 dir/layout 必须为 false。
                当前平台：%s
                """.formatted(platform == null ? "web" : platform));
        docs.put("templates", """
                # VTJ Templates Skill

                模板/区块维护：
                - getBlocks/createBlock/updateBlock/removeBlock 维护区块模板。
                - 发布模板时保持 name/title/category/platform/cover/dsl 清晰。
                - 页面模板应包含完整 BlockSchema；区块模板应可嵌入当前页面节点。
                - 修改模板前先读取现有内容，避免覆盖用户已有 DSL。
                """);
        docs.put("pinia", """
                # Pinia Global Store Skill

                使用 `setGlobalStore` 写入函数：
                ```js
                (app) => ({
                  state: () => ({ user: null }),
                  getters: {},
                  actions: {}
                })
                ```
                页面组件通过全局 store 或 provider 访问状态，避免散落在多个节点内。
                """);
        docs.put("access", """
                # Access Skill

                使用 `setGlobalAccess` 写入 AccessOptions 工厂函数。常见字段：
                session, authKey, storageKey, auth, whiteList, redirectParam,
                unauthorizedCode, unauthorizedMessage, noPermissionMessage, statusKey。

                页面是否需要登录由 PageFile.needLogin 控制，按钮/菜单级权限可通过 JSExpression 读取 access.can。
                """);
        docs.put("axios", """
                # Axios Skill

                使用 `setGlobalAxios` 配置请求：
                ```js
                (app) => ({
                  baseURL: '/',
                  timeout: 60000,
                  settings: {
                    type: 'json',
                    validSuccess: true,
                    originResponse: false,
                    loading: true,
                    failMessage: true,
                    validate: (res) => res.data?.code === 0 || !!res.data?.success
                  }
                })
                ```
                使用 `setGlobalRequestInterceptor` / `setGlobalResponseInterceptor` 处理 token、错误和信封。
                """);
        docs.put("globals", """
                # VTJ Globals Skill

                全局维护工具：
                - setGlobalCss/getGlobalCss：应用级样式
                - createEnv/removeEnv/getEnv：环境变量
                - createI18nMessage/removeI18nMessage/getI18nMessage：i18n 词条
                - setGlobalBeforeEach/setGlobalAfterEach：路由守卫
                - setUniConfig/getUniConfig：uniapp pages.json/manifest/App.vue 生命周期

                生成全局代码时必须输出完整 JS 函数，避免只输出片段。
                """);
        docs.put("vtj-agent", """
                # VTJ Agent Output Protocol

                You are operating the VTJ.PRO designer. Output executable actions, not prose, when
                the user asks to create or modify pages, templates, APIs, events, permissions, global
                configuration, or DSL.

                ## Actions
                - Tool call: output `A:` plus one `json` code block.
                - Full page generation: output `A:` plus one `vue` code block with template/script/style.
                - Current page update: output `A:` plus one `diff` code block.
                - Selected component update: output `A:` plus one complete `vtj-node` JSON code block.
                - Finished: output `F:`.
                - One response may contain at most one executable `A:` block.

                ## Staged page generation
                For complex pages, generate safely in multiple turns:
                1. First create a visible layout skeleton with stable class names and placeholders.
                2. After the frontend reports `O:` success, update exactly one section/component per turn.
                3. Use `diff` against the current Vue source for section updates.
                4. If one section fails, retry only that section. Do not regenerate the whole page.
                5. Finish with `F:` after all sections are complete.

                ## Executable workflows
                - New page: call `createPage`; after the returned `O:` contains its id and the page is
                  active, output a Vue SFC for that page.
                - New reusable component: call `createBlock`; after activation, output its Vue SFC.
                - Existing page/component: call `getCurrentFileContent` when source is missing, then
                  use one focused `diff` block.
                - Data behavior: call `setApi` before wiring the API into state, methods, dataSources,
                  events, loading states, and error handling.
                - JavaScript behavior: use `defineComponent`, `reactive` state, `methods`, and
                  `computed` so the generated behavior is preserved in VTJ DSL. Avoid script-setup-only
                  state for executable pages.
                - Verification: after the frontend applies generated DSL, call `refresh`; fix only the
                  reported runtime error, then continue. With auto-apply enabled, each successful stage
                  is rendered in the designer before the next Agent turn.
                """);
        return docs;
    }

    public ApiResponse<?> successList() {
        return ApiResponse.ok(List.of());
    }

    private Map<String, Object> templateDto(TemplateEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("platform", entity.getPlatform());
        dto.put("category", entity.getCategory());
        dto.put("title", entity.getTitle());
        dto.put("name", entity.getTitle());
        dto.put("description", entity.getDescription());
        dto.put("cover", entity.getCover());
        dto.put("creator", entity.getCreator());
        dto.put("createdAt", entity.getCreatedAt());
        dto.put("updatedAt", entity.getUpdatedAt());
        return dto;
    }
}
