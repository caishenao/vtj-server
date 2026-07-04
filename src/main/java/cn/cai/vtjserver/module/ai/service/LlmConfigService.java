package cn.cai.vtjserver.module.ai.service;

import cn.cai.vtjserver.exception.BusinessException;
import cn.cai.vtjserver.module.ai.dto.AiScene;
import cn.cai.vtjserver.module.ai.dto.LlmConfigDto;
import cn.cai.vtjserver.module.ai.dto.LlmConfigRequest;
import cn.cai.vtjserver.module.ai.entity.LlmConfigEntity;
import cn.cai.vtjserver.module.ai.mapper.LlmConfigMapper;
import cn.cai.vtjserver.util.AesGcmCipher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages {@link LlmConfigEntity} records: CRUD for the {@code /api/llm} admin surface plus the
 * scenario routing used by the AI gateway. The {@code apiKey} is encrypted before persistence and
 * only ever decrypted internally (never exposed through a DTO).
 */
@Service
@RequiredArgsConstructor
public class LlmConfigService {
    private final LlmConfigMapper mapper;
    private final AesGcmCipher cipher;

    /** @return all configurations, secrets masked, newest first */
    public List<LlmConfigDto> list() {
        return mapper.selectList(new LambdaQueryWrapper<LlmConfigEntity>()
                        .orderByDesc(LlmConfigEntity::getUpdatedAt))
                .stream().map(LlmConfigDto::from).toList();
    }

    /** @return the masked configuration for {@code id}, or {@code null} when absent */
    public LlmConfigDto get(String id) {
        LlmConfigEntity entity = mapper.selectById(id);
        return entity == null ? null : LlmConfigDto.from(entity);
    }

    /**
     * Creates or updates a configuration. On update, a blank {@code apiKey} preserves the stored
     * (encrypted) key rather than clearing it.
     *
     * @return the persisted configuration, secret masked
     */
    @Transactional
    public LlmConfigDto save(LlmConfigRequest request) {
        boolean isNew = request.id() == null || request.id().isBlank();
        LlmConfigEntity entity = isNew ? null : mapper.selectById(request.id());
        boolean insert = entity == null;

        if (insert) {
            entity = new LlmConfigEntity();
            entity.setId(isNew ? UUID.randomUUID().toString().replace("-", "") : request.id());
            entity.setCreatedAt(OffsetDateTime.now());
        }
        entity.setName(request.name());
        entity.setType(AiScene.from(request.type()).name());
        entity.setProvider(request.provider());
        entity.setBaseUrl(request.baseUrl());
        entity.setModel(request.model());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
        entity.setUpdatedAt(OffsetDateTime.now());

        // Blank apiKey on an existing record keeps the previously stored secret untouched.
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKey(cipher.encrypt(request.apiKey()));
        }

        if (insert) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return LlmConfigDto.from(entity);
    }

    /** Deletes the configuration; returns {@code true} when a non-blank id was processed. */
    @Transactional
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        mapper.deleteById(id);
        return true;
    }

    /** Enables or disables a configuration. */
    @Transactional
    public boolean setEnabled(String id, boolean enabled) {
        LlmConfigEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("LLM config not found: " + id);
        }
        entity.setEnabled(enabled);
        entity.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(entity);
        return true;
    }

    /**
     * Resolves the enabled configuration for a routing scene, with its {@code apiKey} decrypted for
     * immediate use. The most recently updated enabled config of that type wins.
     *
     * @return the ready-to-use config, or empty when no enabled model matches the scene
     */
    public Optional<LlmConfigEntity> resolveForScene(AiScene scene) {
        LlmConfigEntity entity = mapper.selectOne(new LambdaQueryWrapper<LlmConfigEntity>()
                .eq(LlmConfigEntity::getType, scene.name())
                .eq(LlmConfigEntity::getEnabled, true)
                .orderByDesc(LlmConfigEntity::getUpdatedAt)
                .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        entity.setApiKey(cipher.decrypt(entity.getApiKey()));
        return Optional.of(entity);
    }
}
