-- V20260823_04: projects.avatar_url（项目头像）
-- 背景：项目群聊设置（主群/需求群）可上传项目头像，OSS 直传确认后写入 projects.avatar_url。
-- 幂等可重复执行；全新整库初始化时上方建表脚本（qgents_schema.sql）已包含该列。
SET @project_avatar_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'avatar_url'
);
SET @project_avatar_alter_sql = IF(@project_avatar_col_exists = 0,
    'ALTER TABLE projects ADD COLUMN avatar_url TEXT NULL COMMENT ''项目头像URL（OSS 公共读长期地址，可为空）'' AFTER description',
    'SELECT 1');
PREPARE project_avatar_alter_stmt FROM @project_avatar_alter_sql;
EXECUTE project_avatar_alter_stmt;
DEALLOCATE PREPARE project_avatar_alter_stmt;
