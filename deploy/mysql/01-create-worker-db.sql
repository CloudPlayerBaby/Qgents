CREATE DATABASE IF NOT EXISTS qgents_sandbox_worker
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON qgents_sandbox_worker.* TO 'qgents'@'%';
FLUSH PRIVILEGES;
