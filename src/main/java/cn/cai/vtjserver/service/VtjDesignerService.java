package cn.cai.vtjserver.service;

import cn.cai.vtjserver.config.VtjProperties;
import cn.cai.vtjserver.dto.ApiRequest;
import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.entity.FileEntity;
import cn.cai.vtjserver.entity.HistoryEntity;
import cn.cai.vtjserver.entity.HistoryItemEntity;
import cn.cai.vtjserver.entity.MaterialEntity;
import cn.cai.vtjserver.entity.ProjectEntity;
import cn.cai.vtjserver.entity.StaticFileEntity;
import cn.cai.vtjserver.exception.BusinessException;
import cn.cai.vtjserver.mapper.FileMapper;
import cn.cai.vtjserver.mapper.HistoryItemMapper;
import cn.cai.vtjserver.mapper.HistoryMapper;
import cn.cai.vtjserver.mapper.MaterialMapper;
import cn.cai.vtjserver.mapper.ProjectMapper;
import cn.cai.vtjserver.mapper.StaticFileMapper;
import cn.cai.vtjserver.util.Jsons;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core persistence service backing the VTJ designer's local dispatch protocol
 * ({@code POST /__vtj__/api/{type}.json}). It owns the low-code designer loop: project bootstrap
 * ({@code init}), DSL read/write for projects, files, histories and materials, static-file upload,
 * and one-shot publishing of generated Vue sources.
 *
 * <p>PostgreSQL is authoritative; Redis is a best-effort accelerator only. All DSL payloads are
 * persisted verbatim as JSONB so the frontend contract is never lossily reshaped on the backend.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VtjDesignerService {
    private static final String PROJECT_CACHE_PREFIX = "vtj:project:";
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("(?is)<template[^>]*>(.*?)</template>");
    private static final Pattern STYLE_PATTERN = Pattern.compile("(?is)<style[^>]*>(.*?)</style>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<!--.*?-->|<![^>]*>|<(/?)([A-Za-z][\\w:-]*)([^>]*)>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("([:@#A-Za-z_][\\w:.-]*)(?:\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "param", "source", "track", "wbr");
    private static final Set<String> HTML_TAGS = Set.of(
            "a", "article", "aside", "audio", "b", "blockquote", "body", "button", "canvas",
            "caption", "code", "colgroup", "dd", "details", "dialog", "div", "dl", "dt",
            "em", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hr", "html", "i", "iframe", "img", "input",
            "label", "legend", "li", "main", "nav", "ol", "option", "p", "picture", "pre",
            "section", "select", "small", "source", "span", "strong", "svg", "table", "tbody",
            "td", "template", "textarea", "tfoot", "th", "thead", "tr", "ul", "video");

    private final ProjectMapper projectMapper;
    private final FileMapper fileMapper;
    private final HistoryMapper historyMapper;
    private final HistoryItemMapper historyItemMapper;
    private final MaterialMapper materialMapper;
    private final StaticFileMapper staticFileMapper;
    private final RedisCacheService cacheService;
    private final VtjProperties properties;

    /**
     * Routes a designer dispatch request to the matching persistence handler.
     *
     * <p>The dispatch {@code type} is taken from the request body when present, otherwise from the
     * URL path segment (with the trailing {@code .json} stripped). Unknown types yield a
     * {@code success:false} envelope rather than an error, matching the frontend's contract.
     *
     * @param pathType the {@code {type}} path segment, e.g. {@code saveFile.json}
     * @param request  the decoded request body (never {@code null}; caller substitutes an empty one)
     * @param query    query-string parameters, exposed to handlers via {@link ApiRequest#getQuery()}
     * @return the unified response envelope for the resolved handler
     */
    @Transactional
    public ApiResponse<?> dispatch(String pathType, ApiRequest request, Map<String, Object> query) {
        String type = request.getType() == null ? stripJson(pathType) : request.getType();
        request.setQuery(query);
        return switch (type) {
            case "getExtension" -> ApiResponse.ok(getExtension());
            case "getProjects" -> ApiResponse.ok(getProjects());
            case "removeProject" -> ApiResponse.ok(removeProject(String.valueOf(request.getData())));
            case "init" -> ApiResponse.ok(init(Jsons.map(request.getData())));
            case "saveProject" -> ApiResponse.ok(saveProject(Jsons.map(request.getData())));
            case "saveFile" -> ApiResponse.ok(saveFile(Jsons.map(request.getData())));
            case "getFile" -> ApiResponse.ok(getFile(String.valueOf(request.getData())));
            case "removeFile" -> ApiResponse.ok(removeFile(String.valueOf(request.getData())));
            case "saveHistory" -> ApiResponse.ok(saveHistory(Jsons.map(request.getData())));
            case "getHistory" -> ApiResponse.ok(getHistory(String.valueOf(request.getData())));
            case "removeHistory" -> ApiResponse.ok(removeHistory(String.valueOf(request.getData())));
            case "getHistoryItem" -> ApiResponse.ok(getHistoryItem(Jsons.map(request.getData())));
            case "saveHistoryItem" -> ApiResponse.ok(saveHistoryItem(Jsons.map(request.getData())));
            case "removeHistoryItem" -> ApiResponse.ok(removeHistoryItems(Jsons.map(request.getData())));
            case "saveMaterials" -> ApiResponse.ok(saveMaterials(Jsons.map(request.getData())));
            case "publish" -> ApiResponse.ok(publish(String.valueOf(request.getData())));
            case "publishFile", "createRawPage", "removeRawPage" -> ApiResponse.ok(true);
            case "genVueContent" -> ApiResponse.ok(genVueContent(Jsons.map(request.getData())));
            case "parseVue" -> ApiResponse.ok(parseVue(Jsons.map(request.getData())));
            case "getStaticFiles" -> ApiResponse.ok(getStaticFiles(String.valueOf(request.getData())));
            case "removeStaticFile" -> ApiResponse.ok(removeStaticFile(Jsons.map(request.getData())));
            case "clearStaticFiles" -> ApiResponse.ok(clearStaticFiles(String.valueOf(request.getData())));
            default -> ApiResponse.fail("No handler for type: " + type, null);
        };
    }

    /**
     * Publishes every file of a project as a generated {@code .vue} source into the storage root.
     *
     * @param projectId target project; a blank id is treated as a no-op failure
     * @return {@code true} when all files were written, {@code false} when the id is blank or IO failed
     */
    public boolean publish(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        List<FileEntity> files = fileMapper.selectList(new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getProjectId, projectId));

        Path publishDir = Path.of(properties.getStorage().getRoot(), "publish", projectId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(publishDir);
            for (FileEntity file : files) {
                String content = genVueContent(Map.of("dsl", file.getDsl()));
                Files.writeString(publishDir.resolve(file.getName() + ".vue"), content);
            }
            return true;
        } catch (IOException e) {
            // Surface the failure in logs rather than swallowing it (AGENTS.md §7); the dispatch
            // contract still expects a boolean result rather than a propagated 5xx here.
            log.error("Failed to publish project {} to {}", projectId, publishDir, e);
            return false;
        }
    }

    /** @return a lightweight list (id/name/description/platform/updatedAt) of every stored project */
    public List<Map<String, Object>> getProjects() {
        return projectMapper.selectList(null).stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("description", p.getDescription());
            map.put("platform", p.getPlatform());
            map.put("updatedAt", p.getUpdatedAt());
            return map;
        }).toList();
    }

    /**
     * Cascading project delete: removes the project and all of its files, histories, materials,
     * static-file records and cached DSL in a single transaction.
     *
     * @param id project id
     * @return {@code true} when a non-blank id was processed, {@code false} otherwise
     */
    @Transactional
    public boolean removeProject(String id) {
        if (id == null || id.isBlank()) return false;
        projectMapper.deleteById(id);
        fileMapper.delete(new LambdaQueryWrapper<FileEntity>().eq(FileEntity::getProjectId, id));
        historyMapper.delete(new LambdaQueryWrapper<HistoryEntity>().eq(HistoryEntity::getProjectId, id));
        materialMapper.deleteById(id);
        staticFileMapper.delete(new LambdaQueryWrapper<StaticFileEntity>().eq(StaticFileEntity::getProjectId, id));
        cacheService.delete(PROJECT_CACHE_PREFIX + id);
        return true;
    }

    public Map<String, Object> getExtension() {
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("auth", properties.getProject().getRemote() + "/#/login");
        access.put("storageKey", "RRO_IDE_ACCESS_STORAGE__");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("remote", properties.getProject().getRemote());
        config.put("history", "hash");
        config.put("base", "/");
        config.put("pageRouteName", "page");
        config.put("platform", properties.getProject().getDefaultPlatform());
        config.put("auth", properties.getProject().getAuthSign());
        config.put("__BASE_PATH__", properties.getProject().getStaticBase());
        config.put("__ACCESS__", access);
        config.put("checkVersion", false);
        return config;
    }

    /**
     * Bootstraps or reloads a project. When the project already exists its stored DSL is enriched
     * with runtime fields and cached; otherwise a fresh project with a default launch page is
     * created and persisted.
     *
     * @param input request data carrying at least {@code id} and optionally {@code platform}/{@code name}
     * @return the project DSL the designer should load
     */
    public Map<String, Object> init(Map<String, Object> input) {
        String id = Jsons.text(input, "id", properties.getProject().getDefaultId());
        String platform = Jsons.text(input, "platform", properties.getProject().getDefaultPlatform());
        ProjectEntity existing = projectMapper.selectById(id);
        if (existing != null) {
            Map<String, Object> dsl = new LinkedHashMap<>(existing.getDsl());
            dsl.put("id", existing.getId());
            dsl.put("name", existing.getName());
            dsl.put("description", existing.getDescription());
            dsl.put("platform", existing.getPlatform());
            dsl.put("__BASE_PATH__", properties.getProject().getStaticBase());
            dsl.putIfAbsent("__UID__", UUID.randomUUID().toString().replace("-", ""));
            cacheService.put(PROJECT_CACHE_PREFIX + id, Jsons.string(dsl), Duration.ofMinutes(30));
            return dsl;
        }

        String fileId = shortId();
        Map<String, Object> pageFile = launchPageFile(fileId);
        Map<String, Object> pageDsl = launchPageDsl(fileId);
        pageDsl.put("projectId", id);
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", id);
        project.put("name", Jsons.text(input, "name", properties.getProject().getDefaultName()));
        project.put("description", Jsons.text(input, "description", ""));
        project.put("platform", platform);
        project.put("dependencies", input.getOrDefault("dependencies", List.of()));
        project.put("apis", List.of());
        project.put("meta", List.of());
        project.put("globals", Map.of());
        project.put("blocks", List.of());
        project.put("pages", List.of(pageFile));
        project.put("homepage", fileId);
        project.put("__BASE_PATH__", properties.getProject().getStaticBase());
        project.put("__UID__", UUID.randomUUID().toString().replace("-", ""));

        upsertProject(project);
        saveFile(pageDsl);
        return project;
    }

    /**
     * Upserts a project's DSL.
     *
     * @param dsl full project DSL; must carry at least an {@code id} to avoid clobbering the default project
     * @return {@code true} once persisted
     * @throws BusinessException when the payload is empty
     */
    public boolean saveProject(Map<String, Object> dsl) {
        if (dsl == null || dsl.isEmpty()) {
            throw new BusinessException("saveProject requires a non-empty project DSL");
        }
        upsertProject(dsl);
        return true;
    }

    /**
     * Persists a file DSL, inserting when absent and updating otherwise. A missing id is generated.
     *
     * @param dsl the file DSL payload; {@code id}/{@code name}/{@code projectId} are read from it
     * @return always {@code true} once the row is written
     */
    public boolean saveFile(Map<String, Object> dsl) {
        String id = Jsons.text(dsl, "id", shortId());
        dsl.put("id", id);
        FileEntity entity = new FileEntity();
        entity.setId(id);
        entity.setProjectId(Jsons.text(dsl, "projectId", properties.getProject().getDefaultId()));
        entity.setPlatform(properties.getProject().getDefaultPlatform());
        entity.setName(Jsons.text(dsl, "name", id));
        entity.setDsl(dsl);
        entity.setUpdatedAt(OffsetDateTime.now());
        if (fileMapper.selectById(id) == null) {
            entity.setCreatedAt(OffsetDateTime.now());
            fileMapper.insert(entity);
        } else {
            fileMapper.updateById(entity);
        }
        return true;
    }

    /**
     * Reads a file's DSL by id.
     *
     * @param id file id
     * @return the stored DSL map, or {@code null} when no such file exists
     */
    public Map<String, Object> getFile(String id) {
        FileEntity file = fileMapper.selectById(id);
        return file == null ? null : file.getDsl();
    }

    /**
     * Removes a file by id. The delete is idempotent: removing an already-absent file still acks.
     *
     * @param id file id
     * @return {@code true} when a non-blank id was processed, {@code false} for a blank id
     */
    public boolean removeFile(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        fileMapper.deleteById(id);
        return true;
    }

    public boolean saveHistory(Map<String, Object> history) {
        String id = Jsons.text(history, "id", "");
        if (id.isBlank()) {
            return false;
        }
        HistoryEntity entity = new HistoryEntity();
        entity.setId(id);
        entity.setProjectId(properties.getProject().getDefaultId());
        entity.setHistory(history);
        entity.setUpdatedAt(OffsetDateTime.now());
        if (historyMapper.selectById(id) == null) {
            entity.setCreatedAt(OffsetDateTime.now());
            historyMapper.insert(entity);
        } else {
            historyMapper.updateById(entity);
        }
        return true;
    }

    public Map<String, Object> getHistory(String id) {
        HistoryEntity history = historyMapper.selectById(id);
        return history == null ? Map.of() : history.getHistory();
    }

    public boolean removeHistory(String id) {
        historyMapper.deleteById(id);
        return true;
    }

    public Map<String, Object> getHistoryItem(Map<String, Object> data) {
        HistoryItemEntity entity = historyItemMapper.selectOne(new LambdaQueryWrapper<HistoryItemEntity>()
                .eq(HistoryItemEntity::getFileId, Jsons.text(data, "fId", ""))
                .eq(HistoryItemEntity::getId, Jsons.text(data, "id", ""))
                .last("LIMIT 1"));
        return entity == null ? Map.of() : entity.getItem();
    }

    public boolean saveHistoryItem(Map<String, Object> data) {
        String fileId = Jsons.text(data, "fId", "");
        Map<String, Object> item = Jsons.map(data.get("item"));
        String id = Jsons.text(item, "id", shortId());
        item.put("id", id);
        historyItemMapper.upsert(fileId, id, item);
        return true;
    }

    @SuppressWarnings("unchecked")
    public boolean removeHistoryItems(Map<String, Object> data) {
        String fileId = Jsons.text(data, "fId", "");
        Object ids = data.get("ids");
        if (ids instanceof List<?> list) {
            for (Object id : list) {
                historyItemMapper.deleteByFileAndId(fileId, String.valueOf(id));
            }
        }
        return true;
    }

    public boolean saveMaterials(Map<String, Object> data) {
        Map<String, Object> project = Jsons.map(data.get("project"));
        String projectId = Jsons.text(project, "id", properties.getProject().getDefaultId());
        MaterialEntity entity = new MaterialEntity();
        entity.setProjectId(projectId);
        entity.setMaterials(Jsons.map(data.get("materials")));
        entity.setUpdatedAt(OffsetDateTime.now());
        if (materialMapper.selectById(projectId) == null) {
            materialMapper.insert(entity);
        } else {
            materialMapper.updateById(entity);
        }
        return true;
    }

    public String genVueContent(Map<String, Object> data) {
        Map<String, Object> dsl = Jsons.map(data.get("dsl"));
        String name = Jsons.text(dsl, "name", "VtjPage");
        String css = Jsons.text(dsl, "css", "");
        String template = nodesToHtml(dsl.get("nodes"), 2);
        if (template.isBlank()) {
            template = "  <div class=\"vtj-generated-page\">" + escapeHtml(name) + "</div>";
        }
        return """
                <template>
                %s
                </template>

                <script setup>
                </script>

                <style scoped>
                %s
                </style>
                """.formatted(template, css);
    }

    public Map<String, Object> parseVue(Map<String, Object> data) {
        String id = Jsons.text(data, "id", shortId());
        String name = Jsons.text(data, "name", "ParsedPage");
        String source = Jsons.text(data, "source", "");
        String template = extractTemplate(source);
        List<Map<String, Object>> nodes = htmlToNodes(template);
        if (nodes.isEmpty()) {
            nodes = List.of(textNode("div", visibleFallbackText(template, name), Map.of("class", "vtj-ai-generated")));
        }

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("id", id);
        dsl.put("name", name);
        dsl.put("locked", false);
        dsl.put("inject", List.of());
        dsl.put("state", Map.of());
        dsl.put("lifeCycles", Map.of());
        dsl.put("methods", Map.of());
        dsl.put("computed", Map.of());
        dsl.put("watch", List.of());
        dsl.put("css", extractStyles(source));
        dsl.put("props", List.of());
        dsl.put("emits", List.of());
        dsl.put("slots", List.of());
        dsl.put("dataSources", Map.of());
        dsl.put("__VTJ_BLOCK__", true);
        dsl.put("__VERSION__", String.valueOf(System.currentTimeMillis()));
        dsl.put("nodes", nodes);
        return dsl;
    }

    public List<Map<String, Object>> saveUploadedFiles(MultipartFile[] files, String projectId) throws IOException {
        Path root = Path.of(properties.getStorage().getStaticDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String original = file.getOriginalFilename() == null ? "file" : Path.of(file.getOriginalFilename()).getFileName().toString();
            String id = UUID.randomUUID().toString().replace("-", "");
            String filename = id + "-" + original;
            Path target = root.resolve(filename).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Invalid upload path");
            }
            file.transferTo(target);

            StaticFileEntity entity = new StaticFileEntity();
            entity.setId(id);
            entity.setProjectId(projectId == null ? properties.getProject().getDefaultId() : projectId);
            entity.setFilename(original);
            entity.setFilepath("/api/oss/file/" + filename);
            entity.setContentType(file.getContentType());
            entity.setSizeBytes(file.getSize());
            entity.setCreatedAt(OffsetDateTime.now());
            staticFileMapper.insert(entity);

            result.add(Map.of("id", id, "filename", original, "filepath", entity.getFilepath()));
        }
        return result;
    }

    public List<Map<String, Object>> getStaticFiles(String projectId) {
        String pid = projectId == null || projectId.isBlank() || "null".equals(projectId)
                ? properties.getProject().getDefaultId() : projectId;
        return staticFileMapper.selectList(new LambdaQueryWrapper<StaticFileEntity>()
                        .eq(StaticFileEntity::getProjectId, pid))
                .stream()
                .map(file -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", file.getId());
                    item.put("filename", file.getFilename());
                    item.put("filepath", file.getFilepath());
                    return item;
                })
                .toList();
    }

    public boolean removeStaticFile(Map<String, Object> data) {
        String name = Jsons.text(data, "name", "");
        StaticFileEntity entity = staticFileMapper.selectOne(new LambdaQueryWrapper<StaticFileEntity>()
                .eq(StaticFileEntity::getFilename, name)
                .last("LIMIT 1"));
        if (entity != null) {
            staticFileMapper.deleteById(entity.getId());
        }
        return true;
    }

    public boolean clearStaticFiles(String projectId) {
        String pid = projectId == null || projectId.isBlank() || "null".equals(projectId)
                ? properties.getProject().getDefaultId() : projectId;
        staticFileMapper.delete(new LambdaQueryWrapper<StaticFileEntity>().eq(StaticFileEntity::getProjectId, pid));
        return true;
    }

    public Path staticFilePath(String filename) {
        return Path.of(properties.getStorage().getStaticDir()).toAbsolutePath().normalize()
                .resolve(Path.of(filename).getFileName().toString()).normalize();
    }

    private void upsertProject(Map<String, Object> dsl) {
        String id = Jsons.text(dsl, "id", properties.getProject().getDefaultId());
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(Jsons.text(dsl, "name", properties.getProject().getDefaultName()));
        entity.setDescription(Jsons.text(dsl, "description", ""));
        entity.setPlatform(Jsons.text(dsl, "platform", properties.getProject().getDefaultPlatform()));
        entity.setDsl(dsl);
        entity.setUpdatedAt(OffsetDateTime.now());
        if (projectMapper.selectById(id) == null) {
            entity.setCreatedAt(OffsetDateTime.now());
            projectMapper.insert(entity);
        } else {
            projectMapper.updateById(entity);
        }
        cacheService.put(PROJECT_CACHE_PREFIX + id, Jsons.string(dsl), Duration.ofMinutes(30));
    }

    private Map<String, Object> launchPageFile(String id) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", id);
        page.put("dir", false);
        page.put("layout", false);
        page.put("name", "LaunchPage");
        page.put("title", "Launch Page");
        page.put("icon", "");
        page.put("mask", false);
        page.put("hidden", false);
        page.put("raw", false);
        page.put("pure", true);
        page.put("cache", false);
        page.put("needLogin", false);
        page.put("style", Map.of());
        page.put("type", "page");
        return page;
    }

    private Map<String, Object> launchPageDsl(String id) {
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("id", id);
        dsl.put("name", "LaunchPage");
        dsl.put("locked", false);
        dsl.put("inject", List.of());
        dsl.put("state", Map.of());
        dsl.put("lifeCycles", Map.of());
        dsl.put("methods", Map.of());
        dsl.put("computed", Map.of());
        dsl.put("watch", List.of());
        dsl.put("css", ".launch-page{height:100%;display:flex;align-items:center;justify-content:center;}");
        dsl.put("props", List.of());
        dsl.put("emits", List.of());
        dsl.put("slots", List.of());
        dsl.put("dataSources", Map.of());
        dsl.put("__VTJ_BLOCK__", true);
        dsl.put("__VERSION__", String.valueOf(System.currentTimeMillis()));
        dsl.put("nodes", List.of(Map.of(
                "id", shortId(),
                "name", "div",
                "from", "",
                "invisible", false,
                "locked", false,
                "children", "VTJ Pro",
                "props", Map.of("class", "launch-page"),
                "directives", List.of(),
                "events", Map.of()
        )));
        return dsl;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String stripJson(String value) {
        return value == null ? "" : value.replace(".json", "");
    }

    private String extractTemplate(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1).trim() : source.trim();
    }

    private String extractStyles(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        Matcher matcher = STYLE_PATTERN.matcher(source);
        StringBuilder styles = new StringBuilder();
        while (matcher.find()) {
            String style = matcher.group(1).trim();
            if (!style.isBlank()) {
                if (!styles.isEmpty()) {
                    styles.append("\n\n");
                }
                styles.append(style);
            }
        }
        return styles.toString();
    }

    private List<Map<String, Object>> htmlToNodes(String html) {
        String source = html == null ? "" : html;
        HtmlDraft root = new HtmlDraft("");
        Deque<HtmlDraft> stack = new ArrayDeque<>();
        stack.push(root);
        Matcher matcher = TAG_PATTERN.matcher(source);
        int pos = 0;
        while (matcher.find()) {
            addText(stack.peek(), source.substring(pos, matcher.start()));
            String tag = matcher.group(2);
            if (tag != null) {
                if (!matcher.group(1).isBlank()) {
                    popToTag(stack, tag);
                } else {
                    HtmlDraft node = new HtmlDraft(formatTagName(tag));
                    fillNodeAttributes(node, matcher.group(3));
                    stack.peek().children.add(node);
                    String raw = matcher.group();
                    boolean selfClosing = raw.endsWith("/>") || VOID_TAGS.contains(tag.toLowerCase());
                    if (!selfClosing) {
                        stack.push(node);
                    }
                }
            }
            pos = matcher.end();
        }
        addText(stack.peek(), source.substring(pos));
        return childrenToNodeList(root.children);
    }

    private void addText(HtmlDraft parent, String raw) {
        if (parent == null || raw == null) {
            return;
        }
        String text = decodeEntities(raw).replaceAll("\\s+", " ").trim();
        if (!text.isBlank() && !"\"".equals(text)) {
            parent.children.add(text);
        }
    }

    private void popToTag(Deque<HtmlDraft> stack, String tag) {
        while (stack.size() > 1) {
            HtmlDraft node = stack.pop();
            if (node.name.equalsIgnoreCase(tag)) {
                return;
            }
        }
    }

    private void fillNodeAttributes(HtmlDraft node, String rawAttributes) {
        Matcher matcher = ATTR_PATTERN.matcher(rawAttributes == null ? "" : rawAttributes);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = firstNonNull(matcher.group(3), matcher.group(4), matcher.group(5), "");
            if (key.startsWith("@")) {
                addEvent(node, key.substring(1), value);
            } else if (key.startsWith("v-on:")) {
                addEvent(node, key.substring("v-on:".length()), value);
            } else if (key.startsWith(":")) {
                node.props.put(key.substring(1), jsExpression(value));
            } else if (key.startsWith("v-bind:")) {
                node.props.put(key.substring("v-bind:".length()), jsExpression(value));
            } else if (key.startsWith("v-")) {
                addDirective(node, key, value);
            } else if ("style".equals(key)) {
                node.props.put(key, styleToMap(value));
            } else {
                node.props.put(key, value.isBlank() ? true : value);
            }
        }
    }

    private void addEvent(HtmlDraft node, String eventKey, String value) {
        String[] parts = eventKey.split("\\.");
        String eventName = parts.length == 0 ? eventKey : parts[0];
        Map<String, Object> modifiers = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            modifiers.put(parts[i], true);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", eventName);
        event.put("handler", jsFunction(value.isBlank() ? "() => {}" : value));
        event.put("modifiers", modifiers);
        node.events.put(eventName, event);
    }

    private void addDirective(HtmlDraft node, String key, String value) {
        Map<String, Object> directive = new LinkedHashMap<>();
        switch (key) {
            case "v-if" -> {
                directive.put("name", "vIf");
                directive.put("value", jsExpression(value));
            }
            case "v-else-if" -> {
                directive.put("name", "vElseIf");
                directive.put("value", jsExpression(value));
            }
            case "v-else" -> {
                directive.put("name", "vElse");
                directive.put("value", true);
            }
            case "v-show" -> {
                directive.put("name", "vShow");
                directive.put("value", jsExpression(value));
            }
            case "v-model" -> {
                directive.put("name", "vModel");
                directive.put("value", jsExpression(value));
            }
            case "v-for" -> {
                directive.put("name", "vFor");
                directive.put("value", jsExpression(parseForSource(value)));
                directive.put("iterator", parseForIterator(value));
            }
            default -> {
                directive.put("name", key.replaceFirst("^v-", "v"));
                directive.put("value", jsExpression(value));
            }
        }
        node.directives.add(directive);
    }

    private String parseForSource(String value) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(?:\\(([^)]*)\\)|([^\\s]+))\\s+(?:in|of)\\s+(.+)$").matcher(value);
        return matcher.find() ? matcher.group(3).trim() : value;
    }

    private Map<String, Object> parseForIterator(String value) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(?:\\(([^)]*)\\)|([^\\s]+))\\s+(?:in|of)\\s+(.+)$").matcher(value);
        String item = "item";
        String index = "index";
        if (matcher.find()) {
            String aliases = firstNonNull(matcher.group(1), matcher.group(2), "");
            String[] parts = aliases.split(",");
            if (parts.length > 0 && !parts[0].trim().isBlank()) {
                item = parts[0].trim();
            }
            if (parts.length > 1 && !parts[1].trim().isBlank()) {
                index = parts[1].trim();
            }
        }
        return Map.of("item", item, "index", index);
    }

    private List<Map<String, Object>> childrenToNodeList(List<Object> children) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object child : children) {
            if (child instanceof HtmlDraft draft) {
                nodes.add(toNode(draft));
            } else if (child instanceof String text && !text.isBlank()) {
                nodes.add(textNode("span", text, Map.of()));
            }
        }
        return nodes;
    }

    private Map<String, Object> toNode(HtmlDraft draft) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", shortId());
        node.put("name", draft.name);
        node.put("from", "");
        node.put("invisible", false);
        node.put("locked", false);
        node.put("props", draft.props);
        node.put("directives", draft.directives);
        node.put("events", draft.events);
        node.put("children", toChildrenValue(draft.children));
        return node;
    }

    private Object toChildrenValue(List<Object> children) {
        if (children.isEmpty()) {
            return "";
        }
        if (children.size() == 1 && children.get(0) instanceof String text) {
            return text;
        }
        return childrenToNodeList(children);
    }

    private Map<String, Object> textNode(String tag, String text, Map<String, Object> props) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", shortId());
        node.put("name", tag);
        node.put("from", "");
        node.put("invisible", false);
        node.put("locked", false);
        node.put("children", text);
        node.put("props", props);
        node.put("directives", List.of());
        node.put("events", Map.of());
        return node;
    }

    private Map<String, Object> jsExpression(String value) {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("type", "JSExpression");
        expression.put("value", value == null ? "" : value);
        return expression;
    }

    private Map<String, Object> jsFunction(String value) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("type", "JSFunction");
        function.put("value", value == null ? "" : value);
        return function;
    }

    private Map<String, Object> styleToMap(String style) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (style == null || style.isBlank()) {
            return result;
        }
        for (String item : style.split(";")) {
            String[] parts = item.split(":", 2);
            if (parts.length == 2 && !parts[0].trim().isBlank()) {
                result.put(parts[0].trim(), parts[1].trim());
            }
        }
        return result;
    }

    private String nodesToHtml(Object nodesValue, int depth) {
        if (!(nodesValue instanceof List<?> nodes)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Object nodeValue : nodes) {
            Map<String, Object> node = asMap(nodeValue);
            if (!node.isEmpty()) {
                lines.add(nodeToHtml(node, depth));
            }
        }
        return String.join("\n", lines);
    }

    private String nodeToHtml(Map<String, Object> node, int depth) {
        String tag = Jsons.text(node, "name", "div");
        String indent = " ".repeat(Math.max(0, depth));
        String attrs = attributesToHtml(asMap(node.get("props")), asMap(node.get("events")), asList(node.get("directives")));
        String open = indent + "<" + tag + attrs;
        Object children = node.get("children");
        if (isEmptyChildren(children) && VOID_TAGS.contains(tag.toLowerCase())) {
            return open + " />";
        }
        if (isEmptyChildren(children)) {
            return open + "></" + tag + ">";
        }
        if (children instanceof String text) {
            return open + ">" + escapeHtml(text) + "</" + tag + ">";
        }
        if (children instanceof Map<?, ?> map && "JSExpression".equals(String.valueOf(map.get("type")))) {
            Object value = map.get("value");
            return open + ">{{ " + (value == null ? "" : value) + " }}</" + tag + ">";
        }
        return open + ">\n" + nodesToHtml(children, depth + 2) + "\n" + indent + "</" + tag + ">";
    }

    private String attributesToHtml(Map<String, Object> props, Map<String, Object> events, List<Object> directives) {
        StringBuilder attrs = new StringBuilder();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            appendPropAttr(attrs, entry.getKey(), entry.getValue());
        }
        for (Object directiveValue : directives) {
            appendDirectiveAttr(attrs, asMap(directiveValue));
        }
        for (Map.Entry<String, Object> entry : events.entrySet()) {
            Map<String, Object> event = asMap(entry.getValue());
            Map<String, Object> handler = asMap(event.get("handler"));
            String value = Jsons.text(handler, "value", "");
            if (!value.isBlank()) {
                attrs.append(" @").append(entry.getKey()).append("=\"").append(escapeAttr(value)).append("\"");
            }
        }
        return attrs.toString();
    }

    private void appendPropAttr(StringBuilder attrs, String key, Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return;
        }
        if (Boolean.TRUE.equals(value)) {
            attrs.append(' ').append(key);
            return;
        }
        if (value instanceof Map<?, ?> map && "JSExpression".equals(String.valueOf(map.get("type")))) {
            Object expression = map.get("value");
            attrs.append(" :").append(key).append("=\"").append(escapeAttr(String.valueOf(expression == null ? "" : expression))).append("\"");
            return;
        }
        if ("style".equals(key) && value instanceof Map<?, ?> style) {
            attrs.append(" style=\"").append(escapeAttr(styleMapToString(style))).append("\"");
            return;
        }
        attrs.append(' ').append(key).append("=\"").append(escapeAttr(String.valueOf(value))).append("\"");
    }

    private void appendDirectiveAttr(StringBuilder attrs, Map<String, Object> directive) {
        String name = Jsons.text(directive, "name", "");
        Object value = directive.get("value");
        String expression = "";
        if (value instanceof Map<?, ?> map) {
            Object raw = map.get("value");
            expression = raw == null ? "" : String.valueOf(raw);
        } else if (value != null) {
            expression = String.valueOf(value);
        }
        switch (name) {
            case "vIf" -> attrs.append(" v-if=\"").append(escapeAttr(expression)).append("\"");
            case "vElseIf" -> attrs.append(" v-else-if=\"").append(escapeAttr(expression)).append("\"");
            case "vElse" -> attrs.append(" v-else");
            case "vShow" -> attrs.append(" v-show=\"").append(escapeAttr(expression)).append("\"");
            case "vModel" -> attrs.append(" v-model=\"").append(escapeAttr(expression)).append("\"");
            case "vFor" -> {
                Map<String, Object> iterator = asMap(directive.get("iterator"));
                String item = Jsons.text(iterator, "item", "item");
                String index = Jsons.text(iterator, "index", "index");
                attrs.append(" v-for=\"(").append(item).append(", ").append(index).append(") in ")
                        .append(escapeAttr(expression)).append("\"");
            }
            default -> {
                if (!name.isBlank() && !"null".equals(expression)) {
                    attrs.append(' ').append(name).append("=\"").append(escapeAttr(expression)).append("\"");
                }
            }
        }
    }

    private String styleMapToString(Map<?, ?> style) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : style.entrySet()) {
            entries.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join(";", entries);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Object> asList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private boolean isEmptyChildren(Object children) {
        if (children == null) {
            return true;
        }
        if (children instanceof String text) {
            return text.isBlank();
        }
        if (children instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private String visibleFallbackText(String template, String name) {
        String text = decodeEntities((template == null ? "" : template).replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .trim();
        if (!text.isBlank()) {
            return text.length() > 120 ? text.substring(0, 120) : text;
        }
        return name == null || name.isBlank() ? "AI generated page" : name;
    }

    private String formatTagName(String tag) {
        if (tag == null || tag.isBlank()) {
            return "div";
        }
        String trimmed = tag.trim();
        String lower = trimmed.toLowerCase();
        return HTML_TAGS.contains(lower) || lower.contains("-") ? lower : trimmed;
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private String decodeEntities(String value) {
        return value == null ? "" : value
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttr(String value) {
        return escapeHtml(value).replace("\"", "&quot;");
    }

    private static final class HtmlDraft {
        private final String name;
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final Map<String, Object> events = new LinkedHashMap<>();
        private final List<Map<String, Object>> directives = new ArrayList<>();
        private final List<Object> children = new ArrayList<>();

        private HtmlDraft(String name) {
            this.name = name;
        }
    }
}
