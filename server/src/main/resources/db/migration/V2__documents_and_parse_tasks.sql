CREATE TABLE document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purpose VARCHAR(24) NOT NULL,
    order_id BIGINT NULL,
    uploaded_by VARCHAR(100) NOT NULL,
    uploaded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT uq_document_storage UNIQUE (storage_key),
    CONSTRAINT fk_document_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    CONSTRAINT ck_document_purpose CHECK (
        (purpose = 'ORDER_IMPORT' AND order_id IS NULL)
        OR (purpose = 'DELIVERY_NOTE' AND order_id IS NOT NULL)
    ),
    INDEX ix_document_order_uploaded (order_id, uploaded_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE parse_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    task_type VARCHAR(24) NOT NULL CHECK (task_type IN ('ORDER_IMPORT', 'DELIVERY_NOTE')),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt_no BIGINT NOT NULL DEFAULT 0 CHECK (attempt_no >= 0),
    cycle_attempts INT NOT NULL DEFAULT 0 CHECK (cycle_attempts >= 0),
    worker_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin,
    lease_until TIMESTAMP(6),
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    failure_code VARCHAR(64),
    failure_message VARCHAR(1000),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    parser_version VARCHAR(80),
    result_json JSON,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    CONSTRAINT uq_parse_task_document UNIQUE (document_id),
    CONSTRAINT fk_parse_task_document FOREIGN KEY (document_id) REFERENCES document (id),
    INDEX ix_parse_task_queue (status, available_at, id),
    INDEX ix_parse_task_lease (status, lease_until, id),
    INDEX ix_parse_task_list (created_at DESC, id DESC),
    INDEX ix_parse_task_type_list (task_type, status, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE parse_page (
    task_id BIGINT NOT NULL,
    page_no INT NOT NULL CHECK (page_no >= 1),
    status VARCHAR(16) NOT NULL CHECK (status IN ('TEXT', 'NEEDS_OCR')),
    extracted_text MEDIUMTEXT,
    hints_json JSON NOT NULL,
    PRIMARY KEY (task_id, page_no),
    CONSTRAINT fk_parse_page_task FOREIGN KEY (task_id) REFERENCES parse_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_import_confirmation (
    document_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    confirmed_by VARCHAR(100) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_import_confirmation_document FOREIGN KEY (document_id) REFERENCES document (id),
    CONSTRAINT fk_import_confirmation_order FOREIGN KEY (order_id) REFERENCES purchase_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
