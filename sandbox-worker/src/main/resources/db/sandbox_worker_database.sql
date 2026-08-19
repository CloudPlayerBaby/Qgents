-- 仅由部署管理员执行一次。应用账户只需获得 qgents_sandbox_worker 库内的 DDL/DML 权限，
-- 不应持有全局 CREATE DATABASE 权限。
CREATE DATABASE IF NOT EXISTS qgents_sandbox_worker
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
