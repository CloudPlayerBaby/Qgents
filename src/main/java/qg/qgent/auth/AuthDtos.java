package qg.qgent.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}
    public record Register(@NotBlank @Email @Size(max=320) String email,
                           @NotBlank @Size(max=128) String passwordKeyId,
                           @NotBlank @Size(max=4096) String password,
                           @NotBlank @Size(max=120) String displayName) {}
    public record Login(@NotBlank @Email @Size(max=320) String email,
                        @NotBlank @Size(max=128) String passwordKeyId,
                        @NotBlank @Size(max=4096) String password) {}
    public record Refresh(@NotBlank @Size(max=512) String refreshToken) {}
    public record ResetRequest(@NotBlank @Email @Size(max=320) String email) {}
    public record Reset(@NotBlank @Size(max=512) String token,
                        @NotBlank @Size(max=128) String passwordKeyId,
                        @NotBlank @Size(max=4096) String newPassword) {}
    public record UpdateMe(@Size(min=1,max=120) String displayName, @Size(max=2048) String avatarUrl) {}
    public record Tokens(String accessToken, long accessTokenExpiresIn, String refreshToken,
                         long refreshTokenExpiresIn, UserView user) {}
    public record UserView(String id, String email, String displayName, String avatarUrl) {}
    public record TeamView(String id, String name, String role) {}
    public record ProjectView(String id, String teamId, String name, String role, String status) {}
    public record MeView(UserView user, List<TeamView> teams, List<ProjectView> projects) {}
}
