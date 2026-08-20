package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.api.ApiException;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.GitHubOAuthStartResponse;
import qg.qgent.dto.GitHubOAuthStatusResponse;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.GitHubOAuthService;

/** GitHub OAuth 用户授权接口；OAuth callback 不使用 Qgents JWT，仅信任一次性 state。 */
@RestController
@RequestMapping("/api/v1")
public class GitHubOAuthController {
    private final GitHubOAuthService service;
    private final CurrentActorProvider actor;
    private final String frontendWeb;
    private final String frontendMobile;

    public GitHubOAuthController(GitHubOAuthService service, CurrentActorProvider actor,
                                 @org.springframework.beans.factory.annotation.Value("${app.frontend-url-web:${app.frontend-url}}") String frontendWeb,
                                 @org.springframework.beans.factory.annotation.Value("${app.frontend-url-mobile:${app.frontend-url}}") String frontendMobile) {
        this.service = service;
        this.actor = actor;
        this.frontendWeb = frontendWeb;
        this.frontendMobile = frontendMobile;
    }

    @PostMapping("/me/integrations/github/oauth/start")
    public ApiResponse<GitHubOAuthStartResponse> start(
            @RequestParam(name = "client", defaultValue = "WEB") String client,
            HttpServletRequest request) {
        return ApiResponse.ok(service.start(actor.currentUserId(), client),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    @GetMapping("/integrations/github/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        try {
            GitHubOAuthService.CallbackResult result = service.callback(code, state, error);
            return redirect(result.client(), "authorized", null);
        } catch (GitHubOAuthService.CallbackApiException exception) {
            return redirect(exception.client(), "failed", exception.code());
        } catch (ApiException exception) {
            return redirect(service.callbackClientHint(state), "failed", exception.code());
        }
    }

    @GetMapping("/me/integrations/github/oauth")
    public ApiResponse<GitHubOAuthStatusResponse> status(HttpServletRequest request) {
        return ApiResponse.ok(service.status(actor.currentUserId()),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    @DeleteMapping("/me/integrations/github/oauth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke() { service.revoke(actor.currentUserId()); }

    private ResponseEntity<Void> redirect(String client, String status, String code) {
        String frontend = "MOBILE".equals(client) ? frontendMobile : frontendWeb;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(frontend)
                .pathSegment("app", "settings", "integrations", "github")
                .queryParam("githubOAuth", status);
        if (code != null) builder.queryParam("code", code);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, builder.build().toUriString()).build();
    }
}
