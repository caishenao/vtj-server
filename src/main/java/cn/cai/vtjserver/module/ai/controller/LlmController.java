package cn.cai.vtjserver.module.ai.controller;

import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.exception.BusinessException;
import cn.cai.vtjserver.module.ai.dto.LlmConfigDto;
import cn.cai.vtjserver.module.ai.dto.LlmConfigRequest;
import cn.cai.vtjserver.module.ai.service.LlmConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin surface for LLM configurations (AGENTS.md §5.1 — models are managed dynamically, never
 * hardcoded). Secrets are write-only: {@code apiKey} is accepted on save but never returned.
 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {
    private final LlmConfigService service;

    @GetMapping
    public ApiResponse<List<LlmConfigDto>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<LlmConfigDto> get(@PathVariable String id) {
        LlmConfigDto dto = service.get(id);
        if (dto == null) {
            throw new BusinessException("LLM config not found: " + id);
        }
        return ApiResponse.ok(dto);
    }

    @PostMapping
    public ApiResponse<LlmConfigDto> create(@Valid @RequestBody LlmConfigRequest request) {
        return ApiResponse.ok(service.save(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LlmConfigDto> update(@PathVariable String id, @Valid @RequestBody LlmConfigRequest request) {
        // Path id is authoritative; ignore any divergent id in the body.
        LlmConfigRequest merged = new LlmConfigRequest(id, request.name(), request.type(), request.provider(),
                request.apiKey(), request.baseUrl(), request.model(), request.enabled());
        return ApiResponse.ok(service.save(merged));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable String id) {
        return ApiResponse.ok(service.delete(id));
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Boolean> setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        return ApiResponse.ok(service.setEnabled(id, enabled));
    }
}
