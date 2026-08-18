package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.*;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuthService {
    /** 注册验证码有效期（10 分钟）。 */
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(10);

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordResetTokenMapper resetTokenMapper;
    private final EmailVerificationCodeMapper verificationCodeMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectMapper projectMapper;
    private final RsaPasswordDecryptor rsa;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final PasswordResetMailer mailer;
    private final VerificationCodeMailer verificationMailer;
    private final RateLimiter limiter;
    private final String dummyPasswordHash;

    public AuthService(UserMapper userMapper, RefreshTokenMapper refreshTokenMapper,
                       PasswordResetTokenMapper resetTokenMapper, EmailVerificationCodeMapper verificationCodeMapper,
                       TeamMapper teamMapper, TeamMemberMapper teamMemberMapper,
                       ProjectMapper projectMapper, RsaPasswordDecryptor rsa,
                       PasswordEncoder passwords, TokenService tokens, PasswordResetMailer mailer,
                       VerificationCodeMailer verificationMailer, RateLimiter limiter) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.resetTokenMapper = resetTokenMapper;
        this.verificationCodeMapper = verificationCodeMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.projectMapper = projectMapper;
        this.rsa = rsa;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.verificationMailer = verificationMailer;
        this.limiter = limiter;
        this.dummyPasswordHash = passwords.encode("qgents-dummy-password-not-used");
    }

    @Transactional
    public AuthTokensResponse register(RegisterRequest input) {
        // 获取邮箱和密码
        String email = normalize(input.getEmail());
        String password = validated(rsa.decrypt(input.getPasswordKeyId(), input.getPassword()));
        // 注册必须通过邮箱验证码校验，防止假邮箱注册
        verifyCode(email, input.getVerificationCode());

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

    /**
     * 发送注册邮箱验证码：校验邮箱未注册后生成 6 位数字验证码并异步发送邮件。
     * 已注册邮箱不发送，直接返回 409（与注册时的 EMAIL_ALREADY_REGISTERED 一致）。
     * 限流按 IP+邮箱计，防止验证码轰炸。
     *
     * @param rawEmail   原始邮箱
     * @param fingerprint 请求指纹（IP）
     */
    @Transactional
    public void sendRegisterCode(String rawEmail, String fingerprint) {
        String email = normalize(rawEmail);
        if (!limiter.allow("register-code", fingerprint + ":" + email, 5, Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }
        if (findByEmail(email) != null) {
            throw conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        // 生成 6 位数字验证码
        String code = String.format(Locale.ROOT, "%06d", new Random().nextInt(1_000_000));
        // 存储哈希，禁止明文落库
        EmailVerificationCodeEntity record = new EmailVerificationCodeEntity();
        record.setId(UuidV7.next());
        record.setEmail(email);
        record.setCodeHash(tokens.hash(code));
        record.setExpiresAt(utc(Instant.now().plus(VERIFICATION_TTL)));
        verificationCodeMapper.insert(record);
        verificationMailer.send(email, code);
    }

    /**
     * 校验注册验证码：匹配该邮箱最近一条未使用且未过期的验证码，命中后标记已使用。
     * 校验失败统一返回 422 INVALID_VERIFICATION_CODE，不区分「过期/不存在/已使用」以免枚举。
     */
    private void verifyCode(String email, String rawCode) {
        List<EmailVerificationCodeEntity> candidates = verificationCodeMapper.selectList(
                Wrappers.<EmailVerificationCodeEntity>lambdaQuery()
                        .eq(EmailVerificationCodeEntity::getEmail, email)
                        .isNull(EmailVerificationCodeEntity::getUsedAt)
                        .gt(EmailVerificationCodeEntity::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC))
                        .orderByDesc(EmailVerificationCodeEntity::getCreatedAt)
                        .last("LIMIT 1"));
        EmailVerificationCodeEntity record = candidates.isEmpty() ? null : candidates.get(0);
        if (record == null) {
            throw verificationCodeInvalid();
        }
        if (!MessageDigest.isEqual(record.getCodeHash(), tokens.hash(rawCode))) {
            throw verificationCodeInvalid();
        }
        // 一次性使用：标记已用
        record.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        verificationCodeMapper.updateById(record);
    }

    private ApiException verificationCodeInvalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VERIFICATION_CODE",
                "验证码无效或已过期，请重新获取");
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
                    TeamResponse response = new TeamResponse(team.getId().toString(), team.getName(), role);
                    response.setDescription(team.getDescription());
                    response.setCreatedAt(team.getCreatedAt());
                    response.setMemberCount(memberCount(team.getId()));
                    return response;
                }).toList();
    }

    private int memberCount(UUID teamId) {
        Long count = teamMemberMapper.selectCount(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId));
        return count == null ? 0 : count.intValue();
    }

    private List<ProjectResponse> projects(UUID userId) {
        // 单次查询合并项目成员权限与 canonical Team Owner 兜底，并由 SQL 去重。
        // memberCount/repositoryCount 由项目列表/详情接口补齐，此处不额外统计（前端缺失时隐藏）。
        return projectMapper.selectAccessibleByUser(userId).stream()
                .map(project -> new ProjectResponse(project.getId().toString(), project.getTeamId().toString(),
                        project.getName(), project.getDescription(), project.getRole(), project.getStatus(),
                        null, null))
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
