package qg.qgent.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiException;
import qg.qgent.service.GitCredentialService;

import java.util.Map;

@Hidden
@RestController
@RequestMapping("/internal/v1/git-credentials")
public class InternalGitCredentialController {

    private final GitCredentialService credentialService;
    private final String internalToken;

    public InternalGitCredentialController(
            GitCredentialService credentialService,
            @Value("${sandbox.backend-service-token}") String internalToken) {
        this.credentialService = credentialService;
        this.internalToken = internalToken;
    }

    @PostMapping("/exchange")
    public Map<String, String> exchange(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExchangeRequest request) {
        
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }
        
        String token = authorizationHeader.substring(7);
        if (!internalToken.equals(token)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Invalid internal service token");
        }

        if (request.getCredentialGrantId() == null || request.getCredentialGrantId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing credentialGrantId");
        }
        if (request.getExpectedHeadCommit() == null || request.getExpectedHeadCommit().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing expectedHeadCommit");
        }

        String githubToken = credentialService.exchangeGrant(request.getCredentialGrantId(), request.getExpectedHeadCommit());
        return Map.of("token", githubToken);
    }

    @Data
    public static class ExchangeRequest {
        private String credentialGrantId;
        private String expectedHeadCommit;
    }
}
