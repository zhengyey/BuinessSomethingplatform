import base64
import json
import os
from pathlib import Path
import sys
import time
from urllib import error, request

SAMPLE = Path(__file__).resolve().parents[2] / "samples" / "phase-01" / "DN-001.jpg"
API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"


def main():
    api_key = os.environ.get("ARK_API_KEY", "").strip()
    model = os.environ.get("LABREVIEW_DOUBAO_MODEL", "").strip()
    if not api_key or not model:
        print("豆包实测待验证，请配置 ARK_API_KEY 和 LABREVIEW_DOUBAO_MODEL。")
        return 2
    image_data = base64.b64encode(SAMPLE.read_bytes()).decode("ascii")
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + image_data}},
            {"type": "text", "text": "抄录印刷的单据编号与物料编码。单独列出可辨认的手写验收数量和备注，不清楚时标为不确定。"},
        ]}],
        "max_tokens": 256,
    }
    req = request.Request(API_URL, data=json.dumps(payload).encode("utf-8"), method="POST",
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + api_key})
    start = time.perf_counter()
    try:
        with request.urlopen(req, timeout=35) as response:
            data = json.load(response)
            status = response.status
        excerpt = data["choices"][0]["message"]["content"]
        if not isinstance(excerpt, str) or not excerpt.strip():
            raise ValueError("empty content")
    except (error.URLError, OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        print(json.dumps({
            "ok": False, "modelOrEndpoint": model, "httpStatus": getattr(exc, "code", None),
            "error": type(exc).__name__, "seconds": round(time.perf_counter() - start, 2),
        }, ensure_ascii=False))
        return 1
    print(json.dumps({
        "ok": True, "modelOrEndpoint": model, "responseModel": data.get("model"),
        "httpStatus": status, "seconds": round(time.perf_counter() - start, 2),
        "excerpt": excerpt[:500],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
