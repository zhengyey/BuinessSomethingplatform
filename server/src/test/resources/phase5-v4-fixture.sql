-- Run once against a disposable schema at Flyway V4.
INSERT INTO purchase_order (id, order_no, supplier_name, ordered_on, created_by) VALUES
    (91001, 'P5-LEGACY-A', '  阶段五迁移样本  ', '2026-11-17', 'migration-fixture'),
    (91002, 'P5-LEGACY-B', '阶段五迁移样本', '2026-11-17', 'migration-fixture');

INSERT INTO purchase_order_line (id, order_id, line_no, item_code, item_name, specification, unit, ordered_qty) VALUES
    (91101, 91001, 1, 'G-01', '丁腈手套', 'M 码', '箱', 10),
    (91102, 91002, 1, 'G-01', '丁腈手套', 'M 码', '箱', 10);

INSERT INTO receipt (id, order_id, delivery_note_no, received_on, confirmed_by) VALUES
    (91201, 91001, '  dn-Legacy-01  ', '2026-11-18', 'migration-fixture'),
    (91202, 91002, 'DN-LEGACY-01', '2026-11-18', 'migration-fixture'),
    (91203, 91001, 'DN-LEGACY-02', '2026-11-18', 'migration-fixture');

INSERT INTO receipt_line (id, receipt_id, order_line_id, delivered_qty, accepted_qty) VALUES
    (91301, 91201, 91101, 6, 5), (91302, 91202, 91102, 5, 5), (91303, 91203, 91101, 3, 3);

INSERT INTO document (id, purpose, order_id, uploaded_by, original_filename, mime_type, byte_size, sha256, storage_key) VALUES
    (91401, 'DELIVERY_NOTE', 91001, 'migration-fixture', 'legacy-first.pdf', 'application/pdf', 12, REPEAT('a', 64), 'legacy-first'),
    (91402, 'DELIVERY_NOTE', 91001, 'migration-fixture', 'legacy-posted.pdf', 'application/pdf', 12, REPEAT('a', 64), 'legacy-posted');

INSERT INTO parse_task (id, document_id, task_type, status, result_hash) VALUES
    (91501, 91401, 'DELIVERY_NOTE', 'SUCCEEDED', REPEAT('b', 64)),
    (91502, 91402, 'DELIVERY_NOTE', 'SUCCEEDED', REPEAT('b', 64));

INSERT INTO receipt_review (id, document_id, task_id, order_id, result_hash, status, review_json, snapshot_json,
    updated_by, confirmed_by, confirmed_at, receipt_id)
VALUES (91602, 91402, 91502, 91001, REPEAT('b', 64), 'POSTED', '{}', '{}',
    'migration-fixture', 'migration-fixture', CURRENT_TIMESTAMP(6), 91201);
