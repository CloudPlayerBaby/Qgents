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
import qg.qgent.dto.*;
import qg.qgent.entity.EventEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TokenService tokens;
    private final TeamInvitationMailer invitationMailer;
    private final TeamDisbandService teamDisbandService;
    private final NotificationService notificationService;
    private final EventService eventService;
    private final EventMapper eventMapper;
    private final TaskMapper taskMapper;
    private final MergeRequestMapper mergeRequestMapper;

    public TeamService(TeamMapper teamMapper, TeamMemberMapper memberMapper,
                       TeamInvitationMapper invitationMapper, ProjectMemberMapper projectMemberMapper, ProjectMapper projectMapper,
                       UserMapper userMapper, TokenService tokens, TeamInvitationMailer invitationMailer,
                       TeamDisbandService teamDisbandService, NotificationService notificationService,
                       EventService eventService,
                       EventMapper eventMapper, TaskMapper taskMapper, MergeRequestMapper mergeRequestMapper) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.invitationMapper = invitationMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.tokens = tokens;
        this.invitationMailer = invitationMailer;
        this.teamDisbandService = teamDisbandService;
        this.notificationService = notificationService;
        this.eventService = eventService;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.mergeRequestMapper = mergeRequestMapper;
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
        team.setDescription(request.getDescription());
        team.setStatus("ACTIVE");
        teamMapper.insert(team);
        // 给这个团队的owner设置成创建这个的用户
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(team.getId());
        member.setUserId(actor);
        member.setRole("TEAM_OWNER");
        memberMapper.insert(member);
        // 重查一次以带回数据库生成的 created_at
        return team(teamMapper.selectById(team.getId()), "TEAM_OWNER");
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
        // 转成指定的分页响应，memberCount/description/createdAt 来自列表聚合查询
        return keysetPage(rows, pageSize, scope, TeamMembershipView::getId,
                row -> {
                    TeamResponse response = new TeamResponse(row.getId().toString(), row.getName(),
                            effectiveRole(row.getOwnerUserId(), actor, row.getRole()));
                    response.setMemberCount(row.getMemberCount());
                    response.setDescription(row.getDescription());
                    response.setCreatedAt(row.getCreatedAt());
                    return response;
                });
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
        TeamEntity team = requireTeam(teamId);
        return team(team, effectiveRole(team.getOwnerUserId(), actor, member.getRole()));
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
        // 设置并写入数据库；description 传 null 表示保留原值，传空串表示清空
        team.setName(request.getName().trim());
        if (request.getDescription() != null) {
            team.setDescription(request.getDescription());
        }
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
        TeamEntity team = requireTeam(teamId);
        int pageSize = pageSize(limit);
        String scope = "team-members:" + teamId;
        UUID anchor = decodeCursor(cursor, scope);
        List<TeamMemberView> rows = memberMapper.selectMemberPage(teamId, anchor, pageSize + 1);
        return keysetPage(rows, pageSize, scope, TeamMemberView::getUserId,
                member -> {
                    TeamMemberResponse response = new TeamMemberResponse(member.getUserId().toString(),
                            effectiveRole(team.getOwnerUserId(), member.getUserId(), member.getRole()));
                    response.setDisplayName(member.getDisplayName());
                    response.setEmail(member.getEmail());
                    return response;
                });
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
        // 被邀请邮箱已注册时，通知被邀请人（未注册用户继续走邮件邀请）
        if (invitedUser != null) {
            UserEntity inviter = userMapper.selectById(actor);
            notificationService.notify(invitedUser.getId(), null, null, "INVITED",
                    "你被邀请加入团队 " + team.getName(),
                    inviter == null ? null : "邀请人：" + inviter.getDisplayName(), teamId.toString());
        }
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
     * 当前用户收到的待处理团队邀请（收件人视角）。
     * <p>
     * 按当前登录用户邮箱的归一化值匹配 email_normalized 的 PENDING 邀请（PENDING 但已过期
     * 在响应中按 EXPIRED 展示，由调用方在事务外完成状态判定）；不返回明文邀请 token，
     * 接受时使用响应中的 id 调用 {@link #accept}。团队名与邀请人显示名页内批量补全，避免 N+1。
     *
     * @param actor  当前用户 ID
     * @param cursor 上一页游标，可为空
     * @param limit  每页数量（自动收敛到 1..100）
     * @return 收到的邀请分页结果
     */
    public PageSlice<ReceivedInvitationResponse> myInvitations(UUID actor, String cursor, Integer limit) {
        UserEntity user = userMapper.selectById(actor);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw notFound();
        }
        int pageSize = pageSize(limit);
        String scope = "my-team-invitations";
        UUID anchor = decodeCursor(cursor, scope);
        List<TeamInvitationEntity> rows = invitationMapper.selectPendingByEmail(normalize(user.getEmail()), anchor,
                pageSize + 1);
        Map<UUID, String> teamNames = loadTeamNames(rows);
        Map<UUID, String> inviterNames = loadUserDisplayNames(rows);
        return keysetPage(rows, pageSize, scope, TeamInvitationEntity::getId,
                invitation -> receivedInvitation(invitation, teamNames, inviterNames));
    }

    /**
     * 接受团队邀请。
     * <p>
     * reference 为「邀请记录 id（UUIDv7）或明文邀请 token」：若能被解析为 UUID 则按 id 查找，
     * 否则按明文 token 的 SHA-256 哈希查找（token 为 base64url 字符串、无连字符，永远不会被
     * UUID.fromString 误判，两种查找方式可安全区分）。两种路径收敛到同一接受逻辑：
     * 当前用户邮箱必须与被邀请邮箱归一化后一致；已接受且已是成员时幂等返回；过期置 EXPIRED。
     *
     * @param actor     当前用户 ID
     * @param reference 邀请记录 id 或明文邀请 token
     * @return 接受后的团队成员视图
     */
    @Transactional(noRollbackFor = PersistedApiException.class)
    public TeamMemberResponse accept(UUID actor, String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 512) {
            throw notFound();
        }
        UserEntity user = userMapper.selectById(actor);
        if (user == null) {
            throw notFound();
        }
        // reference 为邀请 id（UUIDv7）或明文邀请 token；两者以 UUID 解析区分
        TeamInvitationEntity invitation = resolveInvitation(reference);
        if (invitation == null || !normalize(user.getEmail()).equals(invitation.getEmailNormalized())) {
            throw notFound();
        }
        // 判断邀请是否已被接受
        if ("ACCEPTED".equals(invitation.getStatus())) {
            TeamMemberEntity existing = memberMapper.selectByTeamAndUser(invitation.getTeamId(), actor);
            if (existing != null) {
                TeamEntity team = requireTeam(invitation.getTeamId());
                return memberResponse(actor, team, existing.getRole(), user);
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
            // 团队级 SSE：新成员加入团队（前端 SSE 需求清单 ②）
            eventService.publishTeamEvent(invitation.getTeamId(), "team.member.updated", id(actor),
                    Map.of("teamId", id(invitation.getTeamId()), "userId", id(actor)));
        }
        // 更新邀请状态为已接受
        invitation.setStatus("ACCEPTED");
        invitation.setAcceptedAt(now());
        invitationMapper.updateById(invitation);
        TeamEntity team = requireTeam(invitation.getTeamId());
        // 通知邀请者：该用户已加入团队
        if (invitation.getInvitedBy() != null) {
            notificationService.notify(invitation.getInvitedBy(), null, null, "TEAM_JOINED",
                    user.getDisplayName() + " 已加入团队 " + team.getName(), null, team.getId().toString());
        }
        return memberResponse(actor, team, member.getRole(), user);
    }

    /**
     * 解析接受邀请的引用：reference 为邀请 id 时按主键查找（加行锁），否则视为明文 token 按其哈希查找。
     * 明文 token 由 {@link TokenService#opaque()} 生成（base64url、无连字符），不可能等于 UUIDv7 字符串，
     * 因此只有真正的邀请 id 会命中 by-id 分支，存量 token 流程行为不变。
     */
    private TeamInvitationEntity resolveInvitation(String reference) {
        UUID parsed = tryUuid(reference);
        if (parsed != null) {
            return invitationMapper.selectOne(Wrappers.<TeamInvitationEntity>lambdaQuery()
                    .eq(TeamInvitationEntity::getId, parsed).last("FOR UPDATE"));
        }
        return invitationMapper.selectOne(Wrappers.<TeamInvitationEntity>lambdaQuery()
                .eq(TeamInvitationEntity::getTokenHash, tokens.hash(reference)).last("FOR UPDATE"));
    }

    private UUID tryUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        return memberResponse(userId, team, target.getRole(), userMapper.selectById(userId));
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
        // 团队行锁后按项目ID锁定受影响项目，避免并发删除/降级造成项目失去最后一名 Admin。
        for (qg.qgent.entity.ProjectEntity project : projectMapper.selectByTeamForUpdate(teamId)) {
            qg.qgent.entity.ProjectMemberEntity projectMember = projectMemberMapper
                    .selectByProjectAndUser(project.getId(), userId);
            if (projectMember != null && "PROJECT_ADMIN".equals(projectMember.getRole())
                    && projectMemberMapper.countAdmins(project.getId()) <= 1) {
                throw conflict("LAST_PROJECT_ADMIN_REQUIRED", "该成员是项目最后一名 Project Admin");
            }
        }
        // 移除团队成员在项目中
        projectMemberMapper.deleteByTeamAndUser(teamId, userId);
        // 移除团队成员在团队成员列表
        memberMapper.deleteByTeamAndUser(teamId, userId);
        // 团队级 SSE：成员移出团队（前端 SSE 需求清单 ②）
        eventService.publishTeamEvent(teamId, "team.member.updated", id(userId),
                Map.of("teamId", id(teamId), "userId", id(userId)));
        return memberResponse(userId, team, target.getRole(), userMapper.selectById(userId));
    }

    /**
     * 解散团队：仅 Team Owner 可调用，级联删除团队下所有项目、需求群、消息、成员关系
     * 等数据（不可恢复）。与成员管理接口不同，本操作是团队生命周期终态，允许把
     * canonical Owner 所在的整个团队一并移除；删除在单事务内完成，失败整体回滚。
     *
     * @param actor  当前操作用户 ID
     * @param teamId 要解散的团队 ID
     * @return 解散前团队的摘要信息（id/name/role/memberCount/description/createdAt）
     */
    @Transactional
    public TeamResponse disband(UUID actor, UUID teamId) {
        TeamEntity team = requireTeamForUpdate(teamId);
        requireOwner(team, actor);
        // 解散前取一次摘要，避免删除后无法查询
        TeamResponse summary = team(team, "TEAM_OWNER");
        teamDisbandService.deleteTeam(teamId);
        return summary;
    }

    /**
     * 团队最近动态（基于项目事件聚合，轻量方案）。
     * <p>
     * 在 events 表上按团队 JOIN projects 拉取最近事件，覆盖事件保留窗口（24 小时）；
     * 类型过滤（type 逗号分隔、前缀匹配）与 keyset 分页都在数据库侧完成，游标为事件 id
     * （UUIDv7 自带时间序，倒序即最近优先）。事件载荷中的 UUID 字段读回为字符串，
     * 统一经 {@link #uuidOf} 转换。MESSAGE/GROUP_CREATED/MEMBER_JOINED/TASK_CREATED
     * 四类动态当前无事件来源，不在此接口产出。
     *
     * @param actor  当前用户 ID（必须是团队成员）
     * @param teamId 团队 ID
     * @param type   动态类型过滤，逗号分隔（如 TASK,MR），可为空表示全部
     * @param cursor 上一页游标，可为空
     * @param limit  每页数量（默认 20、最大 50，前端清单约定）
     * @return 团队最近动态分页结果
     */
    public PageSlice<ActivityResponse> activities(UUID actor, UUID teamId, String type, String cursor, Integer limit) {
        requireMember(teamId, actor);
        requireTeam(teamId);
        int pageSize = Math.min(Math.max(limit == null ? 20 : limit, 1), 50);
        String scope = "team-activities:" + teamId;
        UUID anchor = decodeCursor(cursor, scope);
        List<String> fragments = activityFragments(type);
        List<EventEntity> rows = eventMapper.listTeamAfter(teamId, anchor, fragments, pageSize + 1);
        Map<UUID, TaskEntity> tasks = loadTasks(rows);
        Map<UUID, MergeRequestEntity> mergeRequests = loadMergeRequests(rows);
        Map<UUID, ProjectEntity> projects = loadProjects(rows);
        Map<UUID, String> userNames = loadActivityUserNames(rows, tasks);
        return keysetPage(rows, pageSize, scope, EventEntity::getId,
                event -> activity(event, tasks, mergeRequests, projects, userNames));
    }

    // ---------- 团队动态：事件类型映射与条目构建 ----------

    /**
     * 动态类型 → SQL 过滤片段白名单（event_type 与 payload.status 的 JSON 取值约束）。
     * TASK_CREATED 复用创建任务时已发布的 task.updated(PLANNING)：PLANNING 仅出现在任务创建态，
     * 后续状态转移不会回到 PLANNING，故可安全代表「任务已创建」，无需新增独立事件发布源。
     * 仅由服务端常量引用，绝不拼接用户输入；{@link #activityFragments} 只挑选其中子集。
     */
    private static final Map<String, String> ACTIVITY_FRAGMENTS = Map.ofEntries(
            Map.entry("GROUP_CREATED", "e.event_type='project.created'"),
            Map.entry("TASK_CREATED", "e.event_type='task.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='PLANNING'"),
            Map.entry("TASK_COMPLETED", "e.event_type='task.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='SUCCEEDED'"),
            Map.entry("TASK_FAILED", "e.event_type='task.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='FAILED'"),
            Map.entry("DIFF_CREATED", "e.event_type='diff.created'"),
            Map.entry("MR_CREATED", "e.event_type='merge-request.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='OPEN'"),
            Map.entry("MR_MERGED", "e.event_type='merge-request.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='MERGED'"),
            Map.entry("TEST_RUN_FAILED", "e.event_type='test-run.updated' AND "
                    + "JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.status'))='FAILED'"));

    /**
     * 将逗号分隔的 type 过滤参数映射为 SQL 片段集合；无参数返回全部。
     * 每个过滤 token 按前缀匹配命中动态类型（TASK→TASK_COMPLETED/TASK_FAILED，MR→MR_*，精确值同样可用）；
     * 过滤 token 命中不到任何动态类型时返回 {@code List.of("1=0")}，保证查询返回空集而非全量。
     */
    private List<String> activityFragments(String type) {
        List<String> tokens = type == null || type.isBlank()
                ? List.of()
                : Arrays.stream(type.split(",")).map(String::trim).filter(t -> !t.isBlank()).toList();
        List<String> fragments = new ArrayList<>();
        for (String activityType : ACTIVITY_FRAGMENTS.keySet()) {
            if (tokens.isEmpty() || tokens.stream().anyMatch(t -> activityType.equals(t) || activityType.startsWith(t))) {
                fragments.add(ACTIVITY_FRAGMENTS.get(activityType));
            }
        }
        return fragments.isEmpty() ? List.of("1=0") : fragments;
    }

    private ActivityResponse activity(EventEntity event, Map<UUID, TaskEntity> tasks,
                                      Map<UUID, MergeRequestEntity> mergeRequests,
                                      Map<UUID, ProjectEntity> projects, Map<UUID, String> userNames) {
        String type = activityType(event);
        UUID taskId = taskIdOf(event);
        TaskEntity task = taskId == null ? null : tasks.get(taskId);
        String taskTitle = task == null ? null : task.getTitle();
        String actorId = task == null || task.getCreatedBy() == null ? null : task.getCreatedBy().toString();
        String targetType;
        String targetId;
        String targetTitle;
        String title;
        switch (type) {
            case "GROUP_CREATED" -> {
                UUID groupProjectId = uuidOf(event.getPayload() == null ? null : event.getPayload().get("projectId"));
                ProjectEntity project = groupProjectId == null ? null : projects.get(groupProjectId);
                String projectName = project == null ? null : project.getName();
                targetType = "PROJECT";
                targetId = groupProjectId == null ? null : groupProjectId.toString();
                targetTitle = projectName;
                title = "项目「" + safe(projectName) + "」已创建";
                actorId = payloadString(event, "createdBy");
            }
            case "TASK_CREATED", "TASK_COMPLETED", "TASK_FAILED" -> {
                targetType = "TASK";
                targetId = taskId.toString();
                targetTitle = taskTitle;
                title = "任务「" + safe(taskTitle) + "」" + switch (type) {
                    case "TASK_CREATED" -> "已创建";
                    case "TASK_COMPLETED" -> "已完成";
                    default -> "已失败";
                };
            }
            case "DIFF_CREATED" -> {
                UUID diffId = uuidOf(event.getPayload() == null ? null : event.getPayload().get("diffId"));
                targetType = "DIFF";
                targetId = diffId.toString();
                targetTitle = taskTitle;
                title = "任务「" + safe(taskTitle) + "」的 Diff 待验收";
            }
            case "MR_CREATED", "MR_MERGED" -> {
                UUID mrId = uuidOf(event.getResourceId());
                MergeRequestEntity mr = mrId == null ? null : mergeRequests.get(mrId);
                String mrTitle = mr == null || mr.getTitle() == null || mr.getTitle().isBlank()
                        ? "MR" : mr.getTitle();
                String mrNumber = mr == null || mr.getProviderNumber() == null
                        ? "?" : String.valueOf(mr.getProviderNumber());
                targetType = "MR";
                targetId = mrId == null ? null : mrId.toString();
                targetTitle = "#" + mrNumber + " " + mrTitle;
                title = mrTitle + " MR #" + mrNumber + ("MR_CREATED".equals(type) ? " 已创建" : " 已合并");
                actorId = null;
            }
            case "TEST_RUN_FAILED" -> {
                if (taskId != null) {
                    targetType = "TASK";
                    targetId = taskId.toString();
                    targetTitle = taskTitle;
                } else {
                    UUID projectId = uuidOf(event.getPayload() == null ? null : event.getPayload().get("projectId"));
                    ProjectEntity project = projectId == null ? null : projects.get(projectId);
                    targetType = "PROJECT";
                    targetId = projectId == null ? null : projectId.toString();
                    targetTitle = project == null ? null : project.getName();
                }
                title = "测试运行失败";
                actorId = null;
            }
            default -> throw new IllegalStateException("unmapped activity type: " + type);
        }
        String actorName = actorId == null ? null : userNames.get(UUID.fromString(actorId));
        return new ActivityResponse(event.getId().toString(), type, title, null,
                actorId == null ? null : new ActivityResponse.ActivityActor(actorId, actorName, null),
                new ActivityResponse.ActivityTarget(targetType, targetId, targetTitle), null,
                iso(event.getCreatedAt()));
    }

    /**
     * 事件 → 动态类型；SQL 片段已按状态过滤，返回值与 {@link #ACTIVITY_FRAGMENTS} 的 key 一致。
     */
    private String activityType(EventEntity event) {
        String eventType = event.getEventType();
        String status = payloadString(event, "status");
        return switch (eventType) {
            case "task.updated" -> switch (status) {
                case "PLANNING" -> "TASK_CREATED";
                case "SUCCEEDED" -> "TASK_COMPLETED";
                case "FAILED" -> "TASK_FAILED";
                default -> null;
            };
            case "project.created" -> "GROUP_CREATED";
            case "diff.created" -> "DIFF_CREATED";
            case "merge-request.updated" -> switch (status) {
                case "OPEN" -> "MR_CREATED";
                case "MERGED" -> "MR_MERGED";
                default -> null;
            };
            case "test-run.updated" -> "FAILED".equals(status) ? "TEST_RUN_FAILED" : null;
            default -> null;
        };
    }

    private String payloadString(EventEntity event, String key) {
        Object value = event.getPayload() == null ? null : event.getPayload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 事件关联的 Task id：task.updated/diff.created/test-run.updated 均在 payload.taskId 中携带。
     */
    private UUID taskIdOf(EventEntity event) {
        return uuidOf(event.getPayload() == null ? null : event.getPayload().get("taskId"));
    }

    private UUID uuidOf(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未知任务" : value;
    }

    // ---------- 团队动态：页内批量富化 ----------

    private Map<UUID, TaskEntity> loadTasks(List<EventEntity> rows) {
        Set<UUID> taskIds = rows.stream().map(this::taskIdOf).filter(Objects::nonNull).collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectBatchIds(taskIds).stream().collect(Collectors.toMap(TaskEntity::getId, t -> t));
    }

    private Map<UUID, MergeRequestEntity> loadMergeRequests(List<EventEntity> rows) {
        Set<UUID> mrIds = rows.stream()
                .filter(event -> "merge-request.updated".equals(event.getEventType()))
                .map(event -> uuidOf(event.getResourceId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (mrIds.isEmpty()) {
            return Map.of();
        }
        return mergeRequestMapper.selectBatchIds(mrIds).stream()
                .collect(Collectors.toMap(MergeRequestEntity::getId, mr -> mr));
    }

    private Map<UUID, ProjectEntity> loadProjects(List<EventEntity> rows) {
        Set<UUID> projectIds = rows.stream()
                .map(event -> uuidOf(event.getPayload() == null ? null : event.getPayload().get("projectId")))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(projectIds).stream().collect(Collectors.toMap(ProjectEntity::getId, p -> p));
    }

    private Map<UUID, String> loadActivityUserNames(List<EventEntity> rows, Map<UUID, TaskEntity> tasks) {
        Set<UUID> userIds = tasks.values().stream().map(TaskEntity::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 项目创建动态的发起人取自 project.created 载荷，而非任务关联
        for (EventEntity row : rows) {
            if ("project.created".equals(row.getEventType())) {
                UUID creator = uuidOf(row.getPayload() == null ? null : row.getPayload().get("createdBy"));
                if (creator != null) {
                    userIds.add(creator);
                }
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(UserEntity::getId,
                user -> user.getDisplayName() == null || user.getDisplayName().isBlank() ? "已注销用户"
                        : user.getDisplayName()));
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

    private String effectiveRole(UUID ownerUserId, UUID userId, String storedRole) {
        return ownerUserId != null && ownerUserId.equals(userId) && "TEAM_OWNER".equals(storedRole)
                ? "TEAM_OWNER"
                : "TEAM_MEMBER";
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
        TeamResponse response = new TeamResponse(team.getId().toString(), team.getName(), role);
        response.setMemberCount(memberCount(team.getId()));
        response.setDescription(team.getDescription());
        response.setCreatedAt(team.getCreatedAt());
        return response;
    }

    private int memberCount(UUID teamId) {
        Long count = memberMapper.selectCount(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId));
        return count == null ? 0 : count.intValue();
    }

    private TeamMemberResponse memberResponse(UUID userId, TeamEntity team, String storedRole, UserEntity user) {
        TeamMemberResponse response = new TeamMemberResponse(userId.toString(),
                effectiveRole(team.getOwnerUserId(), userId, storedRole));
        response.setDisplayName(user == null ? null : user.getDisplayName());
        response.setEmail(user == null ? null : user.getEmail());
        return response;
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

    private ReceivedInvitationResponse receivedInvitation(TeamInvitationEntity value, Map<UUID, String> teamNames,
                                                          Map<UUID, String> inviterNames) {
        String status = "PENDING".equals(value.getStatus()) && !value.getExpiresAt().isAfter(now())
                ? "EXPIRED" : value.getStatus();
        return new ReceivedInvitationResponse(value.getId().toString(), value.getTeamId().toString(),
                teamNames.get(value.getTeamId()), "TEAM_MEMBER", inviterNames.get(value.getInvitedBy()),
                status, iso(value.getExpiresAt()), iso(value.getCreatedAt()));
    }

    private Map<UUID, String> loadTeamNames(List<TeamInvitationEntity> rows) {
        Set<UUID> teamIds = rows.stream().map(TeamInvitationEntity::getTeamId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        return teamMapper.selectBatchIds(teamIds).stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
    }

    private Map<UUID, String> loadUserDisplayNames(List<TeamInvitationEntity> rows) {
        Set<UUID> userIds = rows.stream().map(TeamInvitationEntity::getInvitedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(UserEntity::getId,
                user -> user.getDisplayName() == null || user.getDisplayName().isBlank() ? "已注销用户"
                        : user.getDisplayName()));
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
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

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }
}
