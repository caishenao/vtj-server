package cn.cai.vtjserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "vtj")
public class VtjProperties {
    private Project project = new Project();
    private Storage storage = new Storage();
    private Ai ai = new Ai();

    @Data
    public static class Project {
        private String defaultId = "vtj-pro";
        private String defaultName = "VTJ Pro";
        private String defaultPlatform = "web";
        private String staticBase = "/";
        private String remote = "http://localhost:9527";
        private String authUrl = "/login";
        private String authSign = "local-dev";
    }

    @Data
    public static class Storage {
        private String root = "./data";
        private String staticDir = "./data/static";
    }

    /** AI 代理网关运行参数。密钥经 {@code secret} 注入（见 AesGcmCipher），此处仅放超时等非敏感项。 */
    @Data
    public static class Ai {
        /** 多模态（设计稿）请求超时；官方建议 ≥ 30s（AGENTS.md §5.5）。 */
        private Duration timeoutMultimodal = Duration.ofSeconds(60);
        /** 编程/代码请求超时。 */
        private Duration timeoutCoding = Duration.ofSeconds(45);
        /** Maximum characters of DSL/source/tool context included in one model prompt. */
        private int maxContextChars = 24000;
    }
}
