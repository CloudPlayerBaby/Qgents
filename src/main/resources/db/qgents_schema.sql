-- Qgents MVP database initialization schema, MySQL 8.0.16+.
-- 手动执行：mysql -u <user> -p <database> < database/qgents_schema.sql
-- This file initializes a fresh database. Existing deployments require a separate versioned migration.
SET
    NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS
    users (
        id BINARY(16) PRIMARY KEY COMMENT '用户UUIDv7（二进制存储）',
        email VARCHAR(320) NOT NULL COMMENT '归一化后的用户登录邮箱，用于大小写无关唯一校验',
        display_name VARCHAR(120) NOT NULL COMMENT '用户展示名称',
        avatar_url TEXT NULL COMMENT '用户头像URL',
        password_hash VARCHAR(255) NOT NULL COMMENT '密码单向哈希，禁止存储明文',
        password_algorithm VARCHAR(32) NOT NULL DEFAULT 'ARGON2ID' COMMENT '密码哈希算法枚举：ARGON2ID/BCRYPT',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态枚举：ACTIVE/DISABLED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        UNIQUE KEY uk_users_email (email)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户账号与登录凭据';

CREATE TABLE IF NOT EXISTS
    refresh_tokens (
        id BINARY(16) PRIMARY KEY COMMENT '刷新令牌记录UUIDv7',
        user_id BINARY(16) NOT NULL COMMENT '令牌所属用户ID',
        token_hash BINARY(32) NOT NULL COMMENT '刷新令牌SHA-256哈希，禁止存储明文',
        expires_at DATETIME (6) NOT NULL COMMENT '令牌过期时间（UTC）',
        revoked_at DATETIME (6) NULL COMMENT '令牌撤销时间（UTC），为空表示未撤销',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '签发时间（UTC）',
        UNIQUE KEY uk_refresh_hash (token_hash),
        KEY idx_refresh_user (user_id, expires_at),
        CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录刷新令牌，仅保存不可逆哈希';

CREATE TABLE IF NOT EXISTS
    password_reset_tokens (
        id BINARY(16) PRIMARY KEY COMMENT '密码重置记录UUIDv7',
        user_id BINARY(16) NOT NULL COMMENT '申请重置的用户ID',
        token_hash BINARY(32) NOT NULL COMMENT '重置令牌SHA-256哈希，禁止存储明文',
        expires_at DATETIME (6) NOT NULL COMMENT '令牌过期时间（UTC）',
        used_at DATETIME (6) NULL COMMENT '令牌使用时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '申请时间（UTC）',
        UNIQUE KEY uk_reset_hash (token_hash),
        KEY idx_reset_user (user_id, expires_at),
        CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '密码重置一次性令牌';

CREATE TABLE IF NOT EXISTS
    teams (
        id BINARY(16) PRIMARY KEY COMMENT '团队UUIDv7',
        owner_user_id BINARY(16) NOT NULL COMMENT '团队所有者用户ID',
        name VARCHAR(255) NOT NULL COMMENT '团队名称',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '团队状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_team_owner (owner_user_id),
        CONSTRAINT fk_team_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队协作顶层边界';

CREATE TABLE IF NOT EXISTS
    team_members (
        team_id BINARY(16) NOT NULL COMMENT '团队ID',
        user_id BINARY(16) NOT NULL COMMENT '成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '团队角色枚举：TEAM_OWNER/TEAM_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
        PRIMARY KEY (team_id, user_id),
        KEY idx_tm_user (user_id),
        CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队成员及角色关系';

CREATE TABLE IF NOT EXISTS
    team_invitations (
        id BINARY(16) PRIMARY KEY COMMENT '团队邀请UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '目标团队ID',
        invited_by BINARY(16) NOT NULL COMMENT '邀请发起用户ID',
        email_normalized VARCHAR(320) NOT NULL COMMENT '被邀请邮箱归一化值',
        token_hash BINARY(32) NOT NULL COMMENT '邀请令牌SHA-256哈希',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '邀请状态枚举：PENDING/ACCEPTED/REVOKED/EXPIRED',
        expires_at DATETIME (6) NOT NULL COMMENT '邀请过期时间（UTC）',
        accepted_at DATETIME (6) NULL COMMENT '接受邀请时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        UNIQUE KEY uk_invite_hash (token_hash),
        KEY idx_invite_team (team_id, status),
        KEY idx_invite_user (invited_by),
        CONSTRAINT fk_invite_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_invite_user FOREIGN KEY (invited_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队邮箱邀请';

CREATE TABLE IF NOT EXISTS
    projects (
        id BINARY(16) PRIMARY KEY COMMENT '项目UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '所属团队ID',
        created_by BINARY(16) NOT NULL COMMENT '项目创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT '项目名称',
        description TEXT NULL COMMENT '项目说明',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '项目状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_project_team (team_id, status),
        KEY idx_project_creator (created_by),
        CONSTRAINT fk_project_team FOREIGN KEY (team_id) REFERENCES teams (id),
        CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目隔离边界';

CREATE TABLE IF NOT EXISTS
    project_members (
        project_id BINARY(16) NOT NULL COMMENT '项目ID',
        user_id BINARY(16) NOT NULL COMMENT '项目成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '项目角色枚举：PROJECT_ADMIN/PROJECT_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入项目时间（UTC）',
        PRIMARY KEY (project_id, user_id),
        KEY idx_pm_user (user_id),
        CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
        CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目成员及角色关系';

CREATE TABLE IF NOT EXISTS
    github_installations (
        id BINARY(16) PRIMARY KEY COMMENT '安装记录UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '授权所属团队ID',
        provider_installation_id BIGINT UNSIGNED NOT NULL COMMENT 'GitHub App installation数字ID',
        account_login VARCHAR(255) NOT NULL COMMENT 'GitHub授权账号登录名',
        account_type VARCHAR(32) NOT NULL COMMENT 'GitHub账号类型枚举：USER/ORGANIZATION',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '安装状态枚举：ACTIVE/SUSPENDED/DELETED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '同步创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '同步更新时间（UTC）',
        UNIQUE KEY uk_ghi_provider (provider_installation_id),
        KEY idx_ghi_team (team_id, status),
        CONSTRAINT fk_ghi_team FOREIGN KEY (team_id) REFERENCES teams (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队GitHub App安装授权元数据，不存访问令牌';

CREATE TABLE IF NOT EXISTS
    github_repositories (
        id BINARY(16) PRIMARY KEY COMMENT '仓库镜像UUIDv7',
        installation_id BINARY(16) NOT NULL COMMENT '所属GitHub安装记录ID',
        provider_repository_id BIGINT UNSIGNED NOT NULL COMMENT 'GitHub仓库数字ID',
        owner_login VARCHAR(255) NOT NULL COMMENT 'GitHub仓库所有者登录名',
        name VARCHAR(255) NOT NULL COMMENT '仓库名称',
        default_branch VARCHAR(512) NOT NULL COMMENT 'GitHub默认分支名',
        visibility VARCHAR(32) NOT NULL COMMENT '仓库可见性枚举：PUBLIC/PRIVATE/INTERNAL',
        archived TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已在GitHub归档：0否1是',
        synced_at DATETIME (6) NOT NULL COMMENT '最近同步时间（UTC）',
        UNIQUE KEY uk_ghr_provider (provider_repository_id),
        KEY idx_ghr_install (installation_id),
        CONSTRAINT fk_ghr_install FOREIGN KEY (installation_id) REFERENCES github_installations (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub仓库元数据镜像';

CREATE TABLE IF NOT EXISTS
    project_repositories (
        id BINARY(16) PRIMARY KEY COMMENT '项目仓库绑定UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '项目ID',
        repository_id BINARY(16) NOT NULL COMMENT 'GitHub仓库镜像ID',
        default_branch VARCHAR(512) NOT NULL COMMENT '该项目使用的默认分支，可覆盖GitHub仓库默认值',
        display_name VARCHAR(255) NULL COMMENT '仓库在项目内的显示名称',
        bound_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '绑定时间（UTC）',
        UNIQUE KEY uk_pr_repo (project_id, repository_id),
        KEY idx_pr_repository (repository_id),
        CONSTRAINT fk_pr_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
        CONSTRAINT fk_pr_repo FOREIGN KEY (repository_id) REFERENCES github_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目与GitHub仓库绑定';

CREATE TABLE IF NOT EXISTS
    repository_branch_configs (
        id BINARY(16) PRIMARY KEY COMMENT '仓库分支配置UUIDv7',
        project_repository_id BINARY(16) NOT NULL COMMENT '所属项目仓库绑定ID',
        branch_name VARCHAR(512) NOT NULL COMMENT '应用配置的目标分支名',
        policy_json JSON NULL COMMENT '分支保护策略JSON，如合并限制和命名规则',
        required_checks JSON NULL COMMENT '必需门禁类型JSON字符串数组，可含TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        UNIQUE KEY uk_branch_config (project_repository_id, branch_name),
        CONSTRAINT fk_branch_config_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目仓库按分支配置的保护和质量门禁策略';

CREATE TABLE IF NOT EXISTS
    requirement_groups (
        id BINARY(16) PRIMARY KEY COMMENT '需求群UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT '群聊名称',
        description TEXT NULL COMMENT '群聊目标和需求背景说明',
        group_type VARCHAR(32) NOT NULL DEFAULT 'REQUIREMENT' COMMENT '群类型：PROJECT_MAIN/REQUIREMENT',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '群状态枚举：ACTIVE/ARCHIVED',
        project_main_project_id BINARY(16)
            GENERATED ALWAYS AS (
                CASE
                    WHEN group_type = 'PROJECT_MAIN' THEN project_id
                    ELSE NULL
                END
            ) STORED COMMENT 'PROJECT_MAIN uniqueness key; NULL for requirement groups',
        last_message_at DATETIME (6) NULL COMMENT '最近消息时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_rg_project (project_id, status, last_message_at),
        KEY idx_rg_creator (created_by),
        UNIQUE KEY uk_rg_project_main (project_main_project_id),
        CONSTRAINT fk_rg_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_rg_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT chk_rg_group_type
            CHECK (group_type IN ('PROJECT_MAIN', 'REQUIREMENT')),
        CONSTRAINT chk_rg_status
            CHECK (status IN ('ACTIVE', 'ARCHIVED')),
        CONSTRAINT chk_rg_project_main_active
            CHECK (group_type <> 'PROJECT_MAIN' OR status = 'ACTIVE')
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目需求讨论与协作上下文群';

CREATE TABLE IF NOT EXISTS
    requirement_group_repositories (
        requirement_group_id BINARY(16) NOT NULL COMMENT '需求群ID',
        project_repository_id BINARY(16) NOT NULL COMMENT '关联的项目仓库绑定ID',
        PRIMARY KEY (requirement_group_id, project_repository_id),
        KEY idx_rgr_repo (project_repository_id),
        CONSTRAINT fk_rgr_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id) ON DELETE CASCADE,
        CONSTRAINT fk_rgr_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群与多个项目仓库关联';

CREATE TABLE IF NOT EXISTS
    messages (
        id BINARY(16) PRIMARY KEY COMMENT '消息UUIDv7',
        requirement_group_id BINARY(16) NOT NULL COMMENT '所属需求群ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '群内单调递增消息序号',
        author_user_id BINARY(16) NULL COMMENT '用户作者ID；Agent/系统消息时为空',
        agent_id BINARY(16) NULL COMMENT 'Agent 作者ID；用户/系统消息时为空',
        client_message_id VARCHAR(128) NULL COMMENT '客户端生成的消息幂等ID',
        message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型枚举：TEXT/CODE/IMAGE/FILE/DIFF/TASK_STATUS/SYSTEM/QUOTE',
        content JSON NOT NULL COMMENT '按消息类型校验的结构化内容JSON',
        mentions JSON NULL COMMENT '提及对象JSON数组，元素含type(USER/AGENT)和id',
        reply_to_message_id BINARY(16) NULL COMMENT '回复或引用的原消息ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
        UNIQUE KEY uk_msg_seq (requirement_group_id, sequence_no),
        UNIQUE KEY uk_msg_client (requirement_group_id, client_message_id),
        KEY idx_msg_user (author_user_id),
        KEY idx_msg_agent (agent_id),
        KEY idx_msg_reply (reply_to_message_id),
        CONSTRAINT fk_msg_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id),
        CONSTRAINT fk_msg_user FOREIGN KEY (author_user_id) REFERENCES users (id),
        CONSTRAINT fk_msg_reply FOREIGN KEY (reply_to_message_id) REFERENCES messages (id),
        CHECK (
            (
                message_type = 'SYSTEM'
                AND author_user_id IS NULL
                AND agent_id IS NULL
            )
            OR (
                message_type <> 'SYSTEM'
                AND (author_user_id IS NOT NULL OR agent_id IS NOT NULL)
                AND NOT (author_user_id IS NOT NULL AND agent_id IS NOT NULL)
            )
        )
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群有序消息，提及在MVP内以JSON保存';

CREATE TABLE IF NOT EXISTS
    attachments (
        id BINARY(16) PRIMARY KEY COMMENT '附件UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '附件所属项目ID，用于上传前权限隔离',
        message_id BINARY(16) NULL COMMENT '发送消息后绑定的消息ID，上传阶段为空',
        uploaded_by BINARY(16) NOT NULL COMMENT '上传用户ID',
        object_key VARCHAR(512) NOT NULL COMMENT '对象存储内部键，不含临时访问凭证',
        file_name VARCHAR(512) NOT NULL COMMENT '原始文件名',
        media_type VARCHAR(255) NULL COMMENT 'MIME媒体类型',
        size_bytes BIGINT UNSIGNED NULL COMMENT '文件大小，单位字节',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '附件状态枚举：PENDING/READY/FAILED/DELETED',
        metadata JSON NULL COMMENT '附件扩展JSON，如图片宽高和内容哈希',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '上传记录创建时间（UTC）',
        UNIQUE KEY uk_attachment_object (object_key),
        KEY idx_attachment_message (message_id, created_at),
        KEY idx_attachment_user (uploaded_by),
        CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_attachment_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE SET NULL,
        CONSTRAINT fk_attachment_user FOREIGN KEY (uploaded_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息文件、图片和Diff附件元数据';

CREATE TABLE IF NOT EXISTS
    skills (
        id BINARY(16) PRIMARY KEY COMMENT 'Skill UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT 'Skill名称',
        content MEDIUMTEXT NOT NULL COMMENT '可复用操作规范正文',
        tags JSON NULL COMMENT '标签JSON字符串数组',
        visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性枚举：PRIVATE/PROJECT_SHARED',
        status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/ARCHIVED',
        reviewer_id BINARY(16) NULL COMMENT '最近审核用户ID',
        rejection_reason TEXT NULL COMMENT '最近驳回原因',
        reviewed_at DATETIME (6) NULL COMMENT '最近审核时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_skill_project (project_id, status, updated_at),
        KEY idx_skill_creator (created_by),
        KEY idx_skill_reviewer (reviewer_id),
        CONSTRAINT fk_skill_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_skill_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_skill_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目Skill，MVP标签内嵌JSON';

CREATE TABLE IF NOT EXISTS
    memories (
        id BINARY(16) PRIMARY KEY COMMENT 'Memory UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        title VARCHAR(255) NOT NULL COMMENT '知识标题',
        content MEDIUMTEXT NOT NULL COMMENT '经确认的项目事实正文',
        category VARCHAR(64) NOT NULL COMMENT '知识分类标识',
        tags JSON NULL COMMENT '标签JSON字符串数组',
        status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT/PENDING_REVIEW/APPROVED/REJECTED/ARCHIVED',
        reviewer_id BINARY(16) NULL COMMENT '最近审核用户ID',
        rejection_reason TEXT NULL COMMENT '最近驳回原因',
        reviewed_at DATETIME (6) NULL COMMENT '最近审核时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_memory_project (project_id, status, category, updated_at),
        KEY idx_memory_creator (created_by),
        KEY idx_memory_reviewer (reviewer_id),
        CONSTRAINT fk_memory_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_memory_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_memory_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目确认知识，MVP标签内嵌JSON';

CREATE TABLE IF NOT EXISTS
    memory_message_sources (
        memory_id BINARY(16) NOT NULL COMMENT 'Memory ID',
        message_id BINARY(16) NOT NULL COMMENT '作为知识依据的消息ID',
        PRIMARY KEY (memory_id, message_id),
        KEY idx_mms_message (message_id),
        CONSTRAINT fk_mms_memory FOREIGN KEY (memory_id) REFERENCES memories (id) ON DELETE CASCADE,
        CONSTRAINT fk_mms_message FOREIGN KEY (message_id) REFERENCES messages (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Memory与多条来源消息关系';

CREATE TABLE IF NOT EXISTS
    testsets (
        id BINARY(16) PRIMARY KEY COMMENT '测试集UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        project_repository_id BINARY(16) NULL COMMENT '限定的项目仓库绑定ID；为空表示项目通用',
        name VARCHAR(255) NOT NULL COMMENT '测试集名称',
        definition JSON NOT NULL COMMENT '测试定义JSON，包含命令、超时和通过条件',
        status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT '状态枚举：ENABLED/DISABLED',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_testset_project (project_id, status),
        KEY idx_testset_repo (project_repository_id),
        KEY idx_testset_creator (created_by),
        CONSTRAINT fk_testset_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_testset_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id),
        CONSTRAINT fk_testset_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目自定义测试集';

CREATE TABLE IF NOT EXISTS
    repository_branch_config_testsets (
        branch_config_id BINARY(16) NOT NULL COMMENT '仓库分支配置ID',
        testset_id BINARY(16) NOT NULL COMMENT '该分支门禁强制执行的测试集ID',
        PRIMARY KEY (branch_config_id, testset_id),
        KEY idx_rbct_testset (testset_id),
        CONSTRAINT fk_rbct_config FOREIGN KEY (branch_config_id) REFERENCES repository_branch_configs (id) ON DELETE CASCADE,
        CONSTRAINT fk_rbct_testset FOREIGN KEY (testset_id) REFERENCES testsets (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分支质量门禁与强制测试集关系';

CREATE TABLE IF NOT EXISTS
    merge_requests (
        id BINARY(16) PRIMARY KEY COMMENT 'MR镜像UUIDv7',
        project_repository_id BINARY(16) NOT NULL COMMENT '所属项目仓库绑定ID',
        task_id BINARY(16) NULL COMMENT 'Owning Task',
        workspace_id BINARY(16) NULL COMMENT 'Source Workspace',
        provider VARCHAR(32) NOT NULL DEFAULT 'GITHUB' COMMENT '代码托管提供方枚举：GITHUB',
        provider_number BIGINT UNSIGNED NOT NULL COMMENT 'GitHub Pull Request编号',
        source_branch VARCHAR(512) NOT NULL COMMENT '源分支名',
        target_branch VARCHAR(512) NOT NULL COMMENT '目标分支名',
        head_commit VARCHAR(128) NOT NULL COMMENT '当前MR头提交SHA',
        title TEXT NULL COMMENT 'MR标题',
        status VARCHAR(32) NOT NULL COMMENT 'MR状态枚举：OPEN/MERGED/CLOSED',
        quality_gate_status VARCHAR(32) NULL COMMENT '门禁汇总状态枚举：PENDING/PASSED/FAILED',
        provider_updated_at DATETIME (6) NULL COMMENT 'GitHub侧更新时间（UTC）',
        synced_at DATETIME (6) NOT NULL COMMENT '本地最近同步时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '镜像创建时间（UTC）',
        UNIQUE KEY uk_mr_provider (project_repository_id, provider, provider_number),
        KEY idx_mr_repo (
            project_repository_id,
            status,
            provider_updated_at
        ),
        CONSTRAINT fk_mr_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub Pull Request业务镜像';

CREATE TABLE IF NOT EXISTS
    merge_request_groups (
        merge_request_id BINARY(16) NOT NULL COMMENT 'MR镜像ID',
        requirement_group_id BINARY(16) NOT NULL COMMENT 'MR关联的需求群ID',
        PRIMARY KEY (merge_request_id, requirement_group_id),
        KEY idx_mrg_group (requirement_group_id, merge_request_id),
        CONSTRAINT fk_mrg_mr FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id) ON DELETE CASCADE,
        CONSTRAINT fk_mrg_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MR与多个需求群关系';

CREATE TABLE IF NOT EXISTS
    quality_check_results (
        id BINARY(16) PRIMARY KEY COMMENT '质量检查结果UUIDv7',
        merge_request_id BINARY(16) NOT NULL COMMENT '所属MR ID',
        check_type VARCHAR(32) NOT NULL COMMENT '检查类型枚举：TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE',
        attempt_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '相同提交和检查类型的执行序号，从1开始',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '执行状态枚举：PENDING/RUNNING/PASSED/FAILED',
        testset_id BINARY(16) NULL COMMENT 'TESTSET检查使用的测试集ID',
        commit_sha VARCHAR(128) NOT NULL COMMENT '检查对应的Git提交SHA',
        source VARCHAR(64) NOT NULL COMMENT '结果来源服务标识',
        summary JSON NULL COMMENT '检查摘要JSON，包含统计、失败项和脱敏日志引用',
        started_at DATETIME (6) NULL COMMENT '开始执行时间（UTC）',
        completed_at DATETIME (6) NULL COMMENT '完成执行时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间（UTC）',
        UNIQUE KEY uk_check_attempt (
            merge_request_id,
            check_type,
            commit_sha,
            attempt_no
        ),
        KEY idx_check_mr (merge_request_id, status, check_type),
        KEY idx_check_test (testset_id),
        CONSTRAINT fk_check_mr FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id) ON DELETE CASCADE,
        CONSTRAINT fk_check_test FOREIGN KEY (testset_id) REFERENCES testsets (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MR真实质量门禁执行结果';

CREATE TABLE IF NOT EXISTS
    merge_request_reviews (
        id BINARY(16) PRIMARY KEY COMMENT 'MR审查UUIDv7',
        merge_request_id BINARY(16) NOT NULL COMMENT '所属MR ID',
        review_kind VARCHAR(16) NOT NULL COMMENT '审查主体枚举：HUMAN/AI，AI结果仅记录外部同步摘要',
        reviewer_user_id BINARY(16) NULL COMMENT '人工审查用户ID；AI审查时为空',
        reviewer_name VARCHAR(255) NULL COMMENT '外部AI或人工审查主体展示名',
        provider_review_id VARCHAR(255) NULL COMMENT 'GitHub Review外部ID',
        decision VARCHAR(32) NULL COMMENT '审查结论枚举：APPROVED/CHANGES_REQUESTED/COMMENTED',
        summary TEXT NULL COMMENT '审查意见摘要',
        reviewed_at DATETIME (6) NOT NULL COMMENT '审查发生时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '同步创建时间（UTC）',
        KEY idx_review_mr (merge_request_id, review_kind, reviewed_at),
        KEY idx_review_user (reviewer_user_id),
        CONSTRAINT fk_review_mr FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id) ON DELETE CASCADE,
        CONSTRAINT fk_review_user FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
        CHECK (
            (
                review_kind = 'HUMAN'
                AND reviewer_user_id IS NOT NULL
                AND reviewer_name IS NULL
            )
            OR (
                review_kind = 'AI'
                AND reviewer_user_id IS NULL
                AND reviewer_name IS NOT NULL
            )
        )
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MR人工或外部AI审查记录';

CREATE TABLE IF NOT EXISTS
    idempotency_records (
        id BINARY(16) PRIMARY KEY COMMENT '幂等记录UUIDv7',
        actor_user_id BINARY(16) NULL COMMENT '已认证调用用户ID；匿名请求为空',
        actor_fingerprint BINARY(32) NOT NULL COMMENT '调用者稳定指纹SHA-256',
        scope VARCHAR(255) NOT NULL COMMENT '幂等业务作用域，如HTTP方法与路由模板',
        idempotency_key VARCHAR(255) NOT NULL COMMENT '客户端Idempotency-Key原值',
        request_hash BINARY(32) NOT NULL COMMENT '规范化请求体SHA-256，用于检测同键不同请求',
        response_status SMALLINT UNSIGNED NULL COMMENT '首次请求HTTP响应状态码',
        response_body_redacted JSON NULL COMMENT '确认不含Token或Secret的脱敏响应JSON',
        resource_id BINARY(16) NULL COMMENT '首次请求创建或变更的资源UUID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次请求时间（UTC）',
        expires_at DATETIME (6) NOT NULL COMMENT '幂等记录失效时间（UTC）',
        UNIQUE KEY uk_idempotency (actor_fingerprint, scope, idempotency_key),
        KEY idx_idem_user (actor_user_id),
        KEY idx_idem_expiry (expires_at),
        CONSTRAINT fk_idem_user FOREIGN KEY (actor_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '写接口幂等请求与脱敏响应缓存';

-- ============================================================================
-- Task -> TaskStep -> TaskRun execution, Task-level Diff/MR results and events.
-- ============================================================================

CREATE TABLE IF NOT EXISTS agents (
    id BINARY(16) PRIMARY KEY, team_id BINARY(16) NOT NULL, created_by BINARY(16) NULL,
    name VARCHAR(255) NOT NULL, role VARCHAR(32) NOT NULL,
    avatar TEXT NULL COMMENT 'Agent 头像URL', capabilities JSON NULL COMMENT '能力标签JSON数组',
    prompt TEXT NULL COMMENT 'Agent 系统提示词',
    visibility VARCHAR(16) NOT NULL DEFAULT 'TEAM',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    KEY idx_agent_team(team_id,status), CONSTRAINT fk_agent_team FOREIGN KEY(team_id) REFERENCES teams(id),
    CONSTRAINT fk_agent_creator FOREIGN KEY(created_by) REFERENCES users(id),
    CONSTRAINT ck_agent_visibility CHECK(visibility IN ('TEAM','PRIVATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Team-scoped assignable Agent identities';

CREATE TABLE IF NOT EXISTS group_agents (
    requirement_group_id BINARY(16) NOT NULL,
    agent_id BINARY(16) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (requirement_group_id, agent_id),
    KEY idx_ga_agent(agent_id),
    CONSTRAINT fk_ga_group FOREIGN KEY(requirement_group_id) REFERENCES requirement_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_ga_agent FOREIGN KEY(agent_id) REFERENCES agents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊 Agent 参与者（Agent 首次回群时自动加入）';

CREATE TABLE IF NOT EXISTS
    agent_skill_bindings (
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID（隔离同一 Agent 在不同项目的技能集）',
        agent_id BINARY(16) NOT NULL COMMENT 'Team 级 Agent ID',
        skill_id BINARY(16) NOT NULL COMMENT '项目内 Skill ID',
        created_by BINARY(16) NOT NULL COMMENT '绑定发起用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '绑定时间（UTC）',
        PRIMARY KEY (project_id, agent_id, skill_id),
        KEY idx_asb_agent (agent_id),
        KEY idx_asb_skill (skill_id),
        CONSTRAINT fk_asb_agent FOREIGN KEY (agent_id) REFERENCES agents (id) ON DELETE CASCADE,
        CONSTRAINT fk_asb_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent-Skill 绑定关系（按项目隔离，复合主键）';

CREATE TABLE IF NOT EXISTS tasks (
    id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, requirement_group_id BINARY(16) NOT NULL,
    trigger_message_id BINARY(16) NULL, workspace_id BINARY(16) NOT NULL, continuation_of_task_id BINARY(16) NULL,
    title VARCHAR(255) NOT NULL, requirement TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNING' COMMENT 'PLANNING/PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLING/CANCELLED', created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_task_project(project_id,status), KEY idx_task_group(requirement_group_id), KEY idx_task_workspace(workspace_id),
    CONSTRAINT fk_task_project FOREIGN KEY(project_id) REFERENCES projects(id),
    CONSTRAINT fk_task_group FOREIGN KEY(requirement_group_id) REFERENCES requirement_groups(id),
    CONSTRAINT fk_task_message FOREIGN KEY(trigger_message_id) REFERENCES messages(id),
    CONSTRAINT fk_task_continuation FOREIGN KEY(continuation_of_task_id) REFERENCES tasks(id),
    CONSTRAINT fk_task_creator FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-visible requirement execution';

CREATE TABLE IF NOT EXISTS workspaces (
    id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL,
    storage_key VARCHAR(512) NOT NULL COMMENT 'Opaque storage key, not a host path', status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_workspace_storage(storage_key),
    CONSTRAINT fk_workspace_project FOREIGN KEY(project_id) REFERENCES projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent project development workspace';

ALTER TABLE tasks ADD CONSTRAINT fk_task_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id);
ALTER TABLE merge_requests
    ADD CONSTRAINT fk_mr_task FOREIGN KEY(task_id) REFERENCES tasks(id),
    ADD CONSTRAINT fk_mr_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id);

CREATE TABLE IF NOT EXISTS workspace_repositories (
    workspace_id BINARY(16) NOT NULL, project_repository_id BINARY(16) NOT NULL, workspace_path VARCHAR(255) NOT NULL,
    base_commit VARCHAR(128) NULL, source_branch VARCHAR(512) NOT NULL, head_commit VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY(workspace_id,project_repository_id), UNIQUE KEY uk_workspace_repository_path(workspace_id,workspace_path),
    CONSTRAINT fk_workspace_repo_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_repo_repository FOREIGN KEY(project_repository_id) REFERENCES project_repositories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent repository worktrees in one Workspace';

CREATE TABLE IF NOT EXISTS task_steps (
    id BINARY(16) PRIMARY KEY, task_id BINARY(16) NOT NULL, sequence_no INT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL, instruction TEXT NOT NULL, role VARCHAR(32) NOT NULL, assigned_agent_id BINARY(16) NULL,
    acceptance_criteria TEXT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_task_step_sequence(task_id,sequence_no),
    CONSTRAINT fk_task_step_task FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_step_agent FOREIGN KEY(assigned_agent_id) REFERENCES agents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Planner-defined workflow steps';

CREATE TABLE IF NOT EXISTS task_step_dependencies (
    task_step_id BINARY(16) NOT NULL, depends_on_task_step_id BINARY(16) NOT NULL,
    PRIMARY KEY(task_step_id,depends_on_task_step_id),
    CONSTRAINT fk_step_dep_step FOREIGN KEY(task_step_id) REFERENCES task_steps(id) ON DELETE CASCADE,
    CONSTRAINT fk_step_dep_parent FOREIGN KEY(depends_on_task_step_id) REFERENCES task_steps(id) ON DELETE CASCADE,
    CONSTRAINT ck_step_not_self CHECK(task_step_id <> depends_on_task_step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Task-step dependency DAG edges';

CREATE TABLE IF NOT EXISTS task_step_repositories (
    task_step_id BINARY(16) NOT NULL, project_repository_id BINARY(16) NOT NULL, access_mode VARCHAR(8) NOT NULL DEFAULT 'READ',
    PRIMARY KEY(task_step_id,project_repository_id),
    CONSTRAINT fk_step_repo_step FOREIGN KEY(task_step_id) REFERENCES task_steps(id) ON DELETE CASCADE,
    CONSTRAINT fk_step_repo_repository FOREIGN KEY(project_repository_id) REFERENCES project_repositories(id),
    CONSTRAINT ck_step_repo_access CHECK(access_mode IN ('READ','WRITE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Repository access scope per task step';

CREATE TABLE IF NOT EXISTS
    task_runs (
        id BINARY(16) PRIMARY KEY COMMENT '任务运行UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID，用于项目隔离',
        task_id BINARY(16) NOT NULL COMMENT 'Confirmed owning Task',
        task_step_id BINARY(16) NOT NULL COMMENT 'Confirmed planned TaskStep',
        agent_id BINARY(16) NULL COMMENT 'Execution-time Agent identity; FK deferred',
        role VARCHAR(32) NOT NULL COMMENT '执行角色枚举：ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER/GENERAL',
        status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT '运行状态枚举：QUEUED/RUNNING/SUCCEEDED/FAILED/WAITING_INPUT/WAITING_APPROVAL/BLOCKED/CANCELLING/CANCELLED',
        retry_of_task_run_id BINARY(16) NULL COMMENT '重试来源的任务运行ID，为空表示首次运行',
        started_at DATETIME (6) NULL COMMENT '开始执行时间（UTC）',
        finished_at DATETIME (6) NULL COMMENT '结束时间（UTC）',
        created_by BINARY(16) NOT NULL COMMENT '发起用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_task_run_project (project_id, status),
        KEY idx_task_run_task (task_id, task_step_id),
        KEY idx_task_run_creator (created_by),
        CONSTRAINT fk_task_run_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_task_run_task FOREIGN KEY (task_id) REFERENCES tasks (id),
        CONSTRAINT fk_task_run_task_step FOREIGN KEY (task_step_id) REFERENCES task_steps (id),
        CONSTRAINT fk_task_run_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
        CONSTRAINT fk_task_run_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_task_run_retry FOREIGN KEY (retry_of_task_run_id) REFERENCES task_runs (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'One controlled execution attempt of a TaskStep';

CREATE TABLE IF NOT EXISTS
    execution_logs (
        id BINARY(16) PRIMARY KEY COMMENT '日志UUIDv7',
        task_run_id BINARY(16) NOT NULL COMMENT '所属任务运行ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '运行内单调递增日志序号',
        node VARCHAR(64) NULL COMMENT '产生日志的节点名；单节点运行为空',
        content TEXT NOT NULL COMMENT '已脱敏的日志内容，禁止包含Token/密码/密钥',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '写入时间（UTC）',
        UNIQUE KEY uk_log_seq (task_run_id, sequence_no),
        CONSTRAINT fk_log_run FOREIGN KEY (task_run_id) REFERENCES task_runs (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '已脱敏的执行日志，支持游标续读';

CREATE TABLE IF NOT EXISTS
    input_requests (
        id BINARY(16) PRIMARY KEY COMMENT '输入请求UUIDv7',
        task_run_id BINARY(16) NOT NULL COMMENT '所属任务运行ID',
        kind VARCHAR(16) NOT NULL COMMENT '请求类型枚举：INPUT/APPROVAL',
        status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态枚举：PENDING/ANSWERED/APPROVED/REJECTED/EXPIRED',
        prompt TEXT NOT NULL COMMENT '面向用户或审批人的问题描述',
        options JSON NULL COMMENT '可选答案选项JSON数组',
        answer JSON NULL COMMENT 'INPUT类型的用户回答JSON',
        reason TEXT NULL COMMENT '审批/拒绝理由或回复备注',
        created_by BINARY(16) NOT NULL COMMENT '发起请求的用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        resolved_at DATETIME (6) NULL COMMENT '回答/审批/拒绝处理时间（UTC）',
        KEY idx_input_run (task_run_id, status),
        KEY idx_input_creator (created_by),
        CONSTRAINT fk_input_run FOREIGN KEY (task_run_id) REFERENCES task_runs (id) ON DELETE CASCADE,
        CONSTRAINT fk_input_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务运行期间的人机输入/审批请求';

CREATE TABLE IF NOT EXISTS
    diffs (
        id BINARY(16) PRIMARY KEY COMMENT 'Diff UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        task_id BINARY(16) NOT NULL COMMENT 'Owning Task',
        task_run_id BINARY(16) NULL COMMENT '产出该 Diff 的任务运行ID；未绑定运行前为空',
        task_step_id BINARY(16) NULL COMMENT '产出该 Diff 的任务步骤ID；未绑定步骤前为空',
        workspace_id BINARY(16) NOT NULL COMMENT 'Reviewed Workspace',
        project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定ID',
        base_commit VARCHAR(128) NOT NULL COMMENT 'Immutable comparison base',
        source_branch VARCHAR(512) NOT NULL COMMENT 'Feature branch used after acceptance',
        working_tree_hash VARCHAR(128) NOT NULL COMMENT 'Digest of the exact reviewed working tree',
        snapshot_key VARCHAR(512) NULL COMMENT 'Controlled patch snapshot object key',
        head_commit VARCHAR(128) NULL COMMENT 'Commit created after acceptance',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
        reviewed_by BINARY(16) NULL, review_reason TEXT NULL, reviewed_at DATETIME(6) NULL,
        change_stats JSON NULL COMMENT '变更统计JSON，如文件数、增删行数',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
        KEY idx_diff_project (project_id), KEY idx_diff_task(task_id,status), KEY idx_diff_task_run(task_run_id),
        CONSTRAINT fk_diff_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_diff_task FOREIGN KEY(task_id) REFERENCES tasks(id),
        CONSTRAINT fk_diff_task_run FOREIGN KEY(task_run_id) REFERENCES task_runs(id),
        CONSTRAINT fk_diff_task_step FOREIGN KEY(task_step_id) REFERENCES task_steps(id),
        CONSTRAINT fk_diff_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id),
        CONSTRAINT fk_diff_reviewer FOREIGN KEY(reviewed_by) REFERENCES users(id),
        CONSTRAINT fk_diff_repository FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Immutable Task working-tree Diff snapshots';

CREATE TABLE IF NOT EXISTS
    diff_files (
        id BINARY(16) PRIMARY KEY COMMENT 'Diff文件UUIDv7',
        diff_id BINARY(16) NOT NULL COMMENT '所属Diff ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT 'Diff内单调递增文件序号',
        path VARCHAR(1024) NOT NULL COMMENT '文件路径',
        change_type VARCHAR(16) NOT NULL COMMENT '变更类型枚举：ADDED/MODIFIED/DELETED/RENAMED',
        additions INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '新增行数',
        deletions INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除行数',
        binary_flag TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否二进制文件：0否1是',
        hunks JSON NULL COMMENT 'hunk摘要JSON数组，完整行级内容由受控服务按需提供',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        UNIQUE KEY uk_diff_file_seq (diff_id, sequence_no),
        CONSTRAINT fk_diff_file_diff FOREIGN KEY (diff_id) REFERENCES diffs (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Diff文件变更摘要';

CREATE TABLE IF NOT EXISTS
    diff_comments (
        id BINARY(16) PRIMARY KEY COMMENT 'Diff审查意见UUIDv7',
        diff_id BINARY(16) NOT NULL COMMENT '所属Diff ID',
        path VARCHAR(1024) NOT NULL COMMENT '评论指向的文件路径',
        side VARCHAR(8) NULL COMMENT '变更侧枚举：LEFT/RIGHT',
        line INT UNSIGNED NULL COMMENT '行号，行级评论必填',
        hunk_id VARCHAR(64) NULL COMMENT 'hunk标识，hunk级评论使用',
        commit_sha VARCHAR(128) NULL COMMENT '已提交Diff的SHA；未提交Diff通过diff_id绑定不可变快照',
        body TEXT NOT NULL COMMENT '审查意见正文',
        author_user_id BINARY(16) NOT NULL COMMENT '评论作者用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        KEY idx_diff_comment_diff (diff_id, created_at),
        KEY idx_diff_comment_author (author_user_id),
        CONSTRAINT fk_diff_comment_diff FOREIGN KEY (diff_id) REFERENCES diffs (id) ON DELETE CASCADE,
        CONSTRAINT fk_diff_comment_author FOREIGN KEY (author_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Diff行级或hunk级审查意见';

CREATE TABLE IF NOT EXISTS
    test_runs (
        id BINARY(16) PRIMARY KEY COMMENT '测试运行UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        task_id BINARY(16) NULL COMMENT 'Owning Task when workflow-triggered',
        task_step_id BINARY(16) NULL COMMENT 'Requesting TaskStep',
        project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定ID',
        ref VARCHAR(512) NULL COMMENT '目标提交或分支引用',
        testset_ids JSON NOT NULL COMMENT '启用测试集ID JSON数组',
        status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT '状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED',
        summary JSON NULL COMMENT '用例与结果摘要JSON',
        created_by BINARY(16) NOT NULL COMMENT '发起用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_test_run_project (project_id, status),
        KEY idx_test_run_task (task_id, task_step_id),
        KEY idx_test_run_repository (project_repository_id),
        CONSTRAINT fk_test_run_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_test_run_task FOREIGN KEY (task_id) REFERENCES tasks (id),
        CONSTRAINT fk_test_run_task_step FOREIGN KEY (task_step_id) REFERENCES task_steps (id),
        CONSTRAINT fk_test_run_repository FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id),
        CONSTRAINT fk_test_run_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '受控测试运行，真实执行由执行服务承担';

CREATE TABLE IF NOT EXISTS
    dry_runs (
        id BINARY(16) PRIMARY KEY COMMENT '试运行UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        task_id BINARY(16) NULL COMMENT 'Owning Task when workflow-triggered',
        task_step_id BINARY(16) NULL COMMENT 'Requesting TaskStep',
        project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定ID',
        head_commit VARCHAR(128) NOT NULL COMMENT '试运行针对的确定提交SHA',
        target_branch VARCHAR(512) NOT NULL COMMENT '目标分支名',
        status VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT '状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED',
        report JSON NULL COMMENT '试运行报告JSON，含冲突与测试摘要',
        created_by BINARY(16) NOT NULL COMMENT '发起用户ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_dry_run_project (project_id, status),
        KEY idx_dry_run_task (task_id, task_step_id),
        KEY idx_dry_run_repository (project_repository_id),
        CONSTRAINT fk_dry_run_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_dry_run_task FOREIGN KEY (task_id) REFERENCES tasks (id),
        CONSTRAINT fk_dry_run_task_step FOREIGN KEY (task_step_id) REFERENCES task_steps (id),
        CONSTRAINT fk_dry_run_repository FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id),
        CONSTRAINT fk_dry_run_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合并前试运行，真实报告由执行服务写入';

CREATE TABLE IF NOT EXISTS
    events (
        id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        requirement_group_id BINARY(16) NULL COMMENT '可选关联需求群ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '项目内单调递增事件序号，作为SSE游标',
        event_type VARCHAR(64) NOT NULL COMMENT '事件类型，如task-run.updated/diff.created/merge-request.updated',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如taskRunId',
        payload JSON NOT NULL COMMENT '脱敏事件载荷JSON，禁止包含凭证',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        UNIQUE KEY uk_event_seq (project_id, sequence_no),
        KEY idx_event_type (event_type, created_at),
        CONSTRAINT fk_event_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_event_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目级实时事件存储，至少保留24小时';

-- 幂等增量迁移：为 merge_requests 补充 MR 作者列与外键（服务端判定 CQ 审查人时使用）。
-- 全新整库初始化时上方 merge_requests 建表不包含该列，由本 ALTER 补齐；
-- 已存在该列的库自动跳过，脚本整体可重复执行。
-- 说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，此处用 information_schema 探测 + PREPARE 动态执行实现幂等。
SET @mr_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests' AND COLUMN_NAME = 'author_user_id'
);
SET @mr_alter_sql = IF(@mr_col_exists = 0,
    CONCAT('ALTER TABLE merge_requests ADD COLUMN author_user_id BINARY(16) NULL COMMENT ''MR作者用户ID，用于CQ权限校验'' AFTER title, ',
           'ADD CONSTRAINT fk_mr_author FOREIGN KEY (author_user_id) REFERENCES users (id)'),
    'SELECT 1');
PREPARE mr_alter_stmt FROM @mr_alter_sql;
EXECUTE mr_alter_stmt;
DEALLOCATE PREPARE mr_alter_stmt;

-- 幂等增量迁移：为 messages.agent_id 补充指向 agents 的外键（Agent 消息必须引用真实 Agent）。
-- agents 建表晚于 messages，FK 不能写进 messages 的 CREATE 语句，因此改在末尾补 ALTER。
-- 已存在该约束的库自动跳过，脚本整体可重复执行。
SET @msg_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND CONSTRAINT_NAME = 'fk_msg_agent'
);
SET @msg_fk_sql = IF(@msg_fk_exists = 0,
    'ALTER TABLE messages ADD CONSTRAINT fk_msg_agent FOREIGN KEY (agent_id) REFERENCES agents (id)',
    'SELECT 1');
PREPARE msg_fk_stmt FROM @msg_fk_sql;
EXECUTE msg_fk_stmt;
DEALLOCATE PREPARE msg_fk_stmt;

-- 幂等增量迁移：为 agents 表补充身份卡字段（头像/能力标签/提示词，契约 §11.1、产品需求 §2.3）。
-- 全新整库初始化时上方 agents 建表已包含这三列，此处仅服务已存在的库。
SET @agent_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'avatar'
);
SET @agent_alter_sql = IF(@agent_col_exists = 0,
    CONCAT('ALTER TABLE agents ADD COLUMN avatar TEXT NULL COMMENT ''Agent 头像URL'' AFTER role, ',
           'ADD COLUMN capabilities JSON NULL COMMENT ''能力标签JSON数组'' AFTER avatar, ',
           'ADD COLUMN prompt TEXT NULL COMMENT ''Agent 系统提示词'' AFTER capabilities'),
    'SELECT 1');
PREPARE agent_alter_stmt FROM @agent_alter_sql;
EXECUTE agent_alter_stmt;
DEALLOCATE PREPARE agent_alter_stmt;

-- 通知中心：按用户维度持久化的通知（A 联调约定 §1）。
-- 由 task.updated/input-required/approval-required/diff.created/merge-request.updated 等事件触发写入；
-- SSE 只负责实时提醒，历史列表与已读状态由本表提供。
CREATE TABLE IF NOT EXISTS
    notifications (
        id BINARY(16) PRIMARY KEY COMMENT '通知UUIDv7',
        recipient_user_id BINARY(16) NOT NULL COMMENT '接收通知的用户ID',
        project_id BINARY(16) NULL COMMENT '关联项目ID；系统级通知为空',
        requirement_group_id BINARY(16) NULL COMMENT '关联需求群ID；非群通知为空',
        kind VARCHAR(64) NOT NULL COMMENT '通知类型枚举：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING',
        title VARCHAR(255) NOT NULL COMMENT '一行通知标题',
        description TEXT NULL COMMENT '通知描述正文',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如taskId/mrId/diffId',
        is_read TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已读：0未读1已读',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        read_at DATETIME (6) NULL COMMENT '已读时间（UTC），未读为空',
        KEY idx_notif_user (recipient_user_id, is_read, created_at),
        KEY idx_notif_project (project_id),
        KEY idx_notif_group (requirement_group_id),
        CONSTRAINT fk_notif_user FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT fk_notif_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_notif_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户通知中心';
