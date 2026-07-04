package cn.cai.vtjserver.controller;

import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.service.VtjDesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/low-code")
@RequiredArgsConstructor
public class LowCodeController {

    private final VtjDesignerService service;

    @GetMapping("/routes")
    public ApiResponse<?> routes(@RequestParam(required = false) String projectId) {
        return ApiResponse.ok(service.getRoutes(projectId));
    }

    @GetMapping("/projects")
    public ApiResponse<?> projects() {
        return ApiResponse.ok(service.getProjects());
    }

    @GetMapping("/projects/search")
    public ApiResponse<?> searchProjects(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        return ApiResponse.ok(service.searchProjects(keyword, page, size));
    }
}
