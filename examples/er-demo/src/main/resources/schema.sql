-- ER 示例库表结构：客户 / 商品 / 订单 / 订单明细，含两级外键关系。
-- 由应用启动时执行（spring.sql.init），因此全部使用 IF NOT EXISTS，可重复运行。

CREATE TABLE IF NOT EXISTS er_customer (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    email         VARCHAR(128),
    phone         VARCHAR(32),
    id_card       VARCHAR(32),
    credit_limit  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS er_product (
    id        BIGSERIAL PRIMARY KEY,
    sku       VARCHAR(32)  NOT NULL UNIQUE,
    name      VARCHAR(128) NOT NULL,
    price     NUMERIC(12, 2) NOT NULL,
    stock     INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS er_order (
    id           BIGSERIAL PRIMARY KEY,
    order_no     VARCHAR(40)  NOT NULL UNIQUE,
    customer_id  BIGINT       NOT NULL REFERENCES er_customer (id),
    status       VARCHAR(16)  NOT NULL,
    total_amount NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

-- 二级外键：订单明细同时指向订单与商品，用于验证关联查询与级联删除
CREATE TABLE IF NOT EXISTS er_order_item (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES er_order (id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES er_product (id),
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_er_order_customer ON er_order (customer_id);
CREATE INDEX IF NOT EXISTS idx_er_order_item_order ON er_order_item (order_id);
