import pymupdf

from .bounds import fit_text
from .purchase import purchase_candidate

MAX_PDF_PAGES = 50
MAX_PAGE_TEXT = 100_000
PARSER_VERSION = "pymupdf-template-1"
OCR_HINT = "该页没有可用文本层，待 OCR。"


class ParseError(Exception):
    def __init__(self, code, message, retryable=False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


def page_result(page_no, text):
    if not text:
        return {"pageNo": page_no, "status": "NEEDS_OCR", "text": "", "hints": [OCR_HINT]}
    truncated = fit_text(text, MAX_PAGE_TEXT)
    hints = ["该页内容过长，已截取部分文本。"] if truncated != text else []
    return {"pageNo": page_no, "status": "TEXT", "text": truncated, "hints": hints}


def readable_text(page):
    text = page.get_text(sort=True).strip()
    return text if any(character.isalnum() for character in text) else ""


def parse_pdf(content):
    try:
        with pymupdf.open(stream=content, filetype="pdf") as pdf:
            if pdf.is_encrypted or pdf.needs_pass:
                raise ParseError("ENCRYPTED_PDF", "PDF 已加密，请上传可直接打开的原件。")
            if not pdf.is_pdf or not pdf.page_count:
                raise ParseError("INVALID_PDF", "PDF 损坏或没有可读页面，请重新上传原件。")
            if pdf.page_count > MAX_PDF_PAGES:
                raise ParseError("TOO_MANY_PAGES", f"PDF 超过 {MAX_PDF_PAGES} 页，请拆分后上传。")
            pages = [page_result(page_no, readable_text(page)) for page_no, page in enumerate(pdf, 1)]
            if not any(page["status"] == "TEXT" for page in pages):
                raise ParseError("OCR_REQUIRED", "当前导入支持文字型采购单，请使用手工建单。")
            candidate, sources, hints = purchase_candidate(pdf)
            return pages, candidate, sources, hints
    except (pymupdf.FileDataError, RuntimeError, ValueError, TypeError) as exc:
        raise ParseError("INVALID_PDF", "PDF 损坏或无法读取，请重新上传原件。") from exc


def parse(purpose, mime_type, content):
    if purpose == "DELIVERY_NOTE":
        from .delivery import parse_delivery

        return parse_delivery(mime_type, content)
    if mime_type == "application/pdf" and purpose == "ORDER_IMPORT":
        pages, candidate, sources, hints = parse_pdf(content)
        return dict(parserVersion=PARSER_VERSION, pages=pages, candidate=candidate, sources=sources, hints=hints)
    raise ParseError("UNSUPPORTED_FORMAT", "无法处理此类文件，请核对原件格式。")
