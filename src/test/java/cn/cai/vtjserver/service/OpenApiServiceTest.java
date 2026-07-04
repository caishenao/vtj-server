package cn.cai.vtjserver.service;

import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.mapper.TemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OpenApiServiceTest {

    @Mock
    private TemplateMapper templateMapper;
    @Mock
    private RedisCacheService cacheService;

    private OpenApiService service;

    @BeforeEach
    void setUp() {
        service = new OpenApiService(templateMapper, cacheService);
    }

    @Test
    void skillsReturnsRequestedVtjAgentDocuments() {
        ApiResponse<?> response = service.skills("web", List.of("vtj-dsl", "apis", "events"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).asString()
                .contains("VTJ DSL Skill")
                .contains("VTJ API Skill")
                .contains("VTJ Events Skill");
    }

    @Test
    void skillsReturnsStagedAgentProtocol() {
        ApiResponse<?> response = service.skills("web", List.of("vtj-agent"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).asString()
                .contains("VTJ Agent Output Protocol")
                .contains("Staged page generation")
                .contains("one section/component per turn");
    }

    @Test
    void dictReturnsLlmOptionsForAiModelPicker() {
        ApiResponse<?> response = service.dict("LLM");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).asString().contains("VTJ Agent");
    }
}
