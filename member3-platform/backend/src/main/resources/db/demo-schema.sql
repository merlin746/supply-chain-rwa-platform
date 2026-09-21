DROP TABLE IF EXISTS blockchain_event;
DROP TABLE IF EXISTS loan_application;
DROP TABLE IF EXISTS rwa_token;
DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS enterprise;
DROP TABLE IF EXISTS sys_user;

CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(64),
    role VARCHAR(32) NOT NULL,
    enterprise_id BIGINT,
    wallet_address VARCHAR(128),
    status TINYINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE enterprise (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    enterprise_code VARCHAR(64) UNIQUE,
    enterprise_type VARCHAR(32),
    credit_limit DECIMAL(20,2) DEFAULT 0,
    credit_used DECIMAL(20,2) DEFAULT 0,
    wallet_address VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_code VARCHAR(64) NOT NULL UNIQUE,
    invoice_number VARCHAR(64),
    core_enterprise_id BIGINT,
    supplier_id BIGINT,
    amount DECIMAL(20,2) NOT NULL,
    issue_date DATE,
    due_date DATE,
    status VARCHAR(32) DEFAULT 'PENDING',
    file_hash VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rwa_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_id VARCHAR(128) NOT NULL UNIQUE,
    slot_id VARCHAR(128),
    parent_token_id VARCHAR(128),
    owner_address VARCHAR(128),
    enterprise_id BIGINT,
    invoice_id BIGINT,
    value_amount DECIMAL(20,2),
    due_timestamp BIGINT,
    status VARCHAR(32) DEFAULT 'UNCIRCULATED',
    contract_address VARCHAR(128),
    tx_hash VARCHAR(128),
    block_number BIGINT,
    metadata_json LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rwa_owner ON rwa_token(owner_address);
CREATE INDEX idx_rwa_enterprise ON rwa_token(enterprise_id);
CREATE INDEX idx_rwa_status ON rwa_token(status);
CREATE INDEX idx_rwa_parent ON rwa_token(parent_token_id);

CREATE TABLE loan_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE blockchain_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tx_hash VARCHAR(128) NOT NULL,
    log_index BIGINT,
    block_number BIGINT,
    contract_address VARCHAR(128),
    topic0 VARCHAR(128),
    event_name VARCHAR(128),
    raw_topics LONGTEXT,
    raw_data LONGTEXT,
    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tx_logindex UNIQUE(tx_hash, log_index)
);
