package qg.qgent.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import qg.qgent.config.GitHubWebhookProperties;
import qg.qgent.controller.GitHubWebhookController;
import qg.qgent.service.GitHubWebhookService;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc 集成测试：验证 WebhookBodyLimitFilter 在真实 MVC 链中，
 * chunked（无 Content-Length）超限请求返回 413 而非被包装成 400。
 */
class WebhookBodyLimitFilterIntegrationTest {
    private final GitHubWebhookProperties properties = new GitHubWebhookProperties();
    private final GitHubWebhookService webhookService = mock(GitHubWebhookService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties.setSecret("secret");
        properties.setMaxBodyBytes(10);
        WebhookBodyLimitFilter filter = new WebhookBodyLimitFilter(properties);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GitHubWebhookController(webhookService))
                .addFilters(filter)
                .build();
    }

    /**
     * 无 Content-Length 的 chunked 请求：11 字节，filter 在进入 Controller 前拦截并返回 413。
     */
    @Test
    void chunkedBodyOverLimitReturns413BeforeController() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "d1")
                        .content("12345678901".getBytes(StandardCharsets.UTF_8))) // 11 > 10
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("WEBHOOK_BODY_TOO_LARGE"));
        // 超限请求不应进入 Service
        org.mockito.Mockito.verifyNoInteractions(webhookService);
    }

    /**
     * 恰好等于上限的 chunked 请求：10 字节，正常进入 Controller（Service 收到 body）。
     */
    @Test
    void chunkedBodyExactlyAtLimitReachesController() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "d1")
                        .content("1234567890".getBytes(StandardCharsets.UTF_8))) // 10 == limit
                .andExpect(status().isOk());
        // 恰好上限正常进入 Service
        org.mockito.Mockito.verify(webhookService).handle(any(byte[].class), anyString(), anyString(), anyString());
    }

    /**
     * Content-Length 声明超限：不读 body 直接 413。
     */
    @Test
    void declaredContentLengthOverLimitReturns413() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "d1")
                        .content("12345678901234567890".getBytes(StandardCharsets.UTF_8))) // 20 > 10
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("WEBHOOK_BODY_TOO_LARGE"));
        org.mockito.Mockito.verifyNoInteractions(webhookService);
    }

    /**
     * Service 正常返回时 filter 不干扰（非超限路径原样透传 200）。
     */
    @Test
    void serviceSuccessPassesThrough() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "d1")
                        .content("1234567890".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(webhookService).handle(any(byte[].class), anyString(), anyString(), anyString());
    }
}
