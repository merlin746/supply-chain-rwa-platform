CREATE DATABASE IF NOT EXISTS rwa_supply_chain
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE rwa_supply_chain;

DROP TABLE IF EXISTS blockchain_event;
DROP TABLE IF EXISTS loan_application;
DROP TABLE IF EXISTS rwa_token;
DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS enterprise;
DROP TABLE IF EXISTS sys_user;

CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(64),
    role VARCHAR(32) NOT NULL COMMENT 'CORE_ENTERPRISE/SUPPLIER/BANK/ADMIN',
    enterprise_id BIGINT,
    wallet_address VARCHAR(128),
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE enterprise (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    enterprise_code VARCHAR(64) UNIQUE,
    enterprise_type VARCHAR(32),
    credit_limit DECIMAL(20,2) DEFAULT 0,
    credit_used DECIMAL(20,2) DEFAULT 0,
    wallet_address VARCHAR(128),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE invoice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    invoice_code VARCHAR(64) NOT NULL UNIQUE,
    invoice_number VARCHAR(64),
    core_enterprise_id BIGINT,
    supplier_id BIGINT,
    amount DECIMAL(20,2) NOT NULL,
    issue_date DATE,
    due_date DATE,
    status VARCHAR(32) DEFAULT 'PENDING',
    file_hash VARCHAR(128),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE rwa_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_id VARCHAR(128) NOT NULL UNIQUE,
    slot_id VARCHAR(128),
    parent_token_id VARCHAR(128) COMMENT '拆分来源凭证，根凭证为NULL',
    owner_address VARCHAR(128),
    enterprise_id BIGINT,
    invoice_id BIGINT,
    value_amount DECIMAL(20,2),
    due_timestamp BIGINT,
    status VARCHAR(32) DEFAULT 'UNCIRCULATED',
    contract_address VARCHAR(128),
    tx_hash VARCHAR(128),
    block_number BIGINT,
    metadata_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_owner(owner_address),
    INDEX idx_enterprise(enterprise_id),
    INDEX idx_status(status),
    INDEX idx_parent(parent_token_id)
) ENGINE=InnoDB;

CREATE TABLE loan_application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    application_no VARCHAR(64) NOT NULL UNIQUE,
    token_id VARCHAR(128),
    supplier_id BIGINT,
    bank_id BIGINT,
    amount DECIMAL(20,2) NOT NULL,
    interest_rate DECIMAL(8,4),
    term_days INT,
    status VARCHAR(32) DEFAULT 'APPLIED',
    chain_tx_hash VARCHAR(128),
    remark VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE blockchain_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tx_hash VARCHAR(128) NOT NULL,
    log_index BIGINT COMMENT '交易内日志序号，与tx_hash联合去重（同交易可有多个同topic0事件）',
    block_number BIGINT,
    contract_address VARCHAR(128),
    topic0 VARCHAR(128),
    event_name VARCHAR(128),
    raw_topics JSON,
    raw_data LONGTEXT,
    synced_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tx_logindex(tx_hash, log_index)
) ENGINE=InnoDB;

INSERT INTO enterprise(name, enterprise_code, enterprise_type, credit_limit, credit_used, wallet_address)
VALUES
('比亚迪', 'BYD', 'CORE_ENTERPRISE', 1000000000, 0, '0xBYD00000000000000000000000000000000001'),
('科达利', 'KDL', 'SUPPLIER', 200000000, 0, '0xKDL00000000000000000000000000000000002'),
('聚能永拓', 'JNYT', 'SUPPLIER', 100000000, 0, '0xJNYT0000000000000000000000000000000003'),
('长园特发', 'CYTF', 'SUPPLIER', 100000000, 0, '0xCYTF0000000000000000000000000000000004'),
('建设银行', 'CCB', 'BANK', 5000000000, 0, '0xCCB0000000000000000000000000000000005');

INSERT INTO sys_user(username,password,real_name,role,enterprise_id,wallet_address)
VALUES
('byd','123456','比亚迪管理员','CORE_ENTERPRISE',1,'0xBYD00000000000000000000000000000000001'),
('kdl','123456','科达利用户','SUPPLIER',2,'0xKDL00000000000000000000000000000000002'),
('jnyt','123456','聚能永拓用户','SUPPLIER',3,'0xJNYT0000000000000000000000000000000003'),
('cytf','123456','长园特发用户','SUPPLIER',4,'0xCYTF0000000000000000000000000000000004'),
('ccb','123456','建设银行用户','BANK',5,'0xCCB0000000000000000000000000000000005');

-- ==================== 演示种子数据（链下模式） ====================

-- 一张已开立凭证的发票 + 一张待审核发票（用于演示审核流程）
INSERT INTO invoice(invoice_code, invoice_number, core_enterprise_id, supplier_id, amount, issue_date, due_date, status, file_hash)
VALUES
('INV-2026-0001', 'NO-88001', 1, 2, 2000000.00, '2026-08-01', '2026-12-31', 'MINTED', 'OFFCHAIN-FILEHASH-0001'),
('INV-2026-0002', 'NO-88002', 1, 3, 800000.00, '2026-08-15', '2027-03-31', 'PENDING', 'OFFCHAIN-FILEHASH-0002');

-- 根凭证：比亚迪确权开立给科达利（到期日 2026-12-31，due_timestamp=1798646400）
INSERT INTO rwa_token(token_id, slot_id, parent_token_id, owner_address, enterprise_id, invoice_id,
                      value_amount, due_timestamp, status, contract_address, tx_hash, metadata_json)
VALUES
('RWA-DEMO-ROOT-001', 'SLOT-20261231', NULL,
 '0xKDL00000000000000000000000000000000002', 2, 1,
 2000000.00, 1798646400, 'UNCIRCULATED',
 '0x0000000000000000000000000000000000000000', 'OFFCHAIN-DEMO-MINT-0001',
 '{"coreEnterprise":"比亚迪","supplier":"科达利","invoiceCode":"INV-2026-0001","creditSignature":"OFFCHAIN-SIG-BYD","mode":"OFFCHAIN_DB"}');
