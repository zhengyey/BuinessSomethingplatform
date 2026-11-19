import hashlib
import json
from pathlib import Path

import httpx
from PIL import Image
import pymupdf
import pytest

from labreview_worker.parsers import parse
from labreview_worker.parsers import delivery
from labreview_worker.parsers.delivery_layout import Fragment, extract, labeled_quantities
from labreview_worker.worker import Worker


SAMPLES = Path(__file__).resolve().parents[2] / "samples"


@pytest.mark.parametrize("text, expected", [
    ("手写 实 收 数 量 5", [("acceptedQty", 5)]),
    ("送 货 6；实收 5，短 1", [("deliveredQty", 6), ("acceptedQty", 5)]),
    ("实收 5 + 2", []),
    ("实收 5 / 2", []),
    ("实收 5 - 7", []),
    ("实收 5 至 7", []),
    ("实收 5. 2", []),
    ("实收 5，2", []),
    ("实收 5 百", []),
    ("实收 5 亿", []),
    ("预计 实收 5", []),
    ("未实收 5", []),
    ("计划 送货 5，实收 2", [("acceptedQty", 2)]),
])
def test_literal_quantity_evidence(text, expected):
    assert list(labeled_quantities(text)) == expected


def test_delivery_text_layer_supplies_quantities_with_labeled_region_evidence():
    result = parse("DELIVERY_NOTE", "application/pdf", (SAMPLES / "phase-01" / "DN-002.pdf").read_bytes())
    assert result["protocolVersion"] == 2
    assert [(line["itemCode"], line["deliveredQty"], line["acceptedQty"])
            for line in result["delivery"]["lines"]] == [("G-01", 5, 5), ("F-02", 3, 3)]
    page = result["pages"][0]
    assert page["status"] == "EXTRACTED"
    assert page["methods"] == ["PDF_TEXT"]
    assert page["ocrCalls"] == page["doubaoCalls"] == 0
    accepted = [entry for entry in result["delivery"]["evidence"] if entry["field"] == "acceptedQty"]
    assert [entry["rawText"] for entry in accepted] == ["实收数量 5", "实收数量 3"]
    assert all(entry["supporting"] and entry["precision"] == "REGION"
               and all(0 <= coordinate <= 1 for coordinate in entry["bbox"]) for entry in accepted)


def test_unlabeled_difference_cannot_be_used_as_receipt_evidence():
    with pymupdf.open() as pdf:
        page = pdf.new_page()
        for y, words in [
            (100, ["物料编码", "物料名称", "规格", "单位", "送货数量", "实收数量"]),
            (130, ["G-01", "丁腈手套", "M 码", "箱", "6", ""]),
        ]:
            for x, word in zip((44, 118, 227, 327, 383, 473), words):
                if word:
                    page.insert_text((x, y), word, fontname="china-s")
        content = pdf.tobytes()
    result = parse("DELIVERY_NOTE", "application/pdf", content)
    line, = result["delivery"]["lines"]
    assert line["deliveredQty"] == 6 and line["acceptedQty"] is None
    assert "MISSING_ACCEPTED_EVIDENCE" in line["issues"]
    assert result["pages"][0]["doubaoCalls"] == 0


def mock_conflicting_ocr(*_):
    with pymupdf.open(SAMPLES / "phase-01" / "DN-002.pdf") as pdf:
        # Construct a one-line OCR example with separate accepted corrections.
        table = [entry for entry in delivery.pdf_fragments(pdf[0]) if .35 < entry.y < .49]
    return table + [Fragment("手写实收 6 箱", (.56, .59, .75, .62))]


@pytest.mark.parametrize("kind", ["corroborated", "unsupported", "contradictory", "wrong_page", "timeout",
                                  "invalid_json"])
def test_model_routes_and_conflict_stays_unresolved(monkeypatch, kind):
    actual_crop = delivery.cropped_image
    bounds = []

    def record_crop(image, regions):
        cropped, crop = actual_crop(image, regions)
        bounds[:] = crop
        return cropped, crop

    class FixedModel:
        def read(self, image, page_no, lines):
            assert image.width < 1489 and image.height < 1049
            if kind == "timeout":
                raise TimeoutError()
            if kind == "invalid_json":
                raise json.JSONDecodeError("", "", 0)
            x0, y0, x1, y1 = bounds
            return {"suggestions": [{
                "lineKey": lines[0]["lineKey"],
                "pageNo": page_no + 1 if kind == "wrong_page" else page_no,
                "field": "acceptedQty",
                "rawText": {"unsupported": "实收 8 箱", "contradictory": "实收 6 箱；实收 8 箱"}.get(kind, "实收 6 箱"),
                "value": 8 if kind == "unsupported" else 6,
                "bbox": [(.56 - x0) / (x1 - x0), (.59 - y0) / (y1 - y0),
                         (.75 - x0) / (x1 - x0), (.62 - y0) / (y1 - y0)],
            }]}

    monkeypatch.setattr(delivery, "paddle_engine", lambda: object())
    monkeypatch.setattr(delivery, "paddle_fragments", mock_conflicting_ocr)
    monkeypatch.setattr(delivery, "cropped_image", record_crop)
    monkeypatch.setattr(delivery.Doubao, "configured", lambda: FixedModel())
    result = parse("DELIVERY_NOTE", "image/jpeg", (SAMPLES / "phase-01" / "DN-001.jpg").read_bytes())
    row, = result["delivery"]["lines"]
    assert row["deliveredQty"] == 5 and row["acceptedQty"] is None
    assert "CONFLICT" in row["issues"]
    page, = result["pages"]
    assert page["methods"] == ["PADDLE_OCR", "DOUBAO"]
    assert page["ocrCalls"] == page["doubaoCalls"] == 1
    assert "手写实收 6 箱" in page["text"]
    model_rows = [at for at in result["delivery"]["evidence"] if at["method"] == "DOUBAO"]
    if kind in {"corroborated", "unsupported", "contradictory"}:
        at, = model_rows
        assert at["supporting"] == (kind == "corroborated")
        assert (at["bbox"] is not None) == (kind == "corroborated")
        if kind == "corroborated":
            assert at["bbox"] == pytest.approx([.56, .59, .75, .62])
    else:
        assert not model_rows
        assert any("豆包请求失败" in hint for hint in page["hints"])


def test_note_naming_different_material_does_not_attach_to_sole_row():
    entries = mock_conflicting_ocr()[:-1] + [Fragment("G-02 手写实收 6 箱", (.56, .59, .75, .62))]
    rows, sources, *_ = extract(entries, 1, "PADDLE_OCR")
    line, _ = rows[0]
    assert line["itemCode"] == "G-01"
    assert "AMBIGUOUS_QUANTITY" in line["issues"]
    assert {at["value"] for at in sources if at["field"] == "acceptedQty"} == {5}


def test_model_cannot_fill_missing_quantity_without_local_evidence(monkeypatch):
    class HallucinatingModel:
        def read(self, image, page_no, lines):
            return {"suggestions": [{
                "lineKey": lines[0]["lineKey"], "pageNo": page_no, "field": "acceptedQty",
                "rawText": "实收 5 箱", "value": 5, "bbox": [.76, .46, .85, .5],
            }]}

    def ocr(*_):
        return [entry for entry in mock_conflicting_ocr() if entry.text.startswith("手写")
                or not (.45 < entry.y < .49 and entry.bbox[0] > .76)]

    monkeypatch.setattr(delivery, "paddle_engine", lambda: object())
    monkeypatch.setattr(delivery, "paddle_fragments",
                        lambda *_: [Fragment("手写短收 1", entry.bbox) if entry.text.startswith("手写")
                                    else entry for entry in ocr()])
    monkeypatch.setattr(delivery.Doubao, "configured", lambda: HallucinatingModel())
    result = parse("DELIVERY_NOTE", "image/jpeg", (SAMPLES / "phase-01" / "DN-001.jpg").read_bytes())
    row, = result["delivery"]["lines"]
    assert row["deliveredQty"] == 5 and row["acceptedQty"] is None
    assert {"MODEL_SUGGESTION", "MISSING_ACCEPTED_EVIDENCE"} <= set(row["issues"])


def test_model_request_budget_applies_per_document(monkeypatch):
    class FixedModel:
        def read(self, *_):
            return {"suggestions": []}

    monkeypatch.setattr(delivery, "paddle_engine", lambda: object())
    monkeypatch.setattr(delivery, "paddle_fragments", mock_conflicting_ocr)
    monkeypatch.setattr(delivery.Doubao, "configured", lambda: FixedModel())
    with pymupdf.open() as pdf:
        for _ in range(5):
            pdf.new_page(width=595, height=419)
        result = parse("DELIVERY_NOTE", "application/pdf", pdf.tobytes())
    assert [page["doubaoCalls"] for page in result["pages"]] == [1, 1, 1, 0, 0]
    assert [page["ocrCalls"] for page in result["pages"]] == [1, 1, 1, 1, 1]
    assert all("CONFLICT" in line["issues"] for line in result["delivery"]["lines"])


def test_doubao_adapter_sends_structured_image_message():
    def ark(request):
        assert request.url.host == "ark.cn-beijing.volces.com"
        assert request.headers["Authorization"] == "Bearer test-token"
        payload = json.loads(request.content)
        assert payload["model"] == "test-vision"
        assert payload["response_format"] == {"type": "json_object"}
        image_message, instructions = payload["messages"][0]["content"]
        assert image_message["image_url"]["url"].startswith("data:image/jpeg;base64,")
        assert "p1-r1" in instructions["text"] and "G-01" in instructions["text"]
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"suggestions":[]}'}}]})

    with httpx.Client(transport=httpx.MockTransport(ark)) as api, Image.new("RGB", (10, 10)) as image:
        model = delivery.Doubao("test-token", "test-vision", 35, client=api)
        line = {"lineKey": "p1-r1", "itemCode": "G-01", "itemName": "手套"}
        assert model.read(image, 1, [line]) == {"suggestions": []}


@pytest.mark.parametrize("ocr_result", ["unavailable", "failure", "empty"])
def test_overlay_fallback_keeps_printed_value_as_advisory(monkeypatch, ocr_result):
    def engine():
        if ocr_result == "unavailable":
            raise ImportError()
        return object()

    def recognize(*_):
        if ocr_result == "failure":
            raise RuntimeError()
        return []

    monkeypatch.setattr(delivery, "paddle_engine", engine)
    monkeypatch.setattr(delivery, "paddle_fragments", recognize)
    monkeypatch.setattr(delivery.Doubao, "configured", lambda: None)
    result = parse("DELIVERY_NOTE", "application/pdf", (SAMPLES / "phase-03" / "DN-overlay.pdf").read_bytes())
    line, = result["delivery"]["lines"]
    page, = result["pages"]
    assert line["deliveredQty"] == 6 and line["acceptedQty"] is None
    assert page["status"] == "REVIEW" and "AMBIGUOUS_QUANTITY" in line["issues"]
    at, = [entry for entry in result["delivery"]["evidence"] if entry["field"] == "acceptedQty"]
    assert at["method"] == "PDF_TEXT" and at["value"] == 6 and not at["supporting"]


def test_shared_schema_and_worker_output():
    from jsonschema import Draft202012Validator

    directory = SAMPLES.parent / "contracts" / "parse-result"

    def load(name):
        return json.loads((directory / name).read_text(encoding="utf-8"))

    schema = load("schema.json")
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    for name in ("v1-purchase.json", "v1-delivery.json", "v2-valid.json"):
        validator.validate(load(name))
    assert not validator.is_valid(load("v2-invalid.json"))
    content = (SAMPLES / "phase-01" / "DN-002.pdf").read_bytes()
    accepted = []

    def backend(request):
        if request.url.path.endswith("/lease"):
            return httpx.Response(200, json=dict(
                taskId=2, documentId=3, purpose="DELIVERY_NOTE", mimeType="application/pdf",
                sha256=hashlib.sha256(content).hexdigest(), attemptNo=1, leaseSeconds=30,
                contentUrl="/internal/parse-tasks/2/content",
            ))
        if request.url.path.endswith("/content"):
            return httpx.Response(200, content=content)
        assert request.url.path.endswith("/result")
        accepted.append(json.loads(request.content))
        return httpx.Response(204)

    with httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(backend)) as api:
        assert Worker(api, "pytest").run_once()
    callback, = accepted
    validator.validate(callback)
    assert callback["documentId"] == 3 and callback["workerId"] == "pytest"
