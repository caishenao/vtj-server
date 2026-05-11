package cn.cai.vtjserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vtj")
public class VtjProperties {
    private Project project = new Project();
    private Storage storage = new Storage();

    @Data
    public static class Project {
        private String defaultId = "vtj-pro";
        private String defaultName = "VTJ Pro";
        private String defaultPlatform = "web";
        private String staticBase = "/";
        private String remote = "http://localhost:9527";
        private String authUrl = "/login";
    }

    @Data
    public static class Storage {
        private String root = "./data";
        private String staticDir = "./data/static";
    }
}
