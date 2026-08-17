-- 交付模式自动判定：delivery_mode 可空（未显式指定/未判定时为空，由 Plan 物化时定型），
-- 并新增 delivery_reason 记录判定理由（Planner scaleReason 或硬规则依据），供看板/卡片展示。
ALTER TABLE tasks
    MODIFY COLUMN delivery_mode VARCHAR(32) NULL COMMENT '交付模式：DIFF_FIRST/MR_FIRST；为空时由 Plan 物化自动判定';

ALTER TABLE tasks
    ADD COLUMN delivery_reason VARCHAR(512) NULL COMMENT '交付模式判定理由（Planner scaleReason 或规则依据）' AFTER delivery_mode;