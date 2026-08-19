package qg.qgent.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.service.GitCredentialService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Hidden
@Slf4j
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
        long startedAt = System.nanoTime();
        String grantFingerprint = fingerprint(request == null ? null : request.getCredentialGrantId());
        log.info("git credential exchange request start grantFingerprint={} grantPresent={} headCommit={} repository={} branch={} purpose={}",
                grantFingerprint, request != null && request.getCredentialGrantId() != null
                        && !request.getCredentialGrantId().isBlank(),
                request == null ? null : request.getExpectedHeadCommit(),
                request == null ? null : request.getRepositoryFullName(),
                request == null ? null : request.getBranchName(), request == null ? null : request.getPurpose());

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.warn("git credential exchange rejected grantFingerprint={} reason=missing_authorization durationMs={}",
                    grantFingerprint, elapsedMillis(startedAt));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }

        if (internalToken == null || internalToken.isBlank()) {
            log.error("git credential exchange rejected grantFingerprint={} reason=backend_service_token_not_configured durationMs={}",
                    grantFingerprint, elapsedMillis(startedAt));
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Internal service token is not configured");
        }
        String token = authorizationHeader.substring(7);
        if (!MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("git credential exchange rejected grantFingerprint={} reason=invalid_backend_service_token durationMs={}",
                    grantFingerprint, elapsedMillis(startedAt));
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Invalid internal service token");
        }

        if (request.getCredentialGrantId() == null || request.getCredentialGrantId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing credentialGrantId");
        }
        if (request.getExpectedHeadCommit() == null || request.getExpectedHeadCommit().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing expectedHeadCommit");
        }

        if (request.getRepositoryFullName() == null || request.getRepositoryFullName().isBlank()
                || request.getBranchName() == null || request.getBranchName().isBlank()
                || request.getPurpose() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing credential scope");
        }
        try {
            String githubToken = credentialService.exchangeGrant(request.getCredentialGrantId(), request.getExpectedHeadCommit(),
                    request.getRepositoryFullName(), request.getBranchName(), request.getPurpose());
            log.info("git credential exchange success grantFingerprint={} repository={} branch={} purpose={} tokenPresent={} tokenLength={} durationMs={}",
                    grantFingerprint, request.getRepositoryFullName(), request.getBranchName(), request.getPurpose(),
                    githubToken != null && !githubToken.isBlank(), githubToken == null ? 0 : githubToken.length(),
                    elapsedMillis(startedAt));
            return Map.of("token", githubToken);
        } catch (ApiException failure) {
            log.warn("git credential exchange rejected grantFingerprint={} repository={} branch={} purpose={} code={} durationMs={}",
                    grantFingerprint, request.getRepositoryFullName(), request.getBranchName(), request.getPurpose(),
                    failure.code(), elapsedMillis(startedAt));
            throw failure;
        } catch (RuntimeException failure) {
            log.error("git credential exchange failed grantFingerprint={} repository={} branch={} purpose={} exceptionType={} durationMs={} message={}",
                    grantFingerprint, request.getRepositoryFullName(), request.getBranchName(), request.getPurpose(),
                    failure.getClass().getSimpleName(), elapsedMillis(startedAt), failure.getMessage(), failure);
            throw failure;
        }
    }

    private long elapsedMillis(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    /** 只记录一次性 Grant 的不可逆短指纹，不记录明文凭据。 */
    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 algorithm", exception);
        }
    }

    @Data
    @Schema(description = "Worker 兑换一次性 Git 凭据的内部请求")
    public static class ExchangeRequest {
        /**
         * 一次性凭据授权编号。
         */
        @NotBlank
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String credentialGrantId;
        /**
         * 与授权绑定的预期提交。
         */
        @NotBlank
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedHeadCommit;
        /**
         * 已绑定仓库的 owner/name。
         */
        @NotBlank
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String repositoryFullName;
        /**
         * 需要执行操作的受控分支。
         */
        @NotBlank
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String branchName;
        /**
         * 授权用途，必须与签发时一致。
         */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private GitCredentialPurpose purpose;
    }
}
