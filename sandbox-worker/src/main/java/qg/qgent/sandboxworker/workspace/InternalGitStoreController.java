package qg.qgent.sandboxworker.workspace;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

/**
 * 仅供主后端调用的 Git Store 初始化和同步接口。
 */
@RestController
@RequestMapping("/internal/v1/git-stores")
@RequiredArgsConstructor
public class InternalGitStoreController {
    private final GitStoreManager gitStoreManager;
    private final SandboxWorkerProperties properties;

    /**
     * 初始化或同步一个已绑定仓库对应的 bare Git Store。
     */
    @Operation(summary = "初始化或同步受控 Git Store")
    @PostMapping("/{repositoryId}/sync")
    public GitStoreSyncResponse sync(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable UUID repositoryId,
                                     @Valid @RequestBody GitStoreSyncRequest request) {
        requireInternalAuthorization(authorization);
        return gitStoreManager.sync(repositoryId, request);
    }

    private void requireInternalAuthorization(String authorization) {
        String configured = properties.getBackendServiceToken();
        if (configured == null || configured.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new WorkerException(HttpStatus.UNAUTHORIZED, "INTERNAL_AUTH_REQUIRED", "Internal authorization required");
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new WorkerException(HttpStatus.FORBIDDEN, "INTERNAL_AUTH_INVALID", "Internal authorization invalid");
        }
    }
}
