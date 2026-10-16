-- 固定上游 mall dcaa93b3 的 PMS/OMS 游戏适配；审核后显式执行，全新 v1 安装专用。
-- 不执行 DROP，不导入账号/样例数据，不在应用启动时自动建表。
-- 本版本库名必须等于 game.namespace，不与另一版商城/实物电商数据库混用。
CREATE TABLE IF NOT EXISTS commerce_schema_version (
  version INT PRIMARY KEY,
  storage_contract VARCHAR(80) NOT NULL
);
CREATE TABLE IF NOT EXISTS commerce_buyer_guard (buyer_id VARCHAR(20) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS pms_sku_stock (
  id BIGINT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  sku_code VARCHAR(64) NOT NULL,
  price_minor BIGINT NOT NULL,
  currency VARCHAR(16) NOT NULL,
  stock INT NOT NULL,
  lock_stock INT NOT NULL DEFAULT 0,
  published INT NOT NULL DEFAULT 0,
  CHECK (price_minor > 0), CHECK (stock >= 0), CHECK (lock_stock >= 0 AND lock_stock <= stock)
);
CREATE TABLE IF NOT EXISTS oms_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_sn VARCHAR(36) NOT NULL,
  buyer_id VARCHAR(20) NOT NULL,
  request_id VARBINARY(128) NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  pay_amount BIGINT NOT NULL,
  currency VARCHAR(16) NOT NULL,
  status INT NOT NULL,
  create_time_ms BIGINT NOT NULL,
  payment_deadline_ms BIGINT NOT NULL,
  payment_time_ms BIGINT NULL,
  CONSTRAINT uk_mall_order_sn UNIQUE (order_sn),
  CONSTRAINT uk_mall_order_request UNIQUE (buyer_id,request_id),
  INDEX idx_commerce_buyer_status (buyer_id,status),
  CHECK (quantity > 0), CHECK (pay_amount > 0)
);
CREATE TABLE IF NOT EXISTS oms_order_item (
  order_sn VARCHAR(36) PRIMARY KEY,
  product_id BIGINT NOT NULL,
  product_sku_id BIGINT NOT NULL,
  product_sku_code VARCHAR(64) NOT NULL,
  product_quantity INT NOT NULL,
  product_price_minor BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS commerce_payment_receipt (
  channel VARBINARY(32) NOT NULL,
  transaction_id VARBINARY(128) NOT NULL,
  order_sn VARCHAR(36) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency VARCHAR(16) NOT NULL,
  PRIMARY KEY (channel,transaction_id),
  CONSTRAINT uk_mall_payment_order UNIQUE (order_sn)
);
CREATE TABLE IF NOT EXISTS commerce_delivery_intent (
  order_sn VARCHAR(36) PRIMARY KEY,
  buyer_id VARCHAR(20) NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_ms BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS commerce_request_nonce (
  caller_role VARCHAR(16) NOT NULL,
  nonce VARBINARY(128) NOT NULL,
  expires_ms BIGINT NOT NULL,
  PRIMARY KEY (caller_role,nonce),
  INDEX idx_commerce_nonce_expiry (expires_ms)
);
-- 仅在全部DDL成功后登记版本；重复安装必须显式检查已有版本，不覆盖旧事实。
INSERT INTO commerce_schema_version(version,storage_contract) VALUES (1,'mall-game-official-sku-v1-minor-units');
