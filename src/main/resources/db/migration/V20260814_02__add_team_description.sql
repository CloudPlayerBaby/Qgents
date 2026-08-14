ALTER TABLE teams
    ADD COLUMN description TEXT NULL COMMENT '团队简介' AFTER name;
