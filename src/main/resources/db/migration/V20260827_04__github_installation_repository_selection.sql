-- Persist the GitHub App installation repository selection so personal repository
-- creation can be blocked before creating a remote repository when access is SELECTED.
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'github_installations'
      AND column_name = 'repository_selection'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE github_installations ADD COLUMN repository_selection VARCHAR(16) NULL COMMENT ''GitHub App 仓库访问范围：ALL/SELECTED'' AFTER account_type',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
