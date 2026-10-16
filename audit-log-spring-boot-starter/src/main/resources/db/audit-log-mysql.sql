-- 审计表建表脚本：MySQL / MariaDB
--
-- 适用：MySQL 5.7+ / MariaDB 10.2+
-- 说明：MySQL 无 CLOB 类型，大文本列使用 TEXT。
--       MySQL 的 CREATE INDEX 不支持 IF NOT EXISTS（MariaDB 支持），
--       因此索引语句独立给出，重复执行会报"索引已存在"，可忽略或改用迁移工具管理。

CREATE TABLE IF NOT EXISTS audit_log (
  id VARCHAR(96) NOT NULL,
  seq BIGINT NOT NULL,
  event_time DATETIME NULL,
  received_time DATETIME NULL,
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
  transactional TINYINT(1) NULL,
  rolled_back TINYINT(1) NULL,
  prev_hash VARCHAR(128) NULL,
  record_hash VARCHAR(128) NULL,
  signature VARCHAR(128) NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_audit_log_principal ON audit_log (principal_id, event_time);
CREATE INDEX idx_audit_log_action ON audit_log (action_type, event_time);
CREATE INDEX idx_audit_log_trace ON audit_log (trace_id);
CREATE INDEX idx_audit_log_seq ON audit_log (seq);
