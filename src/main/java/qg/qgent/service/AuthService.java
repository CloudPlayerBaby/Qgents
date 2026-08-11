package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.PasswordResetMailer;
import qg.qgent.auth.RateLimiter;
import qg.qgent.auth.RsaPasswordDecryptor;
import qg.qgent.auth.TokenService;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.AuthTokensResponse;
import qg.qgent.dto.LoginRequest;
import qg.qgent.dto.MeResponse;
import qg.qgent.dto.ProjectResponse;
import qg.qgent.dto.RegisterRequest;
import qg.qgent.dto.ResetPasswordRequest;
import qg.qgent.dto.TeamResponse;
import qg.qgent.dto.UpdateMeRequest;
import qg.qgent.dto.UserResponse;
import qg.qgent.entity.PasswordResetTokenEntity;
import qg.qgent.entity.RefreshTokenEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.PasswordResetTokenMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RefreshTokenMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.UserMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordResetTokenMapper resetTokenMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectMapper projectMapper;
    private final RsaPasswordDecryptor rsa;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final PasswordResetMailer mailer;
    private final RateLimiter limiter;
    private final String dummyPasswordHash;

    public AuthService(UserMapper userMapper, RefreshTokenMapper refreshTokenMapper,
            PasswordResetTokenMapper resetTokenMapper, TeamMapper teamMapper, TeamMemberMapper teamMemberMapper,
            ProjectMapper projectMapper, RsaPasswordDecryptor rsa,
            PasswordEncoder passwords, TokenService tokens, PasswordResetMailer mailer, RateLimiter limiter) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.resetTokenMapper = resetTokenMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.projectMapper = projectMapper;
        this.rsa = rsa;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.limiter = limiter;
        this.dummyPasswordHash = passwords.encode("qgents-dummy-password-not-used");
    }

    @Transactional
    public AuthTokensResponse register(RegisterRequest input) {
        // 获取邮箱和密码
        String email = normalize(input.getEmail());
        String password = validated(rsa.decrypt(input.getPasswordKeyId(), input.getPassword()));

        // 新建一个 User
        UserEntity user = new UserEntity();
        user.setId(UuidV7.next());
        user.setEmail(email);
        user.setDisplayName(input.getDisplayName().trim());
        user.setPasswordHash(passwords.encode(password));
        user.setPasswordAlgorithm("BCRYPT");
        user.setStatus("ACTIVE");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        return issue(user);
    }

    @Transactional
    public AuthTokensResponse login(LoginRequest input, String fingerprint) {
        String email = normalize(input.getEmail());
        if (!limiter.allow("login", fingerprint + ":" + email, 10, Duration.ofMinutes(10))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录尝试过于频繁");
        }

        String plain = rsa.decrypt(input.getPasswordKeyId(), input.getPassword());
        UserEntity user = findByEmail(email);
        // 如果用户不存在也要用假的密码哈希值来跑一遍，防止计算出这个邮箱有没有被注册
        boolean passwordMatches = passwords.matches(plain, user == null ? dummyPasswordHash : user.getPasswordHash());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !passwordMatches) {
            throw badCredentials();
        }
        return issue(user);
    }

    @Transactional
    public AuthTokensResponse refresh(String raw) {
        // 找到对应的refresh token
        RefreshTokenEntity old = refreshTokenMapper.selectOne(Wrappers.<RefreshTokenEntity>lambdaQuery()
                .eq(RefreshTokenEntity::getTokenHash, tokens.hash(raw)) // 检查token是否匹配
                .isNull(RefreshTokenEntity::getRevokedAt) // 没有被撤销
                .gt(RefreshTokenEntity::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC)) // 未过期
                .last("FOR UPDATE"));
        // 找不到
        if (old == null) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "refresh token无效或已过期");
        }
        // 标记为已撤销
        old.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        // 更新到数据库
        refreshTokenMapper.updateById(old);
        // 找到用户
        UserEntity user = userMapper.selectById(old.getUserId());
        // 用户不存在或状态不是ACTIVE
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "用户不可用");
        }
        return issue(user);
    }

    @Transactional
    public void logout(UUID userId, String raw) {
        // 找到对应的refresh token
        RefreshTokenEntity token = refreshTokenMapper.selectOne(Wrappers.<RefreshTokenEntity>lambdaQuery()
                .eq(RefreshTokenEntity::getTokenHash, tokens.hash(raw))
                .isNull(RefreshTokenEntity::getRevokedAt)
                .gt(RefreshTokenEntity::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC))
                .last("FOR UPDATE"));
        // 对的上了
        if (token != null && userId.equals(token.getUserId())) {
            // 就标记为已撤销
            token.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
            refreshTokenMapper.updateById(token);
        }
    }

    @Transactional
    public void requestReset(String rawEmail, String fingerprint) {
        String email = normalize(rawEmail);
        // 限流
        if (!limiter.allow("password-reset", fingerprint + ":" + email, 3, Duration.ofHours(1))) {
            return;
        }
        // 找到用户
        UserEntity user = findByEmail(email);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return;
        }
        // 生成一个随机的token
        String raw = tokens.opaque();
        // 插入到数据库
        PasswordResetTokenEntity reset = new PasswordResetTokenEntity();
        reset.setId(UuidV7.next());
        reset.setUserId(user.getId());
        reset.setTokenHash(tokens.hash(raw));
        reset.setExpiresAt(utc(tokens.resetExpiry()));
        resetTokenMapper.insert(reset);
        // 发送邮件
        mailer.send(user.getEmail(), raw);
    }

    @Transactional
    public void reset(ResetPasswordRequest input) {
        // 找到对应的reset token
        PasswordResetTokenEntity reset = resetTokenMapper.selectOne(Wrappers.<PasswordResetTokenEntity>lambdaQuery()
                .eq(PasswordResetTokenEntity::getTokenHash, tokens.hash(input.getToken()))
                .isNull(PasswordResetTokenEntity::getUsedAt)
                .gt(PasswordResetTokenEntity::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC))
                .last("FOR UPDATE"));
        // 找不到
        if (reset == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESET_TOKEN", "重置令牌无效或已过期");
        }

        // 把新密码解密
        String password = validated(rsa.decrypt(input.getPasswordKeyId(), input.getNewPassword()));
        UserEntity user = userMapper.selectById(reset.getUserId());
        user.setPasswordHash(passwords.encode(password));
        user.setPasswordAlgorithm("BCRYPT");
        userMapper.updateById(user);

        // 刷新reset和refresh token
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        resetTokenMapper.update(null, Wrappers.<PasswordResetTokenEntity>lambdaUpdate()
                .set(PasswordResetTokenEntity::getUsedAt, now)
                .eq(PasswordResetTokenEntity::getUserId, reset.getUserId())
                .isNull(PasswordResetTokenEntity::getUsedAt));
        refreshTokenMapper.update(null, Wrappers.<RefreshTokenEntity>lambdaUpdate()
                .set(RefreshTokenEntity::getRevokedAt, now)
                .eq(RefreshTokenEntity::getUserId, reset.getUserId())
                .isNull(RefreshTokenEntity::getRevokedAt));
    }

    public MeResponse me(UUID userId) {
        UserEntity user = requireUser(userId);
        return new MeResponse(view(user), teams(userId), projects(userId));
    }

    @Transactional
    public UserResponse updateMe(UUID userId, UpdateMeRequest input) {
        if (input.getDisplayName() == null && input.getAvatarUrl() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "至少提供一个修改字段");
        }
        // 更新昵称
        String name = input.getDisplayName() == null ? null : input.getDisplayName().trim();
        if (name != null && name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "昵称不能为空");
        }
        // 更新头像并验证
        String avatar = validatedAvatar(input.getAvatarUrl());
        // 写入数据库
        userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(name != null, UserEntity::getDisplayName, name)
                .set(avatar != null, UserEntity::getAvatarUrl, avatar)
                .eq(UserEntity::getId, userId));
        // 返回更新后的用户信息
        return view(requireUser(userId));
    }

    private AuthTokensResponse issue(UserEntity user) {
        // 生成一个随机的token
        String refresh = tokens.opaque();
        // 插入到数据库
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setId(UuidV7.next());
        entity.setUserId(user.getId());
        entity.setTokenHash(tokens.hash(refresh));
        entity.setExpiresAt(utc(tokens.refreshExpiry()));
        refreshTokenMapper.insert(entity);
        return new AuthTokensResponse(tokens.access(user.getId()), tokens.accessSeconds(), refresh,
                tokens.refreshSeconds(), view(user));
    }

    private UserEntity findByEmail(String email) {
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, email));
    }

    private UserEntity requireUser(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw unauthorized("UNAUTHORIZED", "用户不存在");
        }
        return user;
    }

    private List<TeamResponse> teams(UUID userId) {
        List<TeamMemberEntity> members = teamMemberMapper.selectByUserId(userId);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }
        Map<UUID, TeamEntity> teams = teamMapper.selectBatchIds(
                members.stream().map(TeamMemberEntity::getTeamId).toList()).stream()
                .filter(team -> "ACTIVE".equals(team.getStatus()))
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity()));
        return members.stream().filter(member -> teams.containsKey(member.getTeamId()))
                .map(member -> {
                    TeamEntity team = teams.get(member.getTeamId());
                    String role = team.getOwnerUserId().equals(userId) && "TEAM_OWNER".equals(member.getRole())
                            ? "TEAM_OWNER"
                            : "TEAM_MEMBER";
                    return new TeamResponse(team.getId().toString(), team.getName(), role);
                }).toList();
    }

    private List<ProjectResponse> projects(UUID userId) {
        // 单次查询合并项目成员权限与 canonical Team Owner 兜底，并由 SQL 去重。
        return projectMapper.selectAccessibleByUser(userId).stream()
                .map(project -> new ProjectResponse(project.getId().toString(), project.getTeamId().toString(),
                        project.getName(), project.getDescription(), project.getRole(), project.getStatus()))
                .toList();
    }

    private UserResponse view(UserEntity user) {
        return new UserResponse(user.getId().toString(), user.getEmail(), user.getDisplayName(), user.getAvatarUrl());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String validated(String value) {
        if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "密码至少8个字符且UTF-8编码不超过72字节");
        }
        return value;
    }

    private String validatedAvatar(String value) {
        if (value == null) {
            return null;
        }
        try {
            String scheme = URI.create(value).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR_URL", "头像地址必须是HTTP或HTTPS URL");
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private ApiException badCredentials() {
        return unauthorized("INVALID_CREDENTIALS", "邮箱或密码错误");
    }

    private ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
