CREATE TABLE purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    supplier_name VARCHAR(120) NOT NULL,
    ordered_on DATE NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_order_supplier_no UNIQUE (supplier_name, order_no),
    INDEX ix_order_created (created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_order_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(120) NOT NULL,
    specification VARCHAR(120) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    ordered_qty INT NOT NULL CHECK (ordered_qty > 0),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    CONSTRAINT uq_order_line_no UNIQUE (order_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    delivery_note_no VARCHAR(64) NOT NULL,
    received_on DATE NOT NULL,
    confirmed_by VARCHAR(100) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_receipt_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    INDEX ix_receipt_order_confirmed (order_id, confirmed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE receipt_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    order_line_id BIGINT NOT NULL,
    delivered_qty INT CHECK (delivered_qty >= 0),
    accepted_qty INT NOT NULL CHECK (accepted_qty > 0),
    note VARCHAR(500),
    CONSTRAINT fk_receipt_line_receipt FOREIGN KEY (receipt_id) REFERENCES receipt (id),
    CONSTRAINT fk_receipt_line_order_line FOREIGN KEY (order_line_id) REFERENCES purchase_order_line (id),
    CONSTRAINT uq_receipt_order_line UNIQUE (receipt_id, order_line_id),
    INDEX ix_receipt_line_order_line (order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
