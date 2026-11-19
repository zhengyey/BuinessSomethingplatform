from dataclasses import dataclass
import re
import unicodedata

from .bounds import fit_text


HEADERS = {
    "itemCode": ("物料编码", "物料编号", "货品编码"),
    "itemName": ("物料名称", "货品名称", "品名"),
    "specification": ("规格", "规格型号"),
    "unit": ("单位",),
    "deliveredQty": ("送货数量", "送货数", "发货数量", "送货"),
    "acceptedQty": ("实收数量", "实收数", "验收数量", "签收数量", "实收"),
}
FIELD_LIMITS = {"itemCode": 64, "itemName": 120, "specification": 120, "unit": 16}
QUANTITIES = ("deliveredQty", "acceptedQty")
QUANTITY_LABELS = {label: field for field in QUANTITIES for label in HEADERS[field]}
NOTE_LABEL = "|".join(r"\s*".join(label) for label in sorted(QUANTITY_LABELS, key=len, reverse=True))
NOTE = re.compile(rf"({NOTE_LABEL})\s*[:=]?\s*([0-9]+)")
NON_ACTUAL_PREFIX = re.compile(r"(?:未|无|非|预\s*计|应|拟|计\s*划|暂\s*定)\s*[:=]?\s*$")
NON_LITERAL_SUFFIX = re.compile(r"\s*(?:[+\-−–—*/÷×~～万千百亿]|(?:[.,、至到])\s*[0-9])")
HANDWRITING = re.compile(r"手写|短收|少收|少\s*\d|修改|更正|改为|划改|涂改")
CODE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.\-/]{0,63}")
NOTE_CODE = re.compile(r"(?<![A-Za-z0-9_.\-/])(?:[A-Za-z]+[_.\-/]?[0-9]|[0-9]+[_.\-/]?[A-Za-z])"
                       r"[A-Za-z0-9_.\-/]*(?![A-Za-z0-9_.\-/])")


@dataclass(frozen=True)
class Fragment:
    text: str
    bbox: tuple

    @property
    def y(self):
        return (self.bbox[1] + self.bbox[3]) / 2


def normalized(text):
    return unicodedata.normalize("NFKC", text).strip()


def fragment(text, box, width, height):
    if not text.strip():
        return None
    x0, y0, x1, y1 = box
    bounds = tuple(round(min(1., max(0., value)), 6)
                   for value in (x0 / width, y0 / height, x1 / width, y1 / height))
    if bounds[0] < bounds[2] and bounds[1] < bounds[3]:
        return Fragment(text.strip(), bounds)
    return None


def union(boxes):
    return (min(box[0] for box in boxes), min(box[1] for box in boxes),
            max(box[2] for box in boxes), max(box[3] for box in boxes))


def overlaps(first, second):
    return (min(first[2], second[2]) > max(first[0], second[0])
            and min(first[3], second[3]) > max(first[1], second[1]))


def rows_of(fragments):
    rows = []
    for entry in sorted(fragments, key=lambda entry: entry.y):
        if not rows or abs(entry.y - rows[-1][0].y) > min(.014, (entry.bbox[3] - entry.bbox[1]) * .65):
            rows.append([])
        rows[-1].append(entry)
    return [sorted(row, key=lambda entry: entry.bbox[0]) for row in rows]


def cell_text(entries):
    return " ".join(entry.text for entry in entries)


def quantity(text):
    if re.fullmatch(r"[0-9]{1,10}", normalized(text)):
        value = int(normalized(text))
        return value if value <= 2_147_483_647 else None
    return None


def table_quantity(raw, unit):
    text = normalized(raw)
    unit = normalized(unit)
    if unit and text.endswith(unit):
        text = text[:-len(unit)].strip()
    return quantity(text)


def labeled_quantities(text):
    text = normalized(text)
    for match in NOTE.finditer(text):
        if NON_ACTUAL_PREFIX.search(text[:match.start()]) or NON_LITERAL_SUFFIX.match(text, match.end()):
            continue
        value = quantity(match[2])
        if value is not None:
            yield QUANTITY_LABELS[re.sub(r"\s+", "", match[1])], value


def table_header(row):
    matches = {}
    for entry in row:
        label = re.sub(r"\s+", "", normalized(entry.text))
        for field, names in HEADERS.items():
            if label in names:
                matches.setdefault(field, entry)
    return matches if {"itemName", "unit", "deliveredQty"} <= matches.keys() else {}


def table_cells(row, columns):
    cells = {field: [] for field, _ in columns}
    for entry in row:
        x = entry.bbox[0]
        if x < columns[0][1].bbox[0] - .045:
            continue
        for field, label in reversed(columns):
            if x >= label.bbox[0] - .026:
                cells[field].append(entry)
                break
    return cells


def table_row(cells):
    code = normalized(cell_text(cells.get("itemCode", [])))
    name = normalized(cell_text(cells.get("itemName", [])))
    unit = normalized(cell_text(cells.get("unit", [])))
    return bool((CODE.fullmatch(code) and (name or unit))
                or (not code and name and unit and any(cells.get(field) for field in QUANTITIES)))


def evidence(line, field, method, text, value, bbox, supporting=True):
    return dict(lineKey=line["lineKey"], pageNo=line["pageNo"], field=field, method=method,
                rawText=fit_text(text, 1_000), value=value, bbox=list(bbox) if bbox else None,
                precision="REGION" if bbox else "PAGE", supporting=supporting)


def extract(fragments, page_no, method):
    """Recover rows from aligned text fragments and bind evidence to those rows."""
    rows = rows_of(fragments)
    lines, sources, pending_notes, quantity_areas = [], [], [], []
    columns = []
    handwriting = False
    for row in rows:
        header = table_header(row)
        if header:
            columns = sorted(header.items(), key=lambda value: value[1].bbox[0])
            continue
        text = cell_text(row)
        handwriting |= bool(HANDWRITING.search(text))
        cells = table_cells(row, columns) if columns else {}
        if not cells or not table_row(cells):
            if list(labeled_quantities(text)):
                pending_notes.append((row, text))
            continue
        box = union([entry.bbox for entry in row])
        unit = cell_text(cells.get("unit", []))
        line = {"lineKey": f"p{page_no}-r{len(lines) + 1}", "pageNo": page_no, "issues": []}
        line.update(dict.fromkeys(HEADERS))
        lines.append((line, box))
        for field, label in columns:
            entries = cells[field]
            if field in QUANTITIES:
                left = label.bbox[0] - .025
                right = next((entry.bbox[0] - .025 for _, entry in columns if entry.bbox[0] > label.bbox[0]), 1.)
                quantity_areas.append((line["lineKey"], field, (left, box[1] - .015, right, box[3] + .015)))
            if not entries:
                continue
            raw = cell_text(entries)
            region = union([entry.bbox for entry in entries])
            if field in QUANTITIES:
                value = table_quantity(raw, unit)
                if value is not None:
                    sources.append(evidence(line, field, method, f"{label.text} {raw}", value, region))
                else:
                    codes = NOTE_CODE.findall(normalized(raw))
                    explicit = list(labeled_quantities(fit_text(raw, 1_000))) if not codes or line["itemCode"] in codes else []
                    if not explicit:
                        line["issues"].append("AMBIGUOUS_QUANTITY")
                    for explicit_field, explicit_value in explicit:
                        sources.append(evidence(line, explicit_field, method, raw, explicit_value, region))
            else:
                value = raw
                if field == "specification":
                    value = re.sub(r"(?<=[0-9A-Za-z])(?=[\u3400-\u9fff])", " ", value)
                value = fit_text(value, FIELD_LIMITS[field])
                line[field] = value
                sources.append(evidence(line, field, method, raw, value, region))

    for row, text in pending_notes:
        if not lines:
            continue
        box = union([entry.bbox for entry in row])
        named = [line for line, _ in lines if line["itemCode"]
                 and re.search(r"(?<![A-Za-z0-9-])" + re.escape(line["itemCode"]) + r"(?![A-Za-z0-9-])", text)]
        nearby = [line for line, bounds in lines if box[1] <= bounds[3] + .012 and box[3] >= bounds[1] - .012]
        explicit_code = bool(NOTE_CODE.search(normalized(text)))
        if len(named) == 1 or (not named and not explicit_code and (len(lines) == 1 or len(nearby) == 1)):
            line = named[0] if len(named) == 1 else nearby[0] if nearby else lines[0][0]
            line["issues"].append("HANDWRITING_REVIEW")
            literals = set(labeled_quantities(fit_text(text, 1_000)))
            localized = set()
            for entry in row:
                for field, value in labeled_quantities(fit_text(entry.text, 1_000)):
                    if (field, value) in literals:
                        localized.add((field, value))
                        sources.append(evidence(line, field, method, entry.text, value, entry.bbox))
            for field, value in sorted(literals - localized):
                sources.append(evidence(line, field, method, text, value, box))
        else:
            for line, _ in lines:
                line["issues"].append("AMBIGUOUS_QUANTITY")
    if handwriting and len(lines) == 1:
        lines[0][0]["issues"].append("HANDWRITING_REVIEW")
    return lines, sources, quantity_areas, bool(columns), handwriting
