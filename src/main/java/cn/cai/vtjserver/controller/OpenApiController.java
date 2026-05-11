package cn.cai.vtjserver.controller;

import cn.cai.vtjserver.dto.ApiResponse;
import cn.cai.vtjserver.service.OpenApiService;
import cn.cai.vtjserver.util.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OpenApiController {
    private final OpenApiService service;

    @GetMapping("/api/open/auth/{sign}")
    public ResponseEntity<String> auth(@PathVariable String sign, @RequestParam(required = false) String callback) {
        return jsonp(service.login(sign), callback);
    }

    @GetMapping("/api/open/user/{token}")
    public ResponseEntity<String> user(@PathVariable String token, @RequestParam(required = false) String callback) {
        return jsonp(service.user(token), callback);
    }

    @GetMapping("/api/open/templates")
    public ResponseEntity<String> templates(
            @RequestParam(required = false, defaultValue = "web") String platform,
            @RequestParam(required = false) String callback) {
        return jsonp(service.templates(platform), callback);
    }

    @GetMapping("/api/open/template/{token}")
    public ResponseEntity<String> template(
            @RequestParam String id,
            @RequestParam(required = false) String callback) {
        return jsonp(service.template(id), callback);
    }

    @GetMapping("/api/open/template/remove/{token}")
    public ResponseEntity<String> removeTemplate(
            @RequestParam String id,
            @RequestParam(required = false) String callback) {
        return jsonp(service.removeTemplate(id), callback);
    }

    @GetMapping("/api/open/dsl/{token}")
    public ResponseEntity<String> dsl(@RequestParam String id, @RequestParam(required = false) String callback) {
        return jsonp(service.templateDsl(id), callback);
    }

    @GetMapping("/api/open/dict/{code}")
    public ResponseEntity<String> dict(@PathVariable String code, @RequestParam(required = false) String callback) {
        return jsonp(service.dict(code), callback);
    }

    @PostMapping(value = "/api/open/template/publish/{token}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> publishTemplate(
            @RequestParam Map<String, String> form,
            @RequestParam(value = "cover", required = false) MultipartFile cover) {
        return service.publishTemplate(form, cover);
    }

    @PostMapping("/api/open/topic/post/{token}")
    public ApiResponse<?> postTopic(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> topic = new LinkedHashMap<>(body == null ? Map.of() : body);
        topic.putIfAbsent("id", java.util.UUID.randomUUID().toString().replace("-", ""));
        return service.successObject(topic);
    }

    @PostMapping({"/api/open/topic/image/{token}", "/api/open/topic/json/{token}"})
    public ApiResponse<?> postMultipartTopic(@RequestParam Map<String, Object> form) {
        Map<String, Object> topic = new LinkedHashMap<>(form);
        topic.putIfAbsent("id", java.util.UUID.randomUUID().toString().replace("-", ""));
        return service.successObject(topic);
    }

    @GetMapping({"/api/open/chat/list/{token}", "/api/open/topic/list/{token}", "/api/open/topic/hot"})
    public ApiResponse<?> lists() {
        return service.successList();
    }

    @PostMapping("/api/open/chat/post/{token}")
    public ApiResponse<?> postChat(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> chat = new LinkedHashMap<>(body == null ? Map.of() : body);
        chat.putIfAbsent("id", java.util.UUID.randomUUID().toString().replace("-", ""));
        return service.successObject(chat);
    }

    @PostMapping("/api/open/chat/save/{token}")
    public ApiResponse<?> saveChat(@RequestBody(required = false) Map<String, Object> body) {
        return service.successObject(body == null ? Map.of() : body);
    }

    @GetMapping({"/api/open/chat/cancel/{token}", "/api/open/topic/remove/{token}", "/api/open/order/cancel/{token}"})
    public ApiResponse<?> ok() {
        return ApiResponse.ok(true);
    }

    @GetMapping("/api/open/settings/{token}")
    public ApiResponse<?> settings() {
        return ApiResponse.ok(Map.of("enabled", false));
    }

    @PostMapping("/api/open/order/{token}")
    public ApiResponse<?> createOrder() {
        return ApiResponse.ok(Map.of("id", java.util.UUID.randomUUID().toString().replace("-", ""), "status", "created"));
    }

    @GetMapping("/api/open/order/{token}")
    public ApiResponse<?> getOrder(@RequestParam String id) {
        return ApiResponse.ok(Map.of("id", id, "status", "created"));
    }

    @PostMapping("/api/open/skills/{platform}")
    public ApiResponse<?> skills(@RequestBody(required = false) Object ids) {
        return ApiResponse.ok("");
    }

    @GetMapping(value = "/api/open/completions/{token}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@RequestParam String tid, @RequestParam String id) throws IOException {
        SseEmitter emitter = new SseEmitter(5000L);
        emitter.send(SseEmitter.event().data(Jsons.string(Map.of(
                "id", id,
                "topicId", tid,
                "content", "",
                "finish", true
        ))));
        emitter.complete();
        return emitter;
    }

    private ResponseEntity<String> jsonp(ApiResponse<?> response, String callback) {
        String json = Jsons.string(response);
        if (callback != null && !callback.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/javascript;charset=UTF-8"))
                    .body(callback + "(" + json + ");");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }
}
