package qg.qgent.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.RestGitHubAppClient;

@Configuration
@EnableConfigurationProperties(GitHubAppProperties.class)
public class GitHubConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GitHubAppClient gitHubAppClient(GitHubAppProperties properties, Clock clock) {
        return new RestGitHubAppClient(RestClient.builder().baseUrl("https://api.github.com").build(), properties, clock);
    }
}
