CREATE TABLE delivery_file (
    order_id BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id BIGINT NOT NULL UNIQUE,
    PRIMARY KEY (order_id, sha256),
    FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    FOREIGN KEY (document_id) REFERENCES document (id)
) ENGINE=InnoDB;

INSERT INTO delivery_file (order_id, sha256, document_id)
SELECT order_id, sha256, id FROM (
    SELECT d.order_id, d.sha256, d.id, ROW_NUMBER() OVER (
        PARTITION BY d.order_id, d.sha256
        ORDER BY (r.receipt_id IS NOT NULL) DESC, (r.id IS NOT NULL) DESC, d.id
    ) AS rank_in_group
    FROM document d LEFT JOIN receipt_review r ON r.document_id=d.id
    WHERE d.purpose='DELIVERY_NOTE'
) ranked WHERE rank_in_group=1;

ALTER TABLE receipt
    MODIFY delivery_note_no VARCHAR(64) NULL,
    ADD COLUMN note_status VARCHAR(12) NOT NULL DEFAULT 'VERIFIED',
    ADD COLUMN unknown_note_reason VARCHAR(500),
    ADD COLUMN duplicate_review_reason VARCHAR(500),
    ADD COLUMN duplicate_candidates JSON,
    ADD COLUMN supplier_key VARBINARY(480),
    ADD COLUMN note_no_key VARBINARY(256),
    ADD INDEX ix_receipt_identity (supplier_key, note_no_key),
    ADD INDEX ix_receipt_supplier_day (supplier_key, received_on);

-- Uppercase English letters only. REPLACE uses a case-sensitive match.
UPDATE receipt r JOIN purchase_order o ON o.id=r.order_id
SET r.supplier_key=REGEXP_REPLACE(o.supplier_name, '^[[:space:]]+|[[:space:]]+$', ''),
    r.note_no_key=NULLIF(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REGEXP_REPLACE(r.delivery_note_no, '^[[:space:]]+|[[:space:]]+$', ''), 'a', 'A'), 'b', 'B'), 'c', 'C'), 'd', 'D'), 'e', 'E'), 'f', 'F'), 'g', 'G'), 'h', 'H'), 'i', 'I'), 'j', 'J'), 'k', 'K'), 'l', 'L'), 'm', 'M'), 'n', 'N'), 'o', 'O'), 'p', 'P'), 'q', 'Q'), 'r', 'R'), 's', 'S'), 't', 'T'), 'u', 'U'), 'v', 'V'), 'w', 'W'), 'x', 'X'), 'y', 'Y'), 'z', 'Z'), '');

UPDATE receipt SET note_status='UNKNOWN' WHERE note_no_key IS NULL;
ALTER TABLE receipt MODIFY supplier_key VARBINARY(480) NOT NULL;

CREATE TABLE receipt_identity (
    supplier_key VARBINARY(480) NOT NULL,
    note_no_key VARBINARY(256) NOT NULL,
    receipt_id BIGINT NOT NULL UNIQUE,
    PRIMARY KEY (supplier_key, note_no_key),
    FOREIGN KEY (receipt_id) REFERENCES receipt (id)
) ENGINE=InnoDB;

INSERT INTO receipt_identity (supplier_key, note_no_key, receipt_id)
SELECT supplier_key, note_no_key, MIN(id) FROM receipt WHERE note_no_key IS NOT NULL
GROUP BY supplier_key, note_no_key;

CREATE TABLE receipt_supplier_day (
    supplier_key VARBINARY(480) NOT NULL,
    received_on DATE NOT NULL,
    PRIMARY KEY (supplier_key, received_on)
) ENGINE=InnoDB;

CREATE TABLE receipt_command (
    operation VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id BIGINT NOT NULL,
    request_key VARBINARY(128) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NOT NULL,
    PRIMARY KEY (operation, resource_id, request_key)
) ENGINE=InnoDB;

CREATE TABLE receipt_adjustment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_line_id BIGINT NOT NULL,
    revision INT NOT NULL CHECK (revision > 0),
    before_qty INT NOT NULL CHECK (before_qty >= 0),
    after_qty INT NOT NULL CHECK (after_qty >= 0),
    reason VARCHAR(500) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (receipt_line_id, revision),
    FOREIGN KEY (receipt_line_id) REFERENCES receipt_line (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE VIEW effective_receipt_line AS
SELECT l.id, l.receipt_id, l.order_line_id, l.delivered_qty, l.note,
       l.accepted_qty AS original_accepted_qty,
       COALESCE(a.after_qty, l.accepted_qty) AS accepted_qty,
       COALESCE(a.revision, 0) AS revision
FROM receipt_line l LEFT JOIN receipt_adjustment a ON a.receipt_line_id=l.id AND a.revision=(
    SELECT MAX(previous.revision) FROM receipt_adjustment previous WHERE previous.receipt_line_id=l.id
);
