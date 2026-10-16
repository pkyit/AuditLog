-- 审计表建表脚本：Oracle
--
-- 适用：Oracle 12c+
-- 重要：本脚本**未在真实 Oracle 实例上验证**。提供它是为了让 Oracle 使用者
--       不必从源码反推表结构，但请在使用前自行核对。
--
-- 与 PostgreSQL 版本的差异（Oracle 的类型要求）：
--   * 大文本列用 CLOB（Oracle 无 TEXT 类型）
--   * 布尔列用 NUMBER(1)（Oracle SQL 在 23c 之前没有 BOOLEAN 类型）
--   * INT / BIGINT 用 NUMBER(n)（Oracle 无这两个类型名）
--   * TIMESTAMP 语义与 PostgreSQL 一致，可直接使用
--   * CREATE INDEX 不支持 IF NOT EXISTS，重复执行会报 ORA-00955

CREATE TABLE audit_log (
  id VARCHAR2(96) NOT NULL,
  seq NUMBER(19) NOT NULL,
  event_time TIMESTAMP NULL,
  received_time TIMESTAMP NULL,
  zone_id VARCHAR2(64) NULL,
  principal_id VARCHAR2(255) NULL,
  principal_name VARCHAR2(255) NULL,
  principal_type VARCHAR2(32) NULL,
  tenant_id VARCHAR2(128) NULL,
  session_id VARCHAR2(128) NULL,
  auth_type VARCHAR2(64) NULL,
  trace_id VARCHAR2(128) NULL,
  operation VARCHAR2(255) NULL,
  action_type VARCHAR2(32) NULL,
  resource_type VARCHAR2(128) NULL,
  resource_id VARCHAR2(255) NULL,
  client_ip VARCHAR2(64) NULL,
  user_agent VARCHAR2(512) NULL,
  http_method VARCHAR2(16) NULL,
  request_uri VARCHAR2(1024) NULL,
  query_string VARCHAR2(2048) NULL,
  service_name VARCHAR2(128) NULL,
  instance_id VARCHAR2(64) NULL,
  request_body CLOB NULL,
  response_body CLOB NULL,
  outcome VARCHAR2(16) NULL,
  http_status NUMBER(10) NULL,
  biz_code NUMBER(10) NULL,
  biz_message VARCHAR2(1024) NULL,
  error_type VARCHAR2(255) NULL,
  error_message VARCHAR2(2048) NULL,
  duration_millis NUMBER(19) NULL,
  transactional NUMBER(1) NULL,
  rolled_back NUMBER(1) NULL,
  prev_hash VARCHAR2(128) NULL,
  record_hash VARCHAR2(128) NULL,
  signature VARCHAR2(128) NULL,
  payload CLOB NOT NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_principal ON audit_log (principal_id, event_time);
CREATE INDEX idx_audit_log_action ON audit_log (action_type, event_time);
CREATE INDEX idx_audit_log_trace ON audit_log (trace_id);
CREATE INDEX idx_audit_log_seq ON audit_log (seq);
