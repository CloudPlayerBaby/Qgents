-- V20260826_01: 用户×群置顶偏好表（个人偏好，跨设备同步）
-- 背景：群聊置顶从前端 localStorage 升级为后端按「用户×群」持久化，换设备/重新登录后保持。
-- 置顶只影响当前用户自己的群列表顺序，不做群公共属性。主键 (user_id, group_id)，
-- 重复设置同值幂等；群被删除/归档时该行可保留（前端按活跃群过滤），故不建外键。
-- 幂等可重复执行；全新整库初始化时上方建表脚本已包含本表。
CREATE TABLE IF NOT EXISTS user_group_preference (
    user_id BINARY(16) NOT NULL COMMENT '用户ID',
    group_id BINARY(16) NOT NULL COMMENT '需求群ID',
    pinned TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶（0/1）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (user_id, group_id),
    KEY idx_ugp_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户×群置顶偏好（跨设备同步）';
