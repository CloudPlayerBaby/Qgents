package qg.qgent.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
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

        if (internalToken == null || internalToken.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Internal service token is not configured");
        }
        String token = authorizationHeader.substring(7);
        if (!MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
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
        String githubToken = credentialService.exchangeGrant(request.getCredentialGrantId(), request.getExpectedHeadCommit(),
                request.getRepositoryFullName(), request.getBranchName(), request.getPurpose());
        return Map.of("token", githubToken);
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
