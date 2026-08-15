package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.service.GitHubWebhookService;

/**
 * GitHub App Webhook 接收接口（契约 §6，公开无 JWT）。
 * 安全依据为 X-Hub-Signature-256、X-GitHub-Event、X-GitHub-Delivery 和 Webhook Secret；
 * Controller 只负责读取原始 body 与 Header，验签与幂等处理全部在 Service 内完成。
 */
@RestController
@RequestMapping("/api/v1/integrations/github")
@Tag(name = "GitHub Webhook", description = "GitHub App Webhook 接收（无 Qgents JWT，X-Hub-Signature-256 验签）")
public class GitHubWebhookController {
    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * 接收 GitHub Webhook 投递。以原始字节接收 body 以保证验签输入不被 JSON 反序列化破坏。
     * 验签或处理失败时由全局异常处理器转换为 400/401/413/500/503 状态。
     */
    @Operation(summary = "GitHub Webhook 接收", description = "接受 GitHub App 事件投递：ping/installation/installation_repositories/pull_request")
    @PostMapping(value = "/webhook", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                        @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery) {
        webhookService.handle(body == null ? new byte[0] : body, signature, event, delivery);
        return ResponseEntity.ok().build();
    }
}
