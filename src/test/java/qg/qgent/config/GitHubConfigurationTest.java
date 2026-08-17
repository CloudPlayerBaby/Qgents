package qg.qgent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;

class GitHubConfigurationTest {

    @Test
    void bindsExplicitProxyToGitHubHttpClient() {
        GitHubProxyProperties properties = new GitHubProxyProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(7890);

        HttpClient client = GitHubConfiguration.createHttpClient(properties);

        assertTrue(properties.configured());
        InetSocketAddress address = (InetSocketAddress) client.proxy().orElseThrow()
                .select(URI.create("https://api.github.com"))
                .getFirst()
                .address();
        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(7890, address.getPort());
    }

    @Test
    void leavesGitHubClientDirectWhenProxyIsNotConfigured() {
        HttpClient client = GitHubConfiguration.createHttpClient(new GitHubProxyProperties());

        assertFalse(client.proxy().isPresent());
    }
}
