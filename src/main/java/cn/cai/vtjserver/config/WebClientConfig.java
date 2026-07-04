package cn.cai.vtjserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Provides a shared {@link WebClient.Builder} for the AI provider client. A builder (rather than a
 * fully built client) is exposed so each request can target a different provider {@code baseUrl}.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
