-- 审计表建表脚本：PostgreSQL
--
-- 适用：PostgreSQL 9.4+
-- 说明：框架的自动建表（audit.log.store.auto-create-table=true）会执行等价 DDL，
--       但生产环境通常不授予应用 DDL 权限，应由迁移脚本管理表结构。
--       PostgreSQL 的大文本列使用 TEXT（无 CLOB 类型）。
--
-- 表名默认 audit_log，如使用其他表名请整体替换。

CREATE TABLE IF NOT EXISTS audit_log (
  id VARCHAR(96) NOT NULL,
  seq BIGINT NOT NULL,
  event_time TIMESTAMP NULL,
  received_time TIMESTAMP NULL,
  zone_id VARCHAR(64) NULL,
  principal_id VARCHAR(255) NULL,
  principal_name VARCHAR(255) NULL,
  principal_type VARCHAR(32) NULL,
  tenant_id VARCHAR(128) NULL,
  session_id VARCHAR(128) NULL,
  auth_type VARCHAR(64) NULL,
  trace_id VARCHAR(128) NULL,
  operation VARCHAR(255) NULL,
  action_type VARCHAR(32) NULL,
  resource_type VARCHAR(128) NULL,
  resource_id VARCHAR(255) NULL,
  client_ip VARCHAR(64) NULL,
  user_agent VARCHAR(512) NULL,
  http_method VARCHAR(16) NULL,
  request_uri VARCHAR(1024) NULL,
  query_string VARCHAR(2048) NULL,
  service_name VARCHAR(128) NULL,
  instance_id VARCHAR(64) NULL,
  request_body TEXT NULL,
  response_body TEXT NULL,
  outcome VARCHAR(16) NULL,
  http_status INT NULL,
  biz_code INT NULL,
  biz_message VARCHAR(1024) NULL,
  error_type VARCHAR(255) NULL,
  error_message VARCHAR(2048) NULL,
  duration_millis BIGINT NULL,
  transactional BOOLEAN NULL,
  rolled_back BOOLEAN NULL,
  prev_hash VARCHAR(128) NULL,
  record_hash VARCHAR(128) NULL,
  signature VARCHAR(128) NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (id)
);

-- 常用审计查询维度
CREATE INDEX IF NOT EXISTS idx_audit_log_principal ON audit_log (principal_id, event_time);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action_type, event_time);
CREATE INDEX IF NOT EXISTS idx_audit_log_trace ON audit_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_seq ON audit_log (seq);
