package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.api.ApiException;
import qg.qgent.api.PersistedApiException;
import qg.qgent.auth.TeamInvitationMailer;
import qg.qgent.auth.TokenService;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.CreateTeamRequest;
import qg.qgent.dto.InviteTeamMemberRequest;
import qg.qgent.dto.PageInfo;
import qg.qgent.dto.PageSlice;
import qg.qgent.dto.TeamInvitationResponse;
import qg.qgent.dto.TeamMemberResponse;
import qg.qgent.dto.TeamMembershipView;
import qg.qgent.dto.TeamResponse;
import qg.qgent.dto.UpdateTeamMemberRequest;
import qg.qgent.dto.UpdateTeamRequest;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.TeamInvitationMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * 团队服务 service
 * TeamService
 */
@Service
public class TeamService {
    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final TeamInvitationMapper invitationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final TokenService tokens;
    private final TeamInvitationMailer invitationMailer;

    public TeamService(TeamMapper teamMapper, TeamMemberMapper memberMapper,
            TeamInvitationMapper invitationMapper, ProjectMemberMapper projectMemberMapper, UserMapper userMapper,
            TokenService tokens, TeamInvitationMailer invitationMailer) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.invitationMapper = invitationMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
        this.tokens = tokens;
        this.invitationMailer = invitationMailer;
    }

    /**
     * 创建团队
     * 
     * @param actor
     * @param request
     * @return
     */
    @Transactional
    public TeamResponse create(UUID actor, CreateTeamRequest request) {
        // 插入一个团队记录
        TeamEntity team = new TeamEntity();
        team.setId(UuidV7.next());
        team.setOwnerUserId(actor);
        team.setName(request.getName().trim());
        team.setStatus("ACTIVE");
        teamMapper.insert(team);
        // 给这个团队的owner设置成创建这个的用户
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(team.getId());
        member.setUserId(actor);
        member.setRole("TEAM_OWNER");
        memberMapper.insert(member);
        return team(team, "TEAM_OWNER");
    }

    /**
     * 获取团队列表
     * 
     * @param actor
     * @param cursor
     * @param limit
     * @return
     */
    public PageSlice<TeamResponse> list(UUID actor, String cursor, Integer limit) {
        int pageSize = pageSize(limit);
        String scope = "teams";
        UUID anchor = decodeCursor(cursor, scope);
        // 通过自定义sql查询团队成员列表
        List<TeamMembershipView> rows = teamMapper.selectMembershipPage(actor, anchor, pageSize + 1);
        // 转成指定的分页响应
        return keysetPage(rows, pageSize, scope, TeamMembershipView::getId,
                row -> new TeamResponse(row.getId().toString(), row.getName(), row.getRole()));
    }

    /**
     * 获取团队详情
     * 
     * @param actor
     * @param teamId
     * @return
     */
    public TeamResponse get(UUID actor, UUID teamId) {
        // 获取一个团队的详情
        TeamMemberEntity member = requireMember(teamId, actor);
        return team(requireTeam(teamId), member.getRole());
    }

    /**
     * 更新团队信息
     * 
     * @param actor
     * @param teamId
     * @param request
     * @return
     */
    @Transactional
    public TeamResponse update(UUID actor, UUID teamId, UpdateTeamRequest request) {
        // 获取一个team
        TeamEntity team = requireTeamForUpdate(teamId);
        // 检查用户是否是团队的owner
        requireOwner(team, actor);
        // 设置并写入数据库
        team.setName(request.getName().trim());
        teamMapper.updateById(team);
        return team(team, "TEAM_OWNER");
    }

    /**
     * 获取团队成员列表
     * 
     * @param actor
     * @param teamId
     * @param cursor
     * @param limit
     * @return
     */
    public PageSlice<TeamMemberResponse> members(UUID actor, UUID teamId, String cursor, Integer limit) {
        // 检查用户是否是团队的成员
        requireMember(teamId, actor);
        int pageSize = pageSize(limit);
        String scope = "team-members:" + teamId;
        UUID anchor = decodeCursor(cursor, scope);
        List<TeamMemberEntity> rows = memberMapper.selectMemberPage(teamId, anchor, pageSize + 1);
        return keysetPage(rows, pageSize, scope, TeamMemberEntity::getUserId,
                member -> new TeamMemberResponse(member.getUserId().toString(), member.getRole()));
    }

    /**
     * 邀请成员加入团队
     * 
     * @param actor
     * @param teamId
     * @param request
     * @return
     */
    @Transactional
    public TeamInvitationResponse invite(UUID actor, UUID teamId, InviteTeamMemberRequest request) {
        // 确保team存在
        TeamEntity team = requireTeamForUpdate(teamId);
        // 检查用户是否是团队的owner
        requireOwner(team, actor);
        if (!"TEAM_MEMBER".equals(request.getRole())) {
            throw invalid("邀请角色目前仅支持 TEAM_MEMBER");
        }
        if (request.getExpiresInDays() > 30) {
            throw invalid("邀请有效期不能超过30天");
        }
        String email = normalize(request.getEmail());
        // 找出来这个邮箱对应的用户记录
        UserEntity invitedUser = userMapper
                .selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getEmail, email));
        // 判断用户是否已经是团队成员
        if (invitedUser != null && memberMapper.selectByTeamAndUser(teamId, invitedUser.getId()) != null) {
            throw conflict("ALREADY_TEAM_MEMBER", "该用户已是团队成员");
        }
        // 判断用户是否已有待处理邀请
        TeamInvitationEntity pending = invitationMapper.selectOne(Wrappers.<TeamInvitationEntity>lambdaQuery()
                .eq(TeamInvitationEntity::getTeamId, teamId)
                .eq(TeamInvitationEntity::getEmailNormalized, email)
                .eq(TeamInvitationEntity::getStatus, "PENDING")
                .gt(TeamInvitationEntity::getExpiresAt, now()).last("LIMIT 1"));
        if (pending != null) {
            throw conflict("INVITATION_ALREADY_PENDING", "该邮箱已有待处理邀请");
        }
        // 生成一个随机的邀请token
        String rawToken = tokens.opaque();
        // 创建一个邀请记录并写入数据库
        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setId(UuidV7.next());
        invitation.setTeamId(teamId);
        invitation.setInvitedBy(actor);
        invitation.setEmailNormalized(email);
        invitation.setTokenHash(tokens.hash(rawToken));
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(now().plusDays(request.getExpiresInDays()));
        invitationMapper.insert(invitation);
        // 注册一个事务同步，确保在事务提交后发送邮件
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invitationMailer.send(email, rawToken);
            }
        });
        return invitation(invitation);
    }

    /**
     * 获取团队邀请列表
     * 
     * @param actor
     * @param teamId
     * @param cursor
     * @param limit
     * @return
     */
    public PageSlice<TeamInvitationResponse> invitations(UUID actor, UUID teamId, String cursor, Integer limit) {
        // 检查用户是否是团队的owner
        requireOwner(requireTeam(teamId), actor);
        int pageSize = pageSize(limit);
        String scope = "team-invitations:" + teamId;
        UUID anchor = decodeCursor(cursor, scope);
        List<TeamInvitationEntity> rows = invitationMapper.selectInvitationPage(teamId, anchor, pageSize + 1);
        return keysetPage(rows, pageSize, scope, TeamInvitationEntity::getId, this::effectiveInvitation);
    }

    /**
     * 接受团队邀请
     * 
     * @param actor
     * @param rawToken
     * @return
     */
    @Transactional(noRollbackFor = PersistedApiException.class)
    public TeamMemberResponse accept(UUID actor, String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            throw notFound();
        }
        UserEntity user = userMapper.selectById(actor);
        if (user == null) {
            throw notFound();
        }
        // 找出来这个邀请对应的记录
        TeamInvitationEntity invitation = invitationMapper.selectOne(Wrappers.<TeamInvitationEntity>lambdaQuery()
                .eq(TeamInvitationEntity::getTokenHash, tokens.hash(rawToken)).last("FOR UPDATE"));
        if (invitation == null || !normalize(user.getEmail()).equals(invitation.getEmailNormalized())) {
            throw notFound();
        }
        // 判断邀请是否已被接受
        if ("ACCEPTED".equals(invitation.getStatus())) {
            TeamMemberEntity existing = memberMapper.selectByTeamAndUser(invitation.getTeamId(), actor);
            if (existing != null) {
                return new TeamMemberResponse(actor.toString(), existing.getRole());
            }
        }
        // 判断邀请是否是待处理状态
        if (!"PENDING".equals(invitation.getStatus())) {
            throw conflict("INVITATION_NOT_PENDING", "邀请已不可接受");
        }
        // 判断邀请是否已过期
        if (!invitation.getExpiresAt().isAfter(now())) {
            invitation.setStatus("EXPIRED");
            invitationMapper.updateById(invitation);
            throw new PersistedApiException(HttpStatus.CONFLICT, "INVITATION_EXPIRED", "邀请已过期");
        }
        // 判断用户是否已经是团队成员
        TeamMemberEntity member = memberMapper.selectByTeamAndUser(invitation.getTeamId(), actor);
        if (member == null) {
            // 加入到团队成员列表
            member = new TeamMemberEntity();
            member.setTeamId(invitation.getTeamId());
            member.setUserId(actor);
            member.setRole("TEAM_MEMBER");
            memberMapper.insert(member);
        }
        // 更新邀请状态为已接受
        invitation.setStatus("ACCEPTED");
        invitation.setAcceptedAt(now());
        invitationMapper.updateById(invitation);
        return new TeamMemberResponse(actor.toString(), member.getRole());
    }

    /**
     * 撤销团队邀请
     * 
     * @param actor
     * @param teamId
     * @param invitationId
     * @return
     */
    @Transactional(noRollbackFor = PersistedApiException.class)
    public TeamInvitationResponse revoke(UUID actor, UUID teamId, UUID invitationId) {
        // 检查团队是否存在
        TeamEntity team = requireTeamForUpdate(teamId);
        // 检查用户是否是团队的owner
        requireOwner(team, actor);
        // 查询到这个邀请记录并加行锁
        TeamInvitationEntity invitation = invitationMapper.selectOne(Wrappers.<TeamInvitationEntity>lambdaQuery()
                .eq(TeamInvitationEntity::getId, invitationId).eq(TeamInvitationEntity::getTeamId, teamId)
                .last("FOR UPDATE"));
        if (invitation == null) {
            throw notFound();
        }
        // 判断邀请是否已过期
        if ("PENDING".equals(invitation.getStatus()) && !invitation.getExpiresAt().isAfter(now())) {
            invitation.setStatus("EXPIRED");
            invitationMapper.updateById(invitation);
            throw new PersistedApiException(HttpStatus.CONFLICT, "INVITATION_EXPIRED", "邀请已过期");
        }
        // 判断邀请是否是待处理状态
        if (!"PENDING".equals(invitation.getStatus())) {
            throw conflict("INVITATION_NOT_PENDING", "仅能撤销待处理邀请");
        }
        // 更新邀请状态为已撤销
        invitation.setStatus("REVOKED");
        invitationMapper.updateById(invitation);
        return invitation(invitation);
    }

    /**
     * 更新团队成员角色
     * 
     * @param actor
     * @param teamId
     * @param userId
     * @param request
     * @return
     */
    @Transactional
    public TeamMemberResponse updateMember(UUID actor, UUID teamId, UUID userId, UpdateTeamMemberRequest request) {
        // 获取到team
        TeamEntity team = requireTeamForUpdate(teamId);
        // 判断用户是否是团队的owner
        requireOwner(team, actor);
        if (!"TEAM_MEMBER".equals(request.getRole())) {
            throw invalid("成员角色仅支持 TEAM_MEMBER，不能通过成员接口设置 TEAM_OWNER");
        }
        TeamMemberEntity target = requireMember(teamId, userId);
        if (team.getOwnerUserId().equals(userId)) {
            throw conflict("TEAM_OWNER_IMMUTABLE", "Team Owner 不能通过成员角色接口降级");
        }
        // 更新团队成员角色
        target.setRole(request.getRole());
        memberMapper.updateRole(teamId, userId, request.getRole());
        return new TeamMemberResponse(userId.toString(), target.getRole());
    }

    /**
     * 移除团队成员
     * 
     * @param actor
     * @param teamId
     * @param userId
     * @return
     */
    @Transactional
    public TeamMemberResponse removeMember(UUID actor, UUID teamId, UUID userId) {
        // 获取team
        TeamEntity team = requireTeamForUpdate(teamId);
        // 检查是否是owner
        requireOwner(team, actor);
        TeamMemberEntity target = requireMember(teamId, userId);
        if (team.getOwnerUserId().equals(userId)) {
            throw conflict("TEAM_OWNER_IMMUTABLE", "Team Owner 不能被移除");
        }
        // 移除团队成员在项目中
        projectMemberMapper.deleteByTeamAndUser(teamId, userId);
        // 移除团队成员在团队成员列表
        memberMapper.deleteByTeamAndUser(teamId, userId);
        return new TeamMemberResponse(userId.toString(), target.getRole());
    }

    // 查询到团队和成员信息并检查是否存在
    private TeamMemberEntity requireMember(UUID teamId, UUID userId) {
        TeamMemberEntity member = memberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw notFound();
        }
        return member;
    }

    // 检查这个team是不是这个userId是owner
    private void requireOwner(TeamEntity team, UUID userId) {
        // 查询到team和user信息
        TeamMemberEntity member = requireMember(team.getId(), userId);
        if (!team.getOwnerUserId().equals(userId) || !"TEAM_OWNER".equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED", "需要 Team Owner 权限");
        }
    }

    // 查询到team并检查是否存在
    private TeamEntity requireTeam(UUID teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            throw notFound();
        }
        return team;
    }

    // 查询到team并加行锁
    private TeamEntity requireTeamForUpdate(UUID teamId) {
        TeamEntity team = teamMapper.selectByIdForUpdate(teamId);
        if (team == null) {
            throw notFound();
        }
        return team;
    }

    // 检查分页参数是否有效并修改
    private int pageSize(Integer requestedLimit) {
        int limit = requestedLimit == null ? 30 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_LIMIT", "limit 必须在1到100之间");
        }
        return limit;
    }

    private UUID decodeCursor(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (cursor.length() > 512) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || !expectedScope.equals(decoded.substring(0, separator))) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(decoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor 无效");
        }
    }

    private String encodeCursor(String scope, UUID anchor) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "|" + anchor).getBytes(StandardCharsets.UTF_8));
    }

    // 构建分页响应
    private <R, T> PageSlice<T> keysetPage(List<R> rows, int pageSize, String scope,
            Function<R, UUID> id, Function<R, T> convert) {
        boolean hasMore = rows.size() > pageSize;
        List<R> visible = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !visible.isEmpty()
                ? encodeCursor(scope, id.apply(visible.get(visible.size() - 1)))
                : null;
        return new PageSlice<>(visible.stream().map(convert).toList(), new PageInfo(nextCursor, hasMore));
    }

    private TeamResponse team(TeamEntity team, String role) {
        return new TeamResponse(team.getId().toString(), team.getName(), role);
    }

    private TeamInvitationResponse invitation(TeamInvitationEntity value) {
        return new TeamInvitationResponse(value.getId().toString(), value.getEmailNormalized(), value.getStatus(),
                value.getExpiresAt());
    }

    private TeamInvitationResponse effectiveInvitation(TeamInvitationEntity value) {
        TeamInvitationResponse response = invitation(value);
        if ("PENDING".equals(response.getStatus()) && !value.getExpiresAt().isAfter(now())) {
            response.setStatus("EXPIRED");
        }
        return response;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TEAM_OPERATION", message);
    }
}
