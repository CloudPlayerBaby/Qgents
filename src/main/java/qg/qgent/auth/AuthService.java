package qg.qgent.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;

import java.time.Duration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users; private final RsaPasswordDecryptor rsa; private final PasswordEncoder passwords;
    private final TokenService tokens; private final PasswordResetMailer mailer; private final RateLimiter limiter;
    private final String dummyPasswordHash;
    public AuthService(UserRepository users, RsaPasswordDecryptor rsa, PasswordEncoder passwords, TokenService tokens,
                       PasswordResetMailer mailer, RateLimiter limiter) {
        this.users=users; this.rsa=rsa; this.passwords=passwords; this.tokens=tokens; this.mailer=mailer; this.limiter=limiter;
        this.dummyPasswordHash=passwords.encode("qgents-dummy-password-not-used");
    }

    @Transactional
    public AuthDtos.Tokens register(AuthDtos.Register input) {
        String email=normalize(input.email()); String password=validated(rsa.decrypt(input.passwordKeyId(), input.password()));
        var user=new UserRepository.User(UuidV7.next(), email, input.displayName().trim(), null, passwords.encode(password), "ACTIVE");
        try { users.insert(user); } catch (DuplicateKeyException e) { throw conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已注册"); }
        return issue(user);
    }
    @Transactional
    public AuthDtos.Tokens login(AuthDtos.Login input, String fingerprint) {
        String email=normalize(input.email());
        if (!limiter.allow("login", fingerprint+":"+email, 10, Duration.ofMinutes(10)))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","登录尝试过于频繁");
        String plain=rsa.decrypt(input.passwordKeyId(), input.password());
        var optionalUser=users.findByEmail(email);
        boolean passwordMatches=passwords.matches(plain, optionalUser.map(UserRepository.User::passwordHash).orElse(dummyPasswordHash));
        var user=optionalUser.orElseThrow(this::badCredentials);
        if (!"ACTIVE".equals(user.status()) || !passwordMatches) throw badCredentials();
        return issue(user);
    }
    @Transactional
    public AuthDtos.Tokens refresh(String raw) {
        var old=users.activeRefresh(tokens.hash(raw)).orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN","refresh token无效或已过期"));
        users.revokeRefresh(old.id());
        var user=users.findById(old.userId()).filter(u -> "ACTIVE".equals(u.status())).orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN","用户不可用"));
        return issue(user);
    }
    @Transactional
    public void logout(UUID userId, String raw) {
        users.activeRefresh(tokens.hash(raw)).filter(t -> t.userId().equals(userId)).ifPresent(t -> users.revokeRefresh(t.id()));
    }
    @Transactional
    public void requestReset(String rawEmail, String fingerprint) {
        String email=normalize(rawEmail);
        if (!limiter.allow("password-reset", fingerprint+":"+email, 3, Duration.ofHours(1))) return;
        users.findByEmail(email).filter(u -> "ACTIVE".equals(u.status())).ifPresent(user -> {
            String raw=tokens.opaque(); users.insertReset(UuidV7.next(),user.id(),tokens.hash(raw),tokens.resetExpiry());
            mailer.send(user.email(),raw);
        });
    }
    @Transactional
    public void reset(AuthDtos.Reset input) {
        var reset=users.activeReset(tokens.hash(input.token())).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RESET_TOKEN","重置令牌无效或已过期"));
        String password=validated(rsa.decrypt(input.passwordKeyId(), input.newPassword()));
        users.updatePassword(reset.userId(),passwords.encode(password)); users.useAllResets(reset.userId()); users.revokeAllRefresh(reset.userId());
    }
    public AuthDtos.MeView me(UUID userId) {
        var user=users.findById(userId).orElseThrow(() -> unauthorized("UNAUTHORIZED","用户不存在"));
        return new AuthDtos.MeView(view(user),users.teams(userId),users.projects(userId));
    }
    @Transactional
    public AuthDtos.UserView updateMe(UUID userId, AuthDtos.UpdateMe input) {
        if (input.displayName()==null && input.avatarUrl()==null) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","至少提供一个修改字段");
        String name=input.displayName()==null?null:input.displayName().trim();
        if (name != null && name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","昵称不能为空");
        String avatar=validatedAvatar(input.avatarUrl());
        users.updateProfile(userId,name,avatar);
        return view(users.findById(userId).orElseThrow(() -> unauthorized("UNAUTHORIZED","用户不存在")));
    }
    private AuthDtos.Tokens issue(UserRepository.User user) {
        String refresh=tokens.opaque(); users.insertRefresh(UuidV7.next(),user.id(),tokens.hash(refresh),tokens.refreshExpiry());
        return new AuthDtos.Tokens(tokens.access(user.id()), tokens.accessSeconds(), refresh,
                tokens.refreshSeconds(), view(user));
    }
    private AuthDtos.UserView view(UserRepository.User u) { return new AuthDtos.UserView(u.id().toString(),u.email(),u.displayName(),u.avatarUrl()); }
    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private String validated(String value) {
        if (value.length()<8 || value.getBytes(StandardCharsets.UTF_8).length>72) throw new ApiException(HttpStatus.BAD_REQUEST,"WEAK_PASSWORD","密码至少8个字符且UTF-8编码不超过72字节");
        return value;
    }
    private String validatedAvatar(String value) {
        if (value == null) return null;
        try {
            String scheme=URI.create(value).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AVATAR_URL","头像地址必须是HTTP或HTTPS URL");
        }
    }
    private ApiException badCredentials() { return unauthorized("INVALID_CREDENTIALS","邮箱或密码错误"); }
    private ApiException unauthorized(String c,String m) { return new ApiException(HttpStatus.UNAUTHORIZED,c,m); }
    private ApiException conflict(String c,String m) { return new ApiException(HttpStatus.CONFLICT,c,m); }
}
