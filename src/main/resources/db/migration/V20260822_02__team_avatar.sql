-- 团队头像：teams 表增加 avatar_url（长期稳定公共读 URL，OSS 直传确认后写入；可为空）。
ALTER TABLE teams
    ADD COLUMN IF NOT EXISTS avatar_url TEXT NULL COMMENT '团队头像URL（OSS 公共读长期地址，可为空）' AFTER description;
