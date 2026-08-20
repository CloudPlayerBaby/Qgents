ALTER TABLE github_user_authorizations
    ADD COLUMN last_error_code VARCHAR(64) NULL COMMENT '最近一次远程撤销失败的稳定错误码'
    AFTER status;
