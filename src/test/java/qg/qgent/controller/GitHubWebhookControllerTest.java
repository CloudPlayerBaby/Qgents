package qg.qgent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import qg.qgent.api.ApiException;
import qg.qgent.service.GitHubWebhookService;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitHubWebhookControllerTest {
    private final GitHubWebhookService webhookService = mock(GitHubWebhookService.class);
    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new GitHubWebhookController(webhookService);
    }

    @Test
    void validDeliveryReturns200() {
        byte[] body = "{\"zen\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Void> response = controller.receive(body, "sha256=abc", "ping", "d1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handle(eq(body), eq("sha256=abc"), eq("ping"), eq("d1"));
    }

    @Test
    void missingBodyPassesEmptyArrayToService() {
        controller.receive(null, "sha256=abc", "ping", "d1");
        verify(webhookService).handle(any(byte[].class), eq("sha256=abc"), eq("ping"), eq("d1"));
        verify(webhookService).handle(argThat(bytes -> bytes.length == 0), eq("sha256=abc"), eq("ping"), eq("d1"));
    }

    @Test
    void serviceExceptionPropagatesWithStatus() {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_MISMATCH", "bad signature"))
                .when(webhookService).handle(any(), any(), any(), any());
        try {
            controller.receive("{}".getBytes(StandardCharsets.UTF_8), "sha256=bad", "ping", "d1");
            fail("expected ApiException");
        } catch (ApiException failure) {
            assertEquals(HttpStatus.UNAUTHORIZED, failure.status());
            assertTrue(failure.getMessage().contains("bad signature"));
        }
    }
}
