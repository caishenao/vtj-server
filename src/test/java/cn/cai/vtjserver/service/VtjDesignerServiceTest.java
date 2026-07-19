package cn.cai.vtjserver.service;

import cn.cai.vtjserver.config.VtjProperties;
import cn.cai.vtjserver.dto.ApiRequest;
import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.entity.FileEntity;
import cn.cai.vtjserver.entity.ProjectEntity;
import cn.cai.vtjserver.exception.BusinessException;
import cn.cai.vtjserver.mapper.FileMapper;
import cn.cai.vtjserver.mapper.HistoryItemMapper;
import cn.cai.vtjserver.mapper.HistoryMapper;
import cn.cai.vtjserver.mapper.MaterialMapper;
import cn.cai.vtjserver.mapper.ProjectMapper;
import cn.cai.vtjserver.mapper.StaticFileMapper;
import cn.cai.vtjserver.mapper.TemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DSL/project persistence loop of {@link VtjDesignerService}. Mappers and Redis
 * are mocked so the tests assert the service's routing and CRUD decisions without a live database.
 */
@ExtendWith(MockitoExtension.class)
class VtjDesignerServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private HistoryMapper historyMapper;
    @Mock
    private HistoryItemMapper historyItemMapper;
    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private StaticFileMapper staticFileMapper;
    @Mock
    private TemplateMapper templateMapper;
    @Mock
    private RedisCacheService cacheService;

    private VtjProperties properties;
    private VtjDesignerService service;

    @BeforeEach
    void setUp() {
        properties = new VtjProperties();
        service = new VtjDesignerService(projectMapper, fileMapper, historyMapper, historyItemMapper,
                materialMapper, staticFileMapper, templateMapper, cacheService, properties);
    }

    @Test
    void saveFileInsertsWhenAbsentAndBackfillsId() {
        when(fileMapper.selectById(anyString())).thenReturn(null);
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("name", "HomePage");

        boolean result = service.saveFile(dsl);

        assertThat(result).isTrue();
        assertThat(dsl.get("id")).asString().isNotBlank();
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        verify(fileMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("HomePage");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        verify(fileMapper, never()).updateById(any(FileEntity.class));
    }

    @Test
    void saveFileUpdatesWhenPresent() {
        FileEntity existing = new FileEntity();
        existing.setId("f1");
        when(fileMapper.selectById("f1")).thenReturn(existing);
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("id", "f1");
        dsl.put("name", "HomePage");

        service.saveFile(dsl);

        verify(fileMapper).updateById(any(FileEntity.class));
        verify(fileMapper, never()).insert(any(FileEntity.class));
    }

    @Test
    void getFileReturnsDslOrNull() {
        FileEntity entity = new FileEntity();
        entity.setDsl(Map.of("id", "f1", "name", "HomePage"));
        when(fileMapper.selectById("f1")).thenReturn(entity);
        when(fileMapper.selectById("missing")).thenReturn(null);

        assertThat(service.getFile("f1")).containsEntry("name", "HomePage");
        assertThat(service.getFile("missing")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchProjectsFiltersAndPaginatesExistingCompatibilityEndpoint() {
        ProjectEntity dashboard = new ProjectEntity();
        dashboard.setId("p1");
        dashboard.setName("Operations Dashboard");
        dashboard.setDescription("Realtime metrics");
        ProjectEntity website = new ProjectEntity();
        website.setId("p2");
        website.setName("Company Website");
        website.setDescription("Public pages");
        when(projectMapper.selectList(null)).thenReturn(List.of(dashboard, website));

        Map<String, Object> result = service.searchProjects("dashboard", 1, 10);

        assertThat(result).containsEntry("total", 1).containsEntry("page", 1).containsEntry("size", 10);
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsEntry("id", "p1");
    }

    @Test
    void removeFileRejectsBlankIdAndNeverTouchesMapper() {
        assertThat(service.removeFile("  ")).isFalse();
        assertThat(service.removeFile(null)).isFalse();
        verify(fileMapper, never()).deleteById(anyString());
    }

    @Test
    void removeFileDeletesForValidId() {
        assertThat(service.removeFile("f1")).isTrue();
        verify(fileMapper).deleteById("f1");
    }

    @Test
    void saveProjectRejectsEmptyPayload() {
        assertThatThrownBy(() -> service.saveProject(Map.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveProject(null))
                .isInstanceOf(BusinessException.class);
        verify(projectMapper, never()).insert(any(ProjectEntity.class));
        verify(projectMapper, never()).updateById(any(ProjectEntity.class));
    }

    @Test
    void saveProjectUpsertsAndCachesDsl() {
        when(projectMapper.selectById("p1")).thenReturn(null);
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("id", "p1");
        dsl.put("name", "Demo");

        assertThat(service.saveProject(dsl)).isTrue();

        verify(projectMapper).insert(any(ProjectEntity.class));
        verify(cacheService).put(anyString(), anyString(), any());
    }

    @Test
    void removeProjectCascadesAndClearsCache() {
        assertThat(service.removeProject("p1")).isTrue();

        verify(projectMapper).deleteById("p1");
        verify(fileMapper).delete(any());
        verify(historyMapper).delete(any());
        verify(materialMapper).deleteById("p1");
        verify(staticFileMapper).delete(any());
        verify(cacheService).delete(anyString());
    }

    @Test
    void removeProjectRejectsBlankId() {
        assertThat(service.removeProject(" ")).isFalse();
        verify(projectMapper, never()).deleteById(anyString());
    }

    @Test
    void initCreatesProjectWithLaunchPageWhenAbsent() {
        when(projectMapper.selectById(anyString())).thenReturn(null);
        when(fileMapper.selectById(anyString())).thenReturn(null);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "brand-new");
        Map<String, Object> project = service.init(input);

        assertThat(project.get("id")).isEqualTo("brand-new");
        assertThat(project.get("homepage")).asString().isNotBlank();
        assertThat(project).containsKey("pages");
        // A launch page file must be persisted alongside the project.
        verify(projectMapper).insert(any(ProjectEntity.class));
        verify(fileMapper).insert(any(FileEntity.class));
    }

    @Test
    void initReloadsExistingProjectAndEnrichesDsl() {
        ProjectEntity existing = new ProjectEntity();
        existing.setId("vtj-pro");
        existing.setName("VTJ Pro");
        existing.setPlatform("web");
        existing.setDsl(new LinkedHashMap<>(Map.of("pages", List.of())));
        when(projectMapper.selectById("vtj-pro")).thenReturn(existing);

        Map<String, Object> dsl = service.init(Map.of("id", "vtj-pro"));

        assertThat(dsl).containsEntry("id", "vtj-pro").containsEntry("name", "VTJ Pro");
        assertThat(dsl.get("__UID__")).asString().isNotBlank();
        verify(projectMapper, never()).insert(any(ProjectEntity.class));
        verify(cacheService).put(anyString(), anyString(), any());
    }

    @Test
    void getExtensionSuppliesLocalSignAuthForDesignerAutoLogin() {
        Map<String, Object> config = service.getExtension();

        assertThat(config).containsEntry("auth", "local-dev");
        assertThat(config.get("__ACCESS__")).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseVueCreatesVisibleNodesFromVueSfc() {
        String source = """
                <template>
                  <main class="site-home">
                    <section class="hero">
                      <h1>Acme Cloud</h1>
                      <p>Ship products faster</p>
                    </section>
                  </main>
                </template>
                <script setup>
                </script>
                <style scoped>
                .site-home { min-height: 100vh; }
                .hero { padding: 64px; }
                </style>
                """;

        Map<String, Object> dsl = service.parseVue(Map.of(
                "id", "home",
                "name", "HomePage",
                "source", source));

        assertThat(dsl)
                .containsEntry("id", "home")
                .containsEntry("name", "HomePage")
                .containsEntry("__VTJ_BLOCK__", true);
        assertThat(dsl.get("css")).asString().contains(".site-home").contains(".hero");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dsl.get("nodes");
        assertThat(nodes).isNotEmpty();
        assertThat(nodes.get(0)).containsEntry("name", "main");
        assertThat((Map<String, Object>) nodes.get(0).get("props")).containsEntry("class", "site-home");
        assertThat(nodeContainsText(nodes, "Acme Cloud")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseVuePreservesReactiveStateAndMethodsForInteractivePages() {
        String source = """
                <template>
                  <button @click="increment">{{ state.count }}</button>
                </template>
                <script>
                import { defineComponent, reactive } from 'vue';
                export default defineComponent({
                  name: 'Counter',
                  setup() {
                    const state = reactive({ count: 0, label: 'Clicks' });
                    return { state };
                  },
                  methods: {
                    increment() { this.state.count++; }
                  },
                  computed: {
                    summary() { return this.state.label + this.state.count; }
                  }
                });
                </script>
                <style scoped>.counter { color: red; }</style>
                """;

        Map<String, Object> dsl = service.parseVue(Map.of("id", "counter", "source", source));

        Map<String, Object> state = (Map<String, Object>) dsl.get("state");
        Map<String, Object> methods = (Map<String, Object>) dsl.get("methods");
        Map<String, Object> computed = (Map<String, Object>) dsl.get("computed");
        assertThat(state).containsKeys("count", "label");
        assertThat(methods).containsKey("increment");
        assertThat(methods.get("increment")).asString().contains("this.state.count++");
        assertThat(computed).containsKey("summary");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dsl.get("nodes");
        assertThat(nodes.get(0).get("children")).asString().contains("JSExpression").contains("state.count");

        String regenerated = service.genVueContent(Map.of("dsl", dsl));
        assertThat(regenerated)
                .contains("const state = reactive")
                .contains("increment()")
                .contains("summary()")
                .contains("{{ state.count }}")
                .contains("this.state.count++");
    }

    @Test
    void genVueContentSerializesDslNodesForStagedDiffs() {
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("name", "HomePage");
        dsl.put("css", ".site-home{color:#111;}");
        dsl.put("nodes", List.of(Map.of(
                "name", "main",
                "props", Map.of("class", "site-home"),
                "children", List.of(Map.of(
                        "name", "h1",
                        "props", Map.of(),
                        "children", "Acme Cloud",
                        "events", Map.of(),
                        "directives", List.of())),
                "events", Map.of(),
                "directives", List.of()
        )));

        String vue = service.genVueContent(Map.of("dsl", dsl));

        assertThat(vue)
                .contains("<main class=\"site-home\">")
                .contains("<h1>Acme Cloud</h1>")
                .contains(".site-home{color:#111;}");
    }

    @Test
    void dispatchRoutesUnknownTypeToFailureEnvelope() {
        ApiResponse<?> response = service.dispatch("noSuchType.json", new ApiRequest(), Map.of());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isNotEqualTo(0);
    }

    @Test
    void dispatchResolvesTypeFromPathWhenBodyTypeAbsent() {
        when(projectMapper.selectList(any())).thenReturn(List.of());
        ApiRequest request = new ApiRequest();
        // no explicit type -> resolved from the path segment
        ApiResponse<?> response = service.dispatch("getProjects.json", request, Map.of());

        assertThat(response.isSuccess()).isTrue();
        verify(projectMapper).selectList(any());
    }

    @SuppressWarnings("unchecked")
    private boolean nodeContainsText(Object value, String expected) {
        if (value instanceof String text) {
            return text.contains(expected);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> nodeContainsText(item, expected));
        }
        if (value instanceof Map<?, ?> map) {
            return nodeContainsText(((Map<String, Object>) map).get("children"), expected);
        }
        return false;
    }
}
