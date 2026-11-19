ALTER TABLE parse_page
    DROP CHECK parse_page_chk_2,
    ADD CONSTRAINT ck_parse_page_status
        CHECK (status IN ('TEXT', 'NEEDS_OCR', 'EXTRACTED', 'REVIEW', 'PAGE_FAILED'));
