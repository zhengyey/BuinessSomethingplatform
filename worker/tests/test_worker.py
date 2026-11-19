import hashlib
import json
from pathlib import Path

import httpx
import pymupdf
import pytest

from labreview_worker.parsers import ParseError, parse
from labreview_worker.worker import Worker

SAMPLE_DIR = Path(__file__).resolve().parents[2] / "samples" / "phase-01"


def test_standard_order():
    expected = json.loads((SAMPLE_DIR / "expected.json").read_text(encoding="utf-8"))["purchaseOrder"]
    result = parse("ORDER_IMPORT", "application/pdf", (SAMPLE_DIR / expected.pop("file")).read_bytes())
    assert result["candidate"] == expected
    assert result["pages"][0]["status"] == "TEXT"
    assert len(result["sources"]) == 13
    assert all(len(source["bbox"]) == 4 and source["pageNo"] == 1 for source in result["sources"])


@pytest.mark.parametrize("specification", ["14 英寸", None])
def test_changed_columns_and_quantity_keep_missing_date_and_exclude_footer(specification):
    with pymupdf.open() as pdf:
        page = pdf.new_page()
        for y, words in [
            (75, [(36, "采购单号  PO-407"), (360, "供应商  测试供应商")]),
            (115, list(zip((48, 150, 260, 375, 455), ("物料编号", "物料名称", "规格", "单位", "采购数量")))),
            (154, list(zip((48, 150, 260, 375, 455), ("K-18", "测试耗材", specification, "盒", "17")))),
            (290, [(48, "请按采购数量和单位供货")]),
        ]:
            for x, word in words:
                if word is not None:
                    page.insert_text((x, y), word, fontname="china-s")
        payload = pdf.tobytes()
    result = parse("ORDER_IMPORT", "application/pdf", payload)
    assert result["candidate"] == {
        "orderNo": "PO-407", "supplierName": "测试供应商", "orderedOn": None,
        "lines": [{"itemCode": "K-18", "itemName": "测试耗材", "specification": specification,
                   "unit": "盒", "orderedQty": 17}],
    }
    assert any("采购日期" in hint for hint in result["hints"])
    assert any("规格" in hint for hint in result["hints"]) is (specification is None)


def test_delivery_image_and_mixed_pdf_when_ocr_unavailable(monkeypatch):
    from labreview_worker.parsers import delivery

    def unavailable():
        raise ImportError("PaddleOCR absent")

    monkeypatch.setattr(delivery, "paddle_engine", unavailable)
    image = (SAMPLE_DIR / "DN-001.jpg").read_bytes()
    result = parse("DELIVERY_NOTE", "image/jpeg", image)
    assert result["delivery"]["lines"] == []
    assert result["pages"][0]["status"] == "PAGE_FAILED"
    with pymupdf.open() as pdf:
        pdf.new_page().insert_image(pymupdf.Rect(0, 0, 100, 120), stream=image)
        scan = pdf.tobytes()
        with pytest.raises(ParseError) as error:
            parse("ORDER_IMPORT", "application/pdf", scan)
        assert error.value.code == "OCR_REQUIRED"
        with pymupdf.open(SAMPLE_DIR / "DN-002.pdf") as delivery:
            original_pages = len(delivery)
            pdf.insert_pdf(delivery)
        result = parse("DELIVERY_NOTE", "application/pdf", pdf.tobytes())
    assert len(result["pages"]) == original_pages + 1
    assert [page["pageNo"] for page in result["pages"]] == list(range(1, original_pages + 2))
    assert result["pages"][0]["status"] == "PAGE_FAILED"
    assert result["pages"][1]["status"] == "EXTRACTED"


def test_malformed_and_encrypted_pdf():
    with pytest.raises(ParseError) as error:
        parse("ORDER_IMPORT", "application/pdf", b"%PDF-broken")
    assert error.value.code == "INVALID_PDF"
    with pymupdf.open() as pdf:
        pdf.new_page()
        encrypted = pdf.tobytes(encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="secret")
    with pytest.raises(ParseError) as error:
        parse("ORDER_IMPORT", "application/pdf", encrypted)
    assert error.value.code == "ENCRYPTED_PDF"


@pytest.mark.parametrize("scenario", ["bad_hash", "download_timeout", "replay", "stale"])
def test_worker_protocol(scenario):
    binary = b"fixture"
    attempts = []

    def handle(request):
        assert request.headers["Authorization"] == "Bearer test-token"
        endpoint = request.url.path.rsplit("/", 1)[1]
        attempts.append((endpoint, request.content))
        if endpoint == "lease":
            assert json.loads(request.content) == {"workerId": "fixture-worker"}
            return httpx.Response(200, json=dict(
                taskId=7, documentId=10, purpose="DELIVERY_NOTE", mimeType="image/png",
                sha256=hashlib.sha256(binary).hexdigest() if scenario != "bad_hash" else "0" * 64,
                attemptNo=4, contentUrl="/internal/parse-tasks/7/content", leaseSeconds=30,
            ))
        if endpoint == "content":
            assert dict(request.url.params) == {"workerId": "fixture-worker", "attemptNo": "4"}
            if scenario == "download_timeout":
                raise httpx.ReadTimeout("lost download", request=request)
            if scenario == "stale":
                return httpx.Response(409)
            return httpx.Response(200, content=binary)
        callback = json.loads(request.content)
        assert callback["workerId"] == "fixture-worker"
        assert callback["attemptNo"] == 4
        if endpoint == "result":
            assert callback["protocolVersion"] == 1
            if sum(name == "result" for name, _ in attempts) == 1:
                raise httpx.ReadTimeout("lost callback response", request=request)
        return httpx.Response(204)

    def parser(purpose, mime_type, content):
        assert (purpose, mime_type, content) == ("DELIVERY_NOTE", "image/png", binary)
        return {"parserVersion": "test", "pages": [], "candidate": None, "sources": [], "hints": []}

    with httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(handle),
                      headers={"Authorization": "Bearer test-token"}) as api:
        assert Worker(api, "fixture-worker", parser=parser).run_once()
    callbacks = attempts[2:]
    if scenario == "replay":
        assert len(callbacks) == 2 and callbacks[0] == callbacks[1]
        assert callbacks[0][0] == "result"
    elif scenario == "stale":
        assert callbacks == []
    else:
        assert len(callbacks) == 1 and callbacks[0][0] == "failure"
        failure = json.loads(callbacks[0][1])
        assert failure["code"] == {"bad_hash": "HASH_MISMATCH", "download_timeout": "DOWNLOAD_FAILED"}[scenario]
        assert failure["retryable"] is (scenario == "download_timeout")


def test_heartbeat_while_parser_is_working():
    from threading import Event

    lease_renewed = Event()
    bytes_received = b"fixture"
    callbacks = []

    def handle(request):
        endpoint = request.url.path.rsplit("/", 1)[1]
        if endpoint == "lease":
            return httpx.Response(200, json={
                "taskId": 7, "documentId": 10, "purpose": "DELIVERY_NOTE", "mimeType": "image/png",
                "sha256": hashlib.sha256(bytes_received).hexdigest(), "attemptNo": 4,
                "contentUrl": "/internal/parse-tasks/7/content", "leaseSeconds": 0.15,
            })
        if endpoint == "content":
            return httpx.Response(200, content=bytes_received)
        callbacks.append((endpoint, json.loads(request.content)))
        if endpoint == "heartbeat":
            lease_renewed.set()
        return httpx.Response(204)

    def parser(*_):
        assert lease_renewed.wait(2)
        return {"parserVersion": "test", "pages": [], "candidate": None, "sources": [], "hints": []}

    with httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(handle)) as api:
        assert Worker(api, "fixture-worker", parser=parser).run_once()
    assert callbacks[0] == ("heartbeat", {"workerId": "fixture-worker", "attemptNo": 4})
    assert callbacks[-1][0] == "result"


def test_rejects_pdf_with_more_than_fifty_pages():
    with pymupdf.open() as pdf:
        for _ in range(51):
            pdf.new_page()
        content = pdf.tobytes()
    with pytest.raises(ParseError) as error:
        parse("DELIVERY_NOTE", "application/pdf", content)
    assert error.value.code == "TOO_MANY_PAGES"


def test_heartbeats_during_lost_result_response_then_replays_result():
    from threading import Event

    heartbeat_during_result = Event()
    result_started = Event()
    attempts = []
    content = b"fixture"

    def handle(request):
        endpoint = request.url.path.rsplit("/", 1)[1]
        if endpoint == "lease":
            return httpx.Response(200, json={
                "taskId": 7, "documentId": 10, "purpose": "DELIVERY_NOTE", "mimeType": "image/png",
                "sha256": hashlib.sha256(content).hexdigest(), "attemptNo": 4,
                "contentUrl": "/internal/parse-tasks/7/content", "leaseSeconds": 0.15,
            })
        if endpoint == "content":
            return httpx.Response(200, content=content)
        if endpoint == "heartbeat":
            if result_started.is_set():
                heartbeat_during_result.set()
                return httpx.Response(409)
            return httpx.Response(204)
        assert endpoint == "result"
        attempts.append(request.content)
        if len(attempts) == 1:
            result_started.set()
            assert heartbeat_during_result.wait(2)
            raise httpx.ReadTimeout("accepted result response was lost", request=request)
        return httpx.Response(204)

    def parser(*_):
        return {"parserVersion": "test", "pages": [], "candidate": None, "sources": [], "hints": []}

    with httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(handle)) as api:
        assert Worker(api, "fixture-worker", parser=parser).run_once()
    assert len(attempts) == 2 and attempts[0] == attempts[1]


def test_truncated_jpeg_is_rejected_after_header_identification():
    raw = (SAMPLE_DIR / "DN-001.jpg").read_bytes()
    with pytest.raises(ParseError) as error:
        parse("DELIVERY_NOTE", "image/jpeg", raw[: len(raw) // 2])
    assert error.value.code == "INVALID_IMAGE"
