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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OpenApiService {
    private static final String TOKEN_PREFIX = "vtj:auth:";

    private final TemplateMapper templateMapper;
    private final RedisCacheService cacheService;

    public ApiResponse<?> login(String sign) {
        String token = sign == null || sign.isBlank() ? UUID.randomUUID().toString().replace("-", "") : sign;
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "local");
        user.put("name", "Local Developer");
        user.put("avatar", "");
        user.put("token", token);
        cacheService.put(TOKEN_PREFIX + token, Jsons.string(user), Duration.ofDays(7));
        return ApiResponse.ok(user);
    }

    public ApiResponse<?> user(String token) {
        return cacheService.get(TOKEN_PREFIX + token)
                .map(Jsons::parseMap)
                .<ApiResponse<?>>map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.ok(Map.of(
                        "id", "local",
                        "name", "Local Developer",
                        "avatar", "",
                        "token", token
                )));
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
        if ("TemplateCategory".equalsIgnoreCase(code)) {
            return ApiResponse.ok(List.of(
                    Map.of("label", "Page", "value", "page"),
                    Map.of("label", "Block", "value", "block"),
                    Map.of("label", "Form", "value", "form")
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

    public ApiResponse<?> successObject(Map<String, Object> data) {
        return ApiResponse.ok(data);
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
