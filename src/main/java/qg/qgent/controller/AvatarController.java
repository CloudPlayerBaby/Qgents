package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AvatarConfirmRequest;
import qg.qgent.dto.AvatarConfirmResponse;
import qg.qgent.dto.AvatarCredentialRequest;
import qg.qgent.dto.AvatarCredentialResponse;
import qg.qgent.service.AvatarService;

import java.util.UUID;

/**
 * 用户头像上传接口。
 * <p>
 * 流程：签发直传凭证（客户端直传 OSS）→ 确认头像并把对象键/公共读 URL 写入当前用户。
 * OSS 未启用（本地/CI）时，服务端抛 501 AVATAR_STORAGE_NOT_CONFIGURED，由全局异常处理器映射为
 * 「头像上传暂不可用」，不影响其它接口；前端可据此隐藏入口或提示。
 */
@RestController
@RequestMapping("/api/v1")
public class AvatarController {

    private final AvatarService avatarService;

    public AvatarController(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    /**
     * 签发头像直传凭证：客户端凭 uploadUrl 直传 OSS，再携带返回的 objectKey 调确认接口。
     */
    @Operation(summary = "签发头像直传凭证", description = "返回头像对象键与预签名上传地址；OSS 未启用时返回 501。")
    @PostMapping("/me/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId,
                                           @Valid @RequestBody AvatarCredentialRequest body, HttpServletRequest request) {
        AvatarCredentialResponse data = avatarService.credential(userId, body.getMediaType(), body.getSizeBytes());
        return ok(data, request);
    }

    /**
     * 确认头像上传：校验对象属于当前用户且已真实上传，写入 users.avatar_url，返回公共读长期 URL。
     */
    @Operation(summary = "确认头像上传完成", description = "校验对象存在后写库并返回头像公共读 URL；OSS 未启用时返回 501。")
    @PostMapping("/me/avatar/confirm")
    public ApiResponse<?> confirm(@AuthenticationPrincipal UUID userId,
                                  @Valid @RequestBody AvatarConfirmRequest body, HttpServletRequest request) {
        AvatarConfirmResponse data = avatarService.confirm(userId, body);
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
