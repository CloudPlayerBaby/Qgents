-- V20260818_02: users.avatar_object_key（用户头像对象键）
-- 背景：头像上传复用 OSS 直传，采用「每用户单当前头像」模型。为在替换头像时定位并删除旧 OSS 对象，
-- 在 users 增加 avatar_object_key 记录当前头像对象键；avatar_url 保持为公共读长期 URL。
-- 幂等可重复执行；全新整库初始化时上方建表脚本（qgents_schema.sql）已包含该列。
SET @avatar_key_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'avatar_object_key'
);
SET @avatar_key_alter_sql = IF(@avatar_key_col_exists = 0,
    'ALTER TABLE users ADD COLUMN avatar_object_key VARCHAR(512) NULL COMMENT ''当前头像对象键（OSS avatars/{userId}/...）'' AFTER avatar_url',
    'SELECT 1');
PREPARE avatar_key_alter_stmt FROM @avatar_key_alter_sql;
EXECUTE avatar_key_alter_stmt;
DEALLOCATE PREPARE avatar_key_alter_stmt;
