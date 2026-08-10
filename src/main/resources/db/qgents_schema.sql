-- Qgents MVP database initialization schema, MySQL 8.0.16+.
-- 手动执行：mysql -u <user> -p <database> < database/qgents_schema.sql
SET
    NAMES utf8mb4;

CREATE TABLE
    users (
        id BINARY(16) PRIMARY KEY COMMENT '用户UUIDv7（二进制存储）',
        email VARCHAR(320) NOT NULL COMMENT '用户登录邮箱原始值',
        email_normalized VARCHAR(320) NOT NULL COMMENT '归一化邮箱，用于大小写无关唯一校验',
        display_name VARCHAR(120) NOT NULL COMMENT '用户展示名称',
        avatar_url TEXT NULL COMMENT '用户头像URL',
        password_hash VARCHAR(255) NOT NULL COMMENT '密码单向哈希，禁止存储明文',
        password_algorithm VARCHAR(32) NOT NULL DEFAULT 'ARGON2ID' COMMENT '密码哈希算法枚举：ARGON2ID/BCRYPT',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态枚举：ACTIVE/DISABLED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        UNIQUE KEY uk_users_email (email_normalized)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户账号与登录凭据';

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
    requirement_group_repositories (
        requirement_group_id BINARY(16) NOT NULL COMMENT '需求群ID',
        project_repository_id BINARY(16) NOT NULL COMMENT '关联的项目仓库绑定ID',
        PRIMARY KEY (requirement_group_id, project_repository_id),
        KEY idx_rgr_repo (project_repository_id),
        CONSTRAINT fk_rgr_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id) ON DELETE CASCADE,
        CONSTRAINT fk_rgr_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群与多个项目仓库关联';

CREATE TABLE
    messages (
        id BINARY(16) PRIMARY KEY COMMENT '消息UUIDv7',
        requirement_group_id BINARY(16) NOT NULL COMMENT '所属需求群ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '群内单调递增消息序号',
        author_user_id BINARY(16) NULL COMMENT '用户作者ID；系统消息时为空',
        client_message_id VARCHAR(128) NULL COMMENT '客户端生成的消息幂等ID',
        message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型枚举：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE',
        content JSON NOT NULL COMMENT '按消息类型校验的结构化内容JSON',
        mentions JSON NULL COMMENT '提及对象JSON数组，元素含type(USER/AGENT)和id',
        reply_to_message_id BINARY(16) NULL COMMENT '回复或引用的原消息ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
        UNIQUE KEY uk_msg_seq (requirement_group_id, sequence_no),
        UNIQUE KEY uk_msg_client (requirement_group_id, client_message_id),
        KEY idx_msg_user (author_user_id),
        KEY idx_msg_reply (reply_to_message_id),
        CONSTRAINT fk_msg_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id),
        CONSTRAINT fk_msg_user FOREIGN KEY (author_user_id) REFERENCES users (id),
        CONSTRAINT fk_msg_reply FOREIGN KEY (reply_to_message_id) REFERENCES messages (id),
        CHECK (
            (
                message_type = 'SYSTEM'
                AND author_user_id IS NULL
            )
            OR (
                message_type <> 'SYSTEM'
                AND author_user_id IS NOT NULL
            )
        )
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群有序消息，提及在MVP内以JSON保存';

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
    memory_message_sources (
        memory_id BINARY(16) NOT NULL COMMENT 'Memory ID',
        message_id BINARY(16) NOT NULL COMMENT '作为知识依据的消息ID',
        PRIMARY KEY (memory_id, message_id),
        KEY idx_mms_message (message_id),
        CONSTRAINT fk_mms_memory FOREIGN KEY (memory_id) REFERENCES memories (id) ON DELETE CASCADE,
        CONSTRAINT fk_mms_message FOREIGN KEY (message_id) REFERENCES messages (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Memory与多条来源消息关系';

CREATE TABLE
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

CREATE TABLE
    repository_branch_config_testsets (
        branch_config_id BINARY(16) NOT NULL COMMENT '仓库分支配置ID',
        testset_id BINARY(16) NOT NULL COMMENT '该分支门禁强制执行的测试集ID',
        PRIMARY KEY (branch_config_id, testset_id),
        KEY idx_rbct_testset (testset_id),
        CONSTRAINT fk_rbct_config FOREIGN KEY (branch_config_id) REFERENCES repository_branch_configs (id) ON DELETE CASCADE,
        CONSTRAINT fk_rbct_testset FOREIGN KEY (testset_id) REFERENCES testsets (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分支质量门禁与强制测试集关系';

CREATE TABLE
    merge_requests (
        id BINARY(16) PRIMARY KEY COMMENT 'MR镜像UUIDv7',
        project_repository_id BINARY(16) NOT NULL COMMENT '所属项目仓库绑定ID',
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

CREATE TABLE
    merge_request_groups (
        merge_request_id BINARY(16) NOT NULL COMMENT 'MR镜像ID',
        requirement_group_id BINARY(16) NOT NULL COMMENT 'MR关联的需求群ID',
        PRIMARY KEY (merge_request_id, requirement_group_id),
        KEY idx_mrg_group (requirement_group_id, merge_request_id),
        CONSTRAINT fk_mrg_mr FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id) ON DELETE CASCADE,
        CONSTRAINT fk_mrg_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MR与多个需求群关系';

CREATE TABLE
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

CREATE TABLE
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

CREATE TABLE
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
