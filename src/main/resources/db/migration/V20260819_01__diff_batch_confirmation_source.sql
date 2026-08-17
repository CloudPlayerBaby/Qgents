-- MR_FIRST 自动交付：区分 DiffReviewBatch 的交付授权来源。
-- reviewStatus=ACCEPTED 统一表示「该批次已获准进入交付」；
-- confirmation_source 表达谁授权交付：USER=用户确认（DIFF_FIRST），SYSTEM=系统按 MR_FIRST 规则自动授权。
-- 存量已确认批次全部回填 USER，审计含义不丢失；服务端仅允许 MR_FIRST 内部流程写 SYSTEM，
-- 客户端 confirm 接口不接收该字段（固定写 USER），防止伪造。

ALTER TABLE diff_review_batches
    ADD COLUMN confirmation_source VARCHAR(16) NOT NULL DEFAULT 'USER'
    COMMENT '交付授权来源：USER=用户确认 / SYSTEM=MR_FIRST 系统自动授权'
    AFTER review_status;
