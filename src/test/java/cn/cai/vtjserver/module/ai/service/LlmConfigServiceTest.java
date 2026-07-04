package cn.cai.vtjserver.module.ai.service;

import cn.cai.vtjserver.module.ai.dto.AiScene;
import cn.cai.vtjserver.module.ai.dto.LlmConfigDto;
import cn.cai.vtjserver.module.ai.dto.LlmConfigRequest;
import cn.cai.vtjserver.module.ai.entity.LlmConfigEntity;
import cn.cai.vtjserver.module.ai.mapper.LlmConfigMapper;
import cn.cai.vtjserver.util.AesGcmCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmConfigServiceTest {

    @Mock
    private LlmConfigMapper mapper;

    private LlmConfigService service;

    @BeforeEach
    void setUp() {
        // A real cipher (not a mock) keeps the encrypt->store->decrypt round-trip honest.
        service = new LlmConfigService(mapper, new AesGcmCipher("test-secret"));
    }

    @Test
    void saveEncryptsApiKeyAndMasksInDto() {
        LlmConfigRequest request = new LlmConfigRequest(
                null, "GPT", "CODING", "openai", "sk-plaintext",
                "https://api.openai.com/v1", "gpt-4o", true);

        LlmConfigDto dto = service.save(request);

        assertThat(dto.hasApiKey()).isTrue();
        // DTO must never carry the raw key; the persisted entity must not store plaintext.
        ArgumentCaptor<LlmConfigEntity> captor = ArgumentCaptor.forClass(LlmConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getApiKey()).isNotBlank().isNotEqualTo("sk-plaintext");
    }

    @Test
    void saveWithBlankApiKeyKeepsExistingSecret() {
        LlmConfigEntity existing = new LlmConfigEntity();
        existing.setId("c1");
        existing.setApiKey("previously-encrypted");
        when(mapper.selectById("c1")).thenReturn(existing);

        LlmConfigRequest request = new LlmConfigRequest(
                "c1", "GPT", "CODING", "openai", "  ",
                "https://api.openai.com/v1", "gpt-4o", true);
        service.save(request);

        ArgumentCaptor<LlmConfigEntity> captor = ArgumentCaptor.forClass(LlmConfigEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("previously-encrypted");
        verify(mapper, never()).insert(any(LlmConfigEntity.class));
    }

    @Test
    void resolveForSceneReturnsDecryptedKey() {
        AesGcmCipher cipher = new AesGcmCipher("test-secret");
        LlmConfigEntity stored = new LlmConfigEntity();
        stored.setType("MULTIMODAL");
        stored.setEnabled(true);
        stored.setApiKey(cipher.encrypt("sk-secret"));
        when(mapper.selectOne(any())).thenReturn(stored);

        Optional<LlmConfigEntity> resolved = service.resolveForScene(AiScene.MULTIMODAL);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getApiKey()).isEqualTo("sk-secret");
    }

    @Test
    void resolveForSceneEmptyWhenNoEnabledConfig() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolveForScene(AiScene.CODING)).isEmpty();
    }

    @Test
    void deleteRejectsBlankId() {
        assertThat(service.delete(" ")).isFalse();
        verify(mapper, never()).deleteById(anyString());
    }
}
