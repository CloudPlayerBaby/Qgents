-- V20260822_02: teams.avatar_url（团队头像）
-- 背景：团队头像上传（credential/confirm）确认后写入 teams.avatar_url（OSS 公共读长期地址）。
-- 幂等可重复执行；全新整库初始化时上方建表脚本（qgents_schema.sql）已包含该列。
SET @team_avatar_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'teams' AND COLUMN_NAME = 'avatar_url'
);
SET @team_avatar_alter_sql = IF(@team_avatar_col_exists = 0,
    'ALTER TABLE teams ADD COLUMN avatar_url TEXT NULL COMMENT ''团队头像URL（OSS 公共读长期地址，可为空）'' AFTER description',
    'SELECT 1');
PREPARE team_avatar_alter_stmt FROM @team_avatar_alter_sql;
EXECUTE team_avatar_alter_stmt;
DEALLOCATE PREPARE team_avatar_alter_stmt;
