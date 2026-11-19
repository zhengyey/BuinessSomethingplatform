CREATE TABLE receipt_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL UNIQUE,
    task_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    review_json JSON NOT NULL,
    snapshot_json JSON,
    updated_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmed_by VARCHAR(100),
    confirmed_at TIMESTAMP(6) NULL,
    receipt_id BIGINT UNIQUE,
    CONSTRAINT fk_review_document FOREIGN KEY (document_id) REFERENCES document (id),
    CONSTRAINT fk_review_task FOREIGN KEY (task_id) REFERENCES parse_task (id),
    CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_review_receipt FOREIGN KEY (receipt_id) REFERENCES receipt (id),
    CONSTRAINT ck_review_status CHECK (
        (status = 'DRAFT' AND receipt_id IS NULL AND snapshot_json IS NULL
            AND confirmed_by IS NULL AND confirmed_at IS NULL)
        OR (status IN ('POSTED', 'CLOSED_NO_RECEIPT') AND snapshot_json IS NOT NULL
            AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL
            AND ((status = 'POSTED' AND receipt_id IS NOT NULL)
                OR (status = 'CLOSED_NO_RECEIPT' AND receipt_id IS NULL)))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
