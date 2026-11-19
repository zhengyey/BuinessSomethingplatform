"""Generate the annotated phase-three delivery-note fixtures from self-made material."""
import argparse
import io
from pathlib import Path

import pymupdf
from PIL import Image, ImageDraw


OUTPUT = Path(__file__).resolve().parent
PHASE_ONE = OUTPUT.parent / "phase-01"
GLOVE = ("G-01", "丁腈手套", "M 码", "箱")
FILTER = ("F-02", "滤芯", "10 英寸", "盒")
HEADERS = ("物料编码", "物料名称", "规格", "单位", "送货数量", "实收数量")
COLUMNS = (35, 109, 218, 318, 374, 464, 560)
ROW_HEIGHT = 32


def create_delivery(number, rows, font_path, headers=HEADERS):
    doc = pymupdf.open()
    page = doc.new_page(width=595, height=420)
    page.insert_font(fontname="SampleCn", fontfile=str(font_path))

    def write(x, y, value, size=11):
        if value is not None:
            page.insert_text((x, y), str(value), fontname="SampleCn", fontsize=size, color=(0.09, 0.15, 0.19))

    write(35, 60, "到货签收单", 22)
    write(471, 60, "自制演示单据")
    write(35, 96, f"单据编号  {number}")
    write(35, 118, "供应商  示例实验耗材供应商")
    write(449, 96, "到货日期  2026-11-19")
    write(449, 118, "采购单号  PO-001")
    page.draw_rect((COLUMNS[0], 146, COLUMNS[len(headers)], 146 + ROW_HEIGHT),
                   fill=(0.92, 0.94, 0.95), color=None)
    for i, row in enumerate((headers, *rows)):
        y = 146 + i * ROW_HEIGHT
        page.draw_line((COLUMNS[0], y), (COLUMNS[len(headers)], y), color=(0.63, 0.67, 0.70))
        for x, cell in zip(COLUMNS, row):
            write(x + 9, y + 20, cell)
    bottom = 146 + (len(rows) + 1) * ROW_HEIGHT
    page.draw_line((COLUMNS[0], bottom), (COLUMNS[len(headers)], bottom), color=(0.63, 0.67, 0.70))
    for x in COLUMNS[:len(headers) + 1]:
        page.draw_line((x, 146), (x, bottom), color=(0.63, 0.67, 0.70))
    write(35, 368, "本材料仅供识别测试。")
    doc.subset_fonts()
    return doc


def render(page, zoom=2.5):
    pixmap = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)
    return Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGB")


def raster_pdf(image, path):
    with pymupdf.open() as result:
        page = result.new_page(width=595, height=420)
        page.insert_image(page.rect, stream=image_bytes(image))
        result.save(path, deflate=True)


def image_bytes(image):
    stream = io.BytesIO()
    image.save(stream, format="PNG")
    return stream.getvalue()


def handwriting():
    with Image.open(PHASE_ONE / "DN-001.jpg") as image:
        image = image.convert("RGB")
    mask = Image.new("RGBA", image.size)
    pen = [
        (20, 52, 121, min(255, (blue - red) * 3)) if blue > red + 30 and blue > green + 14 else (0, 0, 0, 0)
        for red, green, blue in image.getdata()
    ]
    mask.putdata(pen)
    return mask.crop(mask.getbbox())


def main(font_path):
    with pymupdf.open(PHASE_ONE / "DN-002.pdf") as source:
        scan = render(source[0])
        raster_pdf(scan, OUTPUT / "DN-scan.pdf")
        exif = Image.Exif()
        exif[274] = 6
        scan.transpose(Image.Transpose.ROTATE_90).save(OUTPUT / "DN-rotated.jpg", quality=93, exif=exif)
        with pymupdf.open() as mixed:
            mixed.insert_pdf(source)
            failed = mixed.new_page(width=595, height=420)
            illegible = Image.new("RGB", scan.size, (239, 239, 239))
            draw = ImageDraw.Draw(illegible)
            draw.rectangle((50, 48, 1438, 998), outline=(227, 227, 227), width=4)
            draw.rectangle((93, 310, 1396, 720), fill=(232, 232, 232))
            failed.insert_image(failed.rect, stream=image_bytes(illegible))
            mixed.save(OUTPUT / "DN-mixed.pdf", deflate=True)

    with create_delivery("DN-ONLY", [(*GLOVE, 6)], font_path, HEADERS[:5]) as doc:
        doc.save(OUTPUT / "DN-delivery-only.pdf", deflate=True)
    with create_delivery("DN-ZERO", [(*GLOVE, 6, 0)], font_path) as doc:
        doc.save(OUTPUT / "DN-zero.pdf", deflate=True)
    with create_delivery("DN-DIFF", [("G-01", "丁腈手套", "L 码", "盒", 2, 1),
                                      ("F-O2", *FILTER[1:], 3, 3)], font_path) as doc:
        doc.save(OUTPUT / "DN-mismatch.pdf", deflate=True)

    ink = handwriting()
    with create_delivery("DN-CONFLICT", [(*GLOVE, 6, 6)], font_path) as doc:
        picture = render(doc[0])
        picture.paste(ink, (130, 590), ink)
        picture.save(OUTPUT / "DN-conflict.jpg", quality=95)
    with create_delivery("DN-OVERLAY", [(*GLOVE, 6, 6)], font_path) as doc:
        doc[0].insert_image(pymupdf.Rect(465, 199, 551, 224), stream=image_bytes(ink), keep_proportion=True)
        doc.save(OUTPUT / "DN-overlay.pdf", deflate=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, default=Path("C:/Windows/Fonts/simhei.ttf"))
    main(parser.parse_args().font)
