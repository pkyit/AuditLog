-- 示例表：用于验证「事务回滚时审计不得记录为成功」
CREATE TABLE IF NOT EXISTS demo_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL
);
