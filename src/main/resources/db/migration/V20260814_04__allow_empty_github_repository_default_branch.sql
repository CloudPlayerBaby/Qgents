-- GitHub returns default_branch = null for repositories without an initial commit.
-- Keep the installation synchronized; project binding already rejects repositories with no default branch.
-- This migration is safe to run repeatedly against an existing database.

SET @default_branch_is_not_nullable = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'github_repositories'
      AND COLUMN_NAME = 'default_branch'
      AND IS_NULLABLE = 'NO'
);

SET @allow_empty_repository_default_branch = IF(
    @default_branch_is_not_nullable > 0,
    'ALTER TABLE github_repositories MODIFY COLUMN default_branch VARCHAR(512) NULL COMMENT ''GitHub default branch; NULL when the repository has no initial commit''',
    'SELECT 1'
);

PREPARE allow_empty_repository_default_branch_stmt FROM @allow_empty_repository_default_branch;
EXECUTE allow_empty_repository_default_branch_stmt;
DEALLOCATE PREPARE allow_empty_repository_default_branch_stmt;
