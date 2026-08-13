package qg.qgent.controller.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.service.GitCredentialService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalGitCredentialControllerTest {

    @Mock
    private GitCredentialService credentialService;

    private InternalGitCredentialController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalGitCredentialController(credentialService, "secret123");
    }

    @Test
    void testExchangeSuccess() {
        InternalGitCredentialController.ExchangeRequest req = new InternalGitCredentialController.ExchangeRequest();
        req.setCredentialGrantId("grant123");
        req.setExpectedHeadCommit("head123");

        when(credentialService.exchangeGrant("grant123", "head123")).thenReturn("ghs_real_token");

        Map<String, String> result = controller.exchange("Bearer secret123", req);
        assertEquals("ghs_real_token", result.get("token"));
    }

    @Test
    void testExchangeFailAuth() {
        InternalGitCredentialController.ExchangeRequest req = new InternalGitCredentialController.ExchangeRequest();
        ApiException exception = assertThrows(ApiException.class, () -> controller.exchange("Bearer wrong", req));
        assertEquals(HttpStatus.FORBIDDEN, exception.status());
    }
    
    @Test
    void testExchangeMissingAuth() {
        InternalGitCredentialController.ExchangeRequest req = new InternalGitCredentialController.ExchangeRequest();
        ApiException exception = assertThrows(ApiException.class, () -> controller.exchange(null, req));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }
}
