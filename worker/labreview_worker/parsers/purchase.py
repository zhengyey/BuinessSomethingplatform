from collections import defaultdict
from dataclasses import dataclass
from datetime import date
import re

import pymupdf

from .bounds import fit_text

IDENTITY_LABELS = {"采购单号": "orderNo", "供应商": "supplierName", "采购日期": "orderedOn"}
HEADER_LABELS = {
    "物料编码": "itemCode", "物料名称": "itemName", "规格": "specification",
    "单位": "unit", "采购数量": "orderedQty",
}
HEADER_ALIASES = {"物料编号": "物料编码", "订购数量": "采购数量"}
VALUE_LIMITS = {"orderNo": 64, "supplierName": 120, "itemCode": 64, "itemName": 120,
                "specification": 120, "unit": 16}
MAX_LINES = 500
MAX_HINTS = 200
LINE_START_TOLERANCE = 4
ROW_TOLERANCE = 5


@dataclass(frozen=True)
class Fragment:
    text: str
    bbox: tuple[float, float, float, float]

    def source(self, field, page_no):
        return {"field": field, "pageNo": page_no, "text": fit_text(self.text, 1000), "bbox": list(self.bbox)}


def page_fragments(page):
    for block in page.get_text("dict", flags=pymupdf.TEXTFLAGS_DICT & ~pymupdf.TEXT_PRESERVE_IMAGES)["blocks"]:
        for line in block.get("lines", []):
            for span in line["spans"]:
                if span["text"].strip():
                    yield Fragment(span["text"].strip(), tuple(span["bbox"]))


def group_rows(fragments):
    rows = []
    for fragment in sorted(fragments, key=lambda item: item.bbox[1]):
        if not rows or fragment.bbox[1] - rows[-1][0].bbox[1] > ROW_TOLERANCE:
            rows.append([])
        rows[-1].append(fragment)
    return rows


def merge_cells(fragments):
    ordered = sorted(fragments, key=lambda item: item.bbox[0])
    return Fragment(
        " ".join(fragment.text for fragment in ordered),
        (min(item.bbox[0] for item in ordered), min(item.bbox[1] for item in ordered),
         max(item.bbox[2] for item in ordered), max(item.bbox[3] for item in ordered)),
    )


def parse_items(rows, header, page_no, lines, sources, hints):
    anchors = sorted(
        (fragment.bbox[0], HEADER_LABELS[HEADER_ALIASES.get(fragment.text, fragment.text)])
        for fragment in header
    )
    if [field for _, field in anchors] != list(HEADER_LABELS.values()):
        return False
    for row in rows:
        if any(fragment.text.startswith(("请按", "备注", "合计", "说明")) for fragment in row):
            break
        cells = defaultdict(list)
        for fragment in row:
            for x, field in reversed(anchors):
                if fragment.bbox[0] >= x - LINE_START_TOLERANCE:
                    cells[field].append(fragment)
                    break
        if len(cells) < 2:
            continue
        if len(lines) == MAX_LINES:
            return True
        index = len(lines)
        item = dict.fromkeys(HEADER_LABELS.values())
        for label, field in HEADER_LABELS.items():
            if field in cells:
                cell = merge_cells(cells[field])
                sources.append(cell.source(f"lines[{index}].{field}", page_no))
                if field == "orderedQty":
                    if re.fullmatch(r"[0-9]{1,10}", cell.text) and 0 < int(cell.text) <= 2147483647:
                        item[field] = int(cell.text)
                else:
                    if fit_text(cell.text, VALUE_LIMITS[field]) == cell.text:
                        item[field] = cell.text
            if item[field] is None:
                hints.append(f"第 {index + 1} 行{label}缺失或超出字段要求，请核对原件。")
        lines.append(item)
    return False


def purchase_candidate(pdf):
    result = dict.fromkeys(IDENTITY_LABELS.values())
    lines, sources, hints = [], [], []
    exceeded_lines = False
    for page_no, page in enumerate(pdf, 1):
        rows = group_rows(page_fragments(page))
        header_index = next((i for i, row in enumerate(rows)
                             if set(HEADER_LABELS) == {HEADER_ALIASES.get(fragment.text, fragment.text) for fragment in row}), None)
        for row in rows[:header_index]:
            fragments = sorted(row, key=lambda fragment: fragment.bbox[0])
            for index, fragment in enumerate(fragments):
                for label, field in IDENTITY_LABELS.items():
                    if result[field] is not None:
                        continue
                    match = re.fullmatch(re.escape(label) + r"(?:\s*[:：]\s*|\s+)(.*)", fragment.text)
                    value = match.group(1).strip() if match else ""
                    evidence = fragment
                    if fragment.text.rstrip(":： ") == label and index + 1 < len(fragments):
                        next_fragment = fragments[index + 1]
                        is_label = any(next_fragment.text.startswith(label) for label in IDENTITY_LABELS)
                        if not is_label and next_fragment.bbox[0] - fragment.bbox[2] <= 30:
                            evidence = merge_cells([fragment, next_fragment])
                            value = next_fragment.text
                    if not value:
                        continue
                    if field == "orderedOn":
                        try:
                            value = date.fromisoformat(value).isoformat()
                        except ValueError:
                            continue
                    elif fit_text(value, VALUE_LIMITS[field]) != value:
                        sources.append(evidence.source(field, page_no))
                        continue
                    result[field] = value
                    sources.append(evidence.source(field, page_no))
        if header_index is not None and not exceeded_lines:
            exceeded_lines = parse_items(rows[header_index + 1:], rows[header_index], page_no, lines, sources, hints)
    hints.extend(f"{label}缺失或超出字段要求，请核对原件。"
                 for label, field in IDENTITY_LABELS.items() if result[field] is None)
    if exceeded_lines:
        hints.insert(0, f"采购明细超过 {MAX_LINES} 行，已保留前 {MAX_LINES} 行，请核对原件。")
    if not lines:
        hints.append("未识别到采购明细，请核对原件并补全物料行。")
    result["lines"] = lines
    if len(hints) > MAX_HINTS:
        hints = hints[:MAX_HINTS - 1] + ["识别提示过多，请逐行核对原件。"]
    return result, sources[:3000], hints
