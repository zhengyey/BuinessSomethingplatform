import json
import os
from pathlib import Path
import platform
import sys
import time

WORKER = Path(__file__).resolve().parents[1]
SAMPLE = WORKER.parent / "samples" / "phase-01" / "DN-001.jpg"


def main():
    cache = Path(os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(WORKER / ".paddle-cache"))).resolve()
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"
    import paddle
    import paddleocr

    print(json.dumps({
        "python": platform.python_version(), "paddle": paddle.__version__,
        "paddleocr": paddleocr.__version__, "cache": str(cache), "sample": SAMPLE.name,
    }, ensure_ascii=False), flush=True)
    start = time.perf_counter()
    engine = paddleocr.PaddleOCR(
        device="cpu", enable_mkldnn=False,
        text_detection_model_name="PP-OCRv5_mobile_det",
        text_recognition_model_name="PP-OCRv5_mobile_rec",
        use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
    )
    initialized = time.perf_counter()
    lines = [text for result in engine.predict(str(SAMPLE)) for text in result["rec_texts"]]
    elapsed = time.perf_counter()
    printed_note_found = any("DN-001" in text for text in lines)
    print(json.dumps({
        "initSeconds": round(initialized - start, 2), "inferenceSeconds": round(elapsed - initialized, 2),
        "recognizedLines": lines,
        "printedNoteNumberFound": printed_note_found,
    }, ensure_ascii=False))
    return 0 if printed_note_found else 1


if __name__ == "__main__":
    sys.exit(main())
