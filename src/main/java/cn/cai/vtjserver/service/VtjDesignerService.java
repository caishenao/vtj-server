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
import cn.cai.vtjserver.mapper.FileMapper;
import cn.cai.vtjserver.mapper.HistoryItemMapper;
import cn.cai.vtjserver.mapper.HistoryMapper;
import cn.cai.vtjserver.mapper.MaterialMapper;
import cn.cai.vtjserver.mapper.ProjectMapper;
import cn.cai.vtjserver.mapper.StaticFileMapper;
import cn.cai.vtjserver.mapper.TemplateMapper;
import cn.cai.vtjserver.util.Jsons;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VtjDesignerService {
    private static final String PROJECT_CACHE_PREFIX = "vtj:project:";

    private final ProjectMapper projectMapper;
    private final FileMapper fileMapper;
    private final HistoryMapper historyMapper;
    private final HistoryItemMapper historyItemMapper;
    private final MaterialMapper materialMapper;
    private final StaticFileMapper staticFileMapper;
    private final TemplateMapper templateMapper;
    private final RedisCacheService cacheService;
    private final VtjProperties properties;

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
            case "getRoutes" -> ApiResponse.ok(getRoutes(String.valueOf(request.getData())));
            case "getTemplates" -> ApiResponse.ok(getTemplates());
            default -> ApiResponse.fail("No handler for type: " + type, null);
        };
    }

    public boolean publish(String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
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
            return false;
        }
    }

    public List<Map<String, Object>> getProjects() {
        return projectMapper.selectList(null).stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("description", p.getDescription());
            map.put("platform", p.getPlatform());
            map.put("createdAt", p.getCreatedAt());
            map.put("updatedAt", p.getUpdatedAt());
            return map;
        }).toList();
    }

    public Map<String, Object> searchProjects(String keyword, Integer page, Integer size) {
        LambdaQueryWrapper<ProjectEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(ProjectEntity::getName, keyword)
                    .or()
                    .like(ProjectEntity::getId, keyword)
                    .or()
                    .like(ProjectEntity::getDescription, keyword));
        }
        wrapper.orderByDesc(ProjectEntity::getUpdatedAt);

        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : size;

        long total = projectMapper.selectCount(wrapper);

        List<Map<String, Object>> items = projectMapper.selectList(wrapper.last("LIMIT " + s + " OFFSET " + (p - 1) * s))
                .stream().map(proj -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", proj.getId());
                    map.put("name", proj.getName());
                    map.put("description", proj.getDescription());
                    map.put("platform", proj.getPlatform());
                    map.put("createdAt", proj.getCreatedAt());
                    map.put("updatedAt", proj.getUpdatedAt());
                    return map;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        result.put("pages", (total + s - 1) / s);
        return result;
    }

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
        config.put("__BASE_PATH__", properties.getProject().getStaticBase());
        config.put("__ACCESS__", access);
        config.put("checkVersion", false);
        return config;
    }

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

    public boolean saveProject(Map<String, Object> dsl) {
        upsertProject(dsl);
        return true;
    }

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

    public Map<String, Object> getFile(String id) {
        FileEntity file = fileMapper.selectById(id);
        return file == null ? null : file.getDsl();
    }

    public boolean removeFile(String id) {
        return fileMapper.deleteById(id) >= 0;
    }

    public boolean saveHistory(Map<String, Object> history) {
        String id = Jsons.text(history, "id", "");
        if (id.isBlank()) {
            return false;
        }
        HistoryEntity entity = new HistoryEntity();
        entity.setId(id);
        entity.setProjectId(Jsons.text(history, "projectId", properties.getProject().getDefaultId()));
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
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dsl.getOrDefault("nodes", List.of());
        String templateContent = genNodesTemplate(nodes, 2);
        String scriptContent = genNodesScript(nodes, dsl);
        return """
                <template>
                  <div class="vtj-generated-page">
                %s
                  </div>
                </template>

                <script setup>
                %s
                </script>

                <style scoped>
                %s
                </style>
                """.formatted(templateContent, scriptContent, css);
    }

    private String genNodesTemplate(List<Map<String, Object>> nodes, int indent) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String pad = " ".repeat(indent * 2);
        for (Map<String, Object> node : nodes) {
            String name = Jsons.text(node, "name", "div");
            Map<String, Object> props = Jsons.map(node.get("props"));
            Map<String, Object> events = Jsons.map(node.get("events"));
            String directives = buildDirectives(node);
            String attrs = buildAttrs(props, events);
            sb.append(pad).append("<").append(name);
            if (attrs.length() > 0) sb.append(" ").append(attrs);
            if (directives.length() > 0) sb.append(" ").append(directives);
            String children = (String) node.get("children");
            List<Map<String, Object>> childNodes = (List<Map<String, Object>>) node.get("nodes");
            if (childNodes != null && !childNodes.isEmpty()) {
                sb.append(">\n");
                sb.append(genNodesTemplate(childNodes, indent + 1));
                sb.append("\n").append(pad).append("</").append(name).append(">");
            } else if (children != null && !children.isEmpty()) {
                sb.append(">").append(children).append("</").append(name).append(">");
            } else {
                sb.append(" />");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildAttrs(Map<String, Object> props, Map<String, Object> events) {
        StringBuilder sb = new StringBuilder();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                String key = e.getKey();
                Object val = e.getValue();
                if (val == null) continue;
                if (key.equals("class")) {
                    sb.append("class=\"").append(val).append("\" ");
                } else if (key.equals("style")) {
                    sb.append("style=\"").append(val).append("\" ");
                } else if (key.equals("vModel") || key.equals("modelValue")) {
                    sb.append("v-model=\"").append(val).append("\" ");
                } else if (key.equals("vBind") || key.equals("v-for")) {
                    // skip special directives
                } else {
                    sb.append(key).append("=\"").append(val).append("\" ");
                }
            }
        }
        if (events != null) {
            for (Map.Entry<String, Object> e : events.entrySet()) {
                String handler = (String) e.getValue();
                if (handler != null && !handler.isBlank()) {
                    sb.append("@").append(e.getKey()).append("=\"").append(handler).append("\" ");
                }
            }
        }
        return sb.toString().trim();
    }

    private String buildDirectives(Map<String, Object> node) {
        List<String> directives = new ArrayList<>();
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) node.get("directives");
        if (dirs != null) {
            for (Map<String, Object> d : dirs) {
                String name = Jsons.text(d, "name", "");
                String value = Jsons.text(d, "value", "");
                String arg = Jsons.text(d, "arg", "");
                if (!name.isBlank()) {
                    String dir = arg.isEmpty() ? name : name + ":" + arg;
                    directives.add("v-" + dir + "=\"" + value + "\"");
                }
            }
        }
        return String.join(" ", directives);
    }

    private String genNodesScript(List<Map<String, Object>> nodes, Map<String, Object> dsl) {
        StringBuilder sb = new StringBuilder();
        // Reactive state
        Map<String, Object> state = Jsons.map(dsl.get("state"));
        if (!state.isEmpty()) {
            sb.append("import { reactive } from 'vue'\n");
            sb.append("const state = reactive(").append(Jsons.stringify(state)).append(")\n\n");
        }
        // Methods
        Map<String, Object> methods = Jsons.map(dsl.get("methods"));
        if (!methods.isEmpty()) {
            for (Map.Entry<String, Object> e : methods.entrySet()) {
                sb.append("const ").append(e.getKey()).append(" = () => {\n");
                sb.append("  // ").append(e.getValue()).append("\n");
                sb.append("}\n\n");
            }
        }
        return sb.toString();
    }

    public Map<String, Object> parseVue(Map<String, Object> data) {
        return Map.of(
                "id", Jsons.text(data, "id", shortId()),
                "name", Jsons.text(data, "name", "ParsedPage"),
                "__VTJ_BLOCK__", true,
                "nodes", List.of()
        );
    }

    public List<Map<String, Object>> getRoutes(String projectId) {
        String pid = projectId == null || projectId.isBlank()
                ? properties.getProject().getDefaultId() : projectId;
        List<FileEntity> files = fileMapper.selectList(
                new LambdaQueryWrapper<FileEntity>().eq(FileEntity::getProjectId, pid));
        ProjectEntity project = projectMapper.selectById(pid);
        return files.stream().map(f -> {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("path", "/" + f.getName());
            route.put("name", f.getName());
            route.put("component", f.getId());
            route.put("meta", Map.of(
                    "title", f.getName(),
                    "projectId", pid,
                    "platform", project != null ? project.getPlatform() : "web"
            ));
            route.put("dsl", f.getDsl());
            return route;
        }).toList();
    }

    public List<Map<String, Object>> getTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<>())
                .stream().map(t -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", t.getId());
                    map.put("title", t.getTitle());
                    map.put("category", t.getCategory());
                    map.put("platform", t.getPlatform());
                    map.put("description", t.getDescription());
                    map.put("cover", t.getCover());
                    map.put("dsl", t.getDsl());
                    map.put("creator", t.getCreator());
                    map.put("createdAt", t.getCreatedAt());
                    return map;
                }).toList();
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
}
