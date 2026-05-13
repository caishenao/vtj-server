package cn.cai.vtjserver.controller;

import cn.cai.vtjserver.dto.ApiRequest;
import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.service.VtjDesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DesignerController {
    private final VtjDesignerService service;

    @PostMapping("/__vtj__/api/{type}.json")
    public ApiResponse<?> dispatch(
            @PathVariable String type,
            @RequestBody(required = false) ApiRequest request,
            @RequestParam Map<String, Object> query) {
        ApiRequest body = request == null ? new ApiRequest() : request;
        return service.dispatch(type, body, query == null ? new LinkedHashMap<>() : query);
    }

    @PostMapping(value = "/__vtj__/api/uploader.json", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "projectId", required = false) String projectId) throws IOException {
        return ApiResponse.ok(service.saveUploadedFiles(files, projectId));
    }

    @GetMapping("/api/oss/file/{filename:.+}")
    public ResponseEntity<Resource> ossFile(@PathVariable String filename) throws IOException {
        Path path = service.staticFilePath(filename);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType)
                .body(new FileSystemResource(path));
    }

    @GetMapping("/api/health")
    public ApiResponse<?> health() {
        return ApiResponse.ok(Map.of("status", "UP", "timestamp", System.currentTimeMillis()));
    }
}
