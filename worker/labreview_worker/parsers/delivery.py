from collections import defaultdict
from io import BytesIO
import logging
import math

import pymupdf
from PIL import Image, ImageOps

from . import MAX_PAGE_TEXT, MAX_PDF_PAGES, ParseError
from .bounds import fit_text
from .delivery_layout import (
    HANDWRITING, QUANTITIES, cell_text, extract, fragment, labeled_quantities, overlaps, rows_of, table_header,
)
from .delivery_services import Doubao, model_evidence, paddle_engine, paddle_fragments


MAX_RENDER_PIXELS = 6_000_000
MAX_RENDER_EDGE = 2500
MAX_DOUBAO_CALLS = 3
log = logging.getLogger(__name__)


def parse_delivery(mime_type, content):
    reader = DeliveryReader(Doubao.configured())
    if mime_type == "application/pdf":
        reader.read_pdf(content)
    elif mime_type in {"image/jpeg", "image/png"}:
        reader.read_image(content, mime_type)
    else:
        raise ParseError("UNSUPPORTED_FORMAT", "无法处理此类文件，请核对原件格式。")
    return dict(protocolVersion=2, parserVersion="delivery-layout-2", pages=reader.pages, hints=[],
                delivery=dict(lines=reader.lines, evidence=reader.evidence))


def display_scale(width, height):
    return min(1, math.sqrt(MAX_RENDER_PIXELS / (width * height)), MAX_RENDER_EDGE / max(width, height))


def pdf_fragments(page):
    rotation = page.rotation_matrix
    return [entry for x0, y0, x1, y1, text, *_ in page.get_text("words")
            if (entry := fragment(text, pymupdf.Rect(x0, y0, x1, y1) * rotation,
                                  page.rect.width, page.rect.height)) is not None]


def quantity_overlay(page, areas):
    if not areas:
        return set()
    rotation = page.rotation_matrix
    bounds = [record["bbox"] for record in page.get_image_info()]
    bounds.extend(annotation.rect for annotation in page.annots() or ())
    overlays = [entry.bbox for box in bounds
                if (entry := fragment("overlay", pymupdf.Rect(box) * rotation,
                                      page.rect.width, page.rect.height)) is not None]
    return {(line_key, field) for line_key, field, area in areas
            if any(overlaps(box, area) for box in overlays)}


def rasterize(page):
    width, height = page.rect.width, page.rect.height
    scale = 2.5 * display_scale(width * 2.5, height * 2.5)
    pixmap = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), colorspace=pymupdf.csRGB, alpha=False)
    return Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)


def cropped_image(image, regions):
    if not regions:
        return image.copy(), (0., 0., 1., 1.)
    width, height = image.size
    pixels = (
        max(0, math.floor((min(box[0] for box in regions) - .035) * width)),
        max(0, math.floor((min(box[1] for box in regions) - .045) * height)),
        min(width, math.ceil((max(box[2] for box in regions) + .035) * width)),
        min(height, math.ceil((max(box[3] for box in regions) + .045) * height)),
    )
    return image.crop(pixels), (pixels[0] / width, pixels[1] / height, pixels[2] / width, pixels[3] / height)


def resolve(lines, sources):
    by_field = defaultdict(set)
    for at in sources:
        if at["supporting"] and at["field"] in QUANTITIES:
            by_field[at["lineKey"], at["field"]].add(at["value"])
    for line in lines:
        for field in QUANTITIES:
            values = by_field[line["lineKey"], field]
            if len(values) > 1:
                line["issues"].append("CONFLICT")
            elif values:
                line[field] = next(iter(values))
            else:
                issue = "MISSING_ACCEPTED_EVIDENCE" if field == "acceptedQty" else "MISSING_DELIVERED_EVIDENCE"
                line["issues"].append(issue)
        line["issues"] = list(dict.fromkeys(line["issues"]))


class DeliveryReader:
    def __init__(self, doubao):
        self.pages, self.lines, self.evidence = [], [], []
        self.doubao = doubao
        self.doubao_calls = 0
        self.ocr_unavailable = False

    def read_pdf(self, content):
        try:
            with pymupdf.open(stream=content, filetype="pdf") as pdf:
                if pdf.is_encrypted or pdf.needs_pass:
                    raise ParseError("ENCRYPTED_PDF", "PDF 已加密，请上传可直接打开的原件。")
                if not pdf.is_pdf or not pdf.page_count:
                    raise ParseError("INVALID_PDF", "PDF 损坏或没有可读页面，请重新上传原件。")
                if pdf.page_count > MAX_PDF_PAGES:
                    raise ParseError("TOO_MANY_PAGES", f"PDF 超过 {MAX_PDF_PAGES} 页，请拆分后上传。")
                for page_no in range(1, pdf.page_count + 1):
                    self.read_pdf_page(pdf, page_no)
        except (pymupdf.FileDataError, RuntimeError, ValueError, TypeError) as exc:
            raise ParseError("INVALID_PDF", "PDF 损坏或无法读取，请重新上传原件。") from exc

    def read_pdf_page(self, pdf, page_no):
        result = self.new_page(page_no)
        try:
            page = pdf[page_no - 1]
            if page.rect.width <= 0 or page.rect.height <= 0:
                raise ValueError("Empty page bounds")
            result.update(width=page.rect.width, height=page.rect.height, rotation=page.rotation)
        except (RuntimeError, ValueError) as exc:
            log.warning("PDF page %s cannot be accessed (%s)", page_no, type(exc).__name__)
            result["hints"].append("无法读取 PDF 页面。")
            return
        try:
            fragments = pdf_fragments(page)
            result["text"] = self.bounded_text(result, "PDF 文字", page.get_text(sort=True).strip())
            if fragments:
                result["methods"].append("PDF_TEXT")
            local = extract(fragments, page_no, "PDF_TEXT")
        except (RuntimeError, ValueError) as exc:
            log.warning("PDF text/geometry unavailable for page %s (%s)", page_no, type(exc).__name__)
            result["hints"].append("PDF 文字或页面坐标读取失败，尝试页面识别。")
            local = ([], [], [], False, False)
        try:
            overlay = quantity_overlay(page, local[2])
        except (RuntimeError, ValueError) as exc:
            log.warning("PDF overlay check unavailable for page %s (%s)", page_no, type(exc).__name__)
            result["hints"].append("无法检查数量区是否存在图像覆盖。")
            overlay = {(line_key, field) for line_key, field, _ in local[2]}
        self.read_page(result, local, overlay, lambda: rasterize(page))

    def read_image(self, content, mime_type):
        try:
            with Image.open(BytesIO(content)) as source:
                if source.format != ("JPEG" if mime_type == "image/jpeg" else "PNG"):
                    raise ValueError("Unexpected image format")
                if source.width * source.height > Image.MAX_IMAGE_PIXELS:
                    raise ValueError("Image exceeds safe decode bounds")
                rotation = {3: 180, 4: 180, 5: 270, 6: 90, 7: 90, 8: 270}.get(source.getexif().get(274), 0)
                width, height = ((source.height, source.width) if rotation in (90, 270) else source.size)
                scale = display_scale(source.width, source.height)
                size = (max(1, int(source.width * scale)), max(1, int(source.height * scale)))
                source.thumbnail(size)
                with ImageOps.exif_transpose(source) as upright, upright.convert("RGB") as image:
                    result = self.new_page(1, width, height, rotation)
                    self.read_page(result, ([], [], [], False, False), set(), image.copy)
        except (OSError, ValueError, SyntaxError, Image.DecompressionBombError) as exc:
            raise ParseError("INVALID_IMAGE", "图片损坏或无法读取，请重新上传原件。") from exc

    def new_page(self, page_no, width=1, height=1, rotation=0):
        result = dict(pageNo=page_no, width=width, height=height, rotation=rotation, status="PAGE_FAILED",
                      text=None, hints=[], methods=[], ocrCalls=0, doubaoCalls=0)
        self.pages.append(result)
        return result

    @staticmethod
    def bounded_text(result, kind, text):
        bounded = fit_text(text, MAX_PAGE_TEXT)
        if bounded != text:
            result["hints"].append(f"{kind}过长，已截取部分文本。")
        return bounded or None

    def read_page(self, result, local, overlay, render):
        page_no = result["pageNo"]
        rows, sources, _, _, handwriting = local
        uncertain = handwriting or any(line["issues"] for line, _ in rows)
        if overlay:
            result["hints"].append("数量区存在图像或批注覆盖，尝试 PaddleOCR。")
        elif not rows or uncertain:
            result["hints"].append("本地文字层无法稳定定位数量或物料行，尝试 PaddleOCR。")
        else:
            self.finish_page(result, rows, sources)
            return
        try:
            with render() as image:
                ocr = self.read_ocr(result, image)
                if ocr is not None:
                    other_rows, other_sources, _, _, ocr_handwriting = extract(ocr, page_no, "PADDLE_OCR")
                    rows, sources = self.merge_rows(rows, sources, other_rows, other_sources)
                    uncertain |= ocr_handwriting or any(line["issues"] for line, _ in rows)
                options = defaultdict(set)
                for entry in sources:
                    if entry["field"] in QUANTITIES:
                        options[entry["lineKey"], entry["field"]].add(entry["value"])
                conflict = any(len(values) > 1 for values in options.values())
                overlay_unresolved = self.mark_unverified_overlays(result, rows, sources, overlay)
                if rows and (uncertain or conflict or overlay_unresolved):
                    if uncertain:
                        result["hints"].append("检测到改写、手写或数量归属不明。")
                    if conflict:
                        result["hints"].append("同一物料字段出现多个数量，待核对。")
                    regions = self.model_regions(rows, ocr, sources, overlay_unresolved)
                    self.read_model(result, image, rows, sources, regions)
        except (OSError, RuntimeError, ValueError) as exc:
            log.warning("Page %s image could not be rendered (%s)", page_no, type(exc).__name__)
            result["hints"].append("页面图像渲染失败。")
            self.mark_unverified_overlays(result, rows, sources, overlay)
        self.finish_page(result, rows, sources)

    @staticmethod
    def mark_unverified_overlays(result, rows, sources, overlay):
        ocr_fields = {(entry["lineKey"], entry["field"]) for entry in sources
                      if entry["method"] == "PADDLE_OCR" and entry["supporting"]}
        unresolved = overlay - ocr_fields
        if unresolved:
            result["hints"].append("覆盖区域内缺少可核对的数量证据，保留原文字层作为参考。")
            affected = {line_key for line_key, _ in unresolved}
            for line, _ in rows:
                if line["lineKey"] in affected:
                    line["issues"].append("AMBIGUOUS_QUANTITY")
            for entry in sources:
                if (entry["lineKey"], entry["field"]) in unresolved:
                    entry["supporting"] = False
        return bool(unresolved)

    @staticmethod
    def model_regions(rows, ocr, sources, overlay_unresolved):
        if not ocr or overlay_unresolved:
            return []
        fragments_by_row = rows_of(ocr)
        headers = [entry.bbox for row in fragments_by_row if table_header(row) for entry in row]
        if not headers:
            return []
        notes = [entry.bbox for row in fragments_by_row
                 if HANDWRITING.search(cell_text(row)) or list(labeled_quantities(cell_text(row)))
                 for entry in row]
        issues = {issue for line, _ in rows for issue in line["issues"]}
        if not notes and issues & {"HANDWRITING_REVIEW", "AMBIGUOUS_QUANTITY"}:
            return []
        quantities = [entry["bbox"] for entry in sources if entry["field"] in QUANTITIES and entry["bbox"]]
        return headers + notes + quantities + [bounds for _, bounds in rows]

    def read_ocr(self, result, image):
        if self.ocr_unavailable:
            result["hints"].append("PaddleOCR 不可用，未执行页面识别。")
            return None
        try:
            engine = paddle_engine()
        except Exception as exc:
            self.ocr_unavailable = True
            log.warning("PaddleOCR unavailable (%s)", type(exc).__name__)
            result["hints"].append("PaddleOCR 依赖或模型不可用，未执行页面识别。")
            return None
        result["ocrCalls"] = 1
        result["methods"].append("PADDLE_OCR")
        try:
            fragments = paddle_fragments(engine, image)
        except Exception as exc:
            log.warning("Page %s OCR failed (%s)", result["pageNo"], type(exc).__name__)
            result["hints"].append("PaddleOCR 页面识别失败。")
            return None
        if not fragments:
            result["hints"].append("PaddleOCR 未识别出可读文字。")
        else:
            ocr_text = "\n".join(entry.text for entry in fragments)
            combined = f"{result['text']}\n\n[PaddleOCR]\n{ocr_text}" if result["text"] else ocr_text
            result["text"] = self.bounded_text(result, "页面文字", combined)
        return fragments

    @staticmethod
    def merge_rows(rows, sources, other_rows, other_sources):
        if not rows:
            return other_rows, other_sources
        remapped = {}
        for other, other_box in other_rows:
            nearby = [line for line, bounds in rows if overlaps(bounds, other_box)
                      and (not line["itemCode"] or not other["itemCode"] or line["itemCode"] == other["itemCode"])]
            if len(nearby) == 1:
                line = nearby[0]
                remapped[other["lineKey"]] = line["lineKey"]
                line["issues"].extend(other["issues"])
                for field in ("itemCode", "itemName", "specification", "unit"):
                    if line[field] is None:
                        line[field] = other[field]
            elif any(overlaps(bounds, other_box) for _, bounds in rows):
                for line, bounds in rows:
                    if overlaps(bounds, other_box):
                        line["issues"].append("AMBIGUOUS_QUANTITY")
            else:
                key = f"p{other['pageNo']}-r{len(rows) + 1}"
                remapped[other["lineKey"]] = key
                other["lineKey"] = key
                rows.append((other, other_box))
        for entry in other_sources:
            if entry["lineKey"] in remapped:
                sources.append(entry | {"lineKey": remapped[entry["lineKey"]]})
        return rows, sources

    def read_model(self, result, image, rows, sources, regions):
        if self.doubao is None:
            result["hints"].append("豆包未配置，保留本地证据。")
            return
        if self.doubao_calls >= MAX_DOUBAO_CALLS:
            result["hints"].append("已达到每份材料的豆包调用上限，待人工核对。")
            return
        self.doubao_calls += 1
        result["doubaoCalls"] = 1
        result["methods"].append("DOUBAO")
        lines = [line for line, _ in rows]
        try:
            cropped, crop = cropped_image(image, regions)
            with cropped:
                response = self.doubao.read(cropped, result["pageNo"], lines)
            suggestions = model_evidence(response, result["pageNo"], lines, sources, crop)
        except Exception as exc:
            log.warning("Page %s Doubao request or structure validation failed (%s)", result["pageNo"],
                        type(exc).__name__)
            result["hints"].append("豆包请求失败、超时或返回结构无效，保留本地证据。")
            return
        sources.extend(suggestions)
        uncertain = {entry["lineKey"] for entry in suggestions if not entry["supporting"]}
        for line in lines:
            if line["lineKey"] in uncertain:
                line["issues"].append("MODEL_SUGGESTION")
        result["hints"].append("豆包已返回核对建议。" if suggestions else "豆包未返回可核对的数量建议。")

    def finish_page(self, result, rows, sources):
        lines = [line for line, _ in rows]
        resolve(lines, sources)
        if not lines:
            if result["text"]:
                result["status"] = "REVIEW"
            result["hints"].append("未定位到可核对的物料行。")
            return
        result["status"] = "REVIEW" if any(line["issues"] for line in lines) or result["doubaoCalls"] else "EXTRACTED"
        if any("MISSING_ACCEPTED_EVIDENCE" in line["issues"] for line in lines):
            result["hints"].append("缺少实收依据，请现场确认。")
        by_line = defaultdict(list)
        for entry in sources:
            by_line[entry["lineKey"]].append(entry)
        for line in lines:
            entries = by_line[line["lineKey"]]
            if len(self.lines) >= 500 or len(self.evidence) + len(entries) > 3000:
                result["status"] = "REVIEW"
                result["hints"].append("已达到识别结果容量上限，剩余物料行请现场核对。")
                break
            self.lines.append(line)
            self.evidence.extend(entries)
