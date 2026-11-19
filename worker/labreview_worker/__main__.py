import logging
import os
import socket
import time
import uuid

import httpx

from .worker import Worker


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    log = logging.getLogger(__name__)
    token = os.environ.get("LABREVIEW_WORKER_TOKEN", "").strip()
    if not token:
        raise SystemExit("请配置 LABREVIEW_WORKER_TOKEN。")
    worker_id = os.environ.get("LABREVIEW_WORKER_ID") or f"{socket.gethostname()[:60]}-{uuid.uuid4().hex}"
    base_url = os.environ.get("LABREVIEW_API_URL", "http://127.0.0.1:8080")
    with httpx.Client(base_url=base_url, headers={"Authorization": f"Bearer {token}"},
                      timeout=httpx.Timeout(5.0), trust_env=False) as api:
        worker = Worker(api, worker_id)
        log.info("Worker %s started", worker_id)
        try:
            while True:
                try:
                    if worker.run_once():
                        continue
                except httpx.HTTPStatusError as exc:
                    if exc.response.status_code in {401, 403}:
                        raise SystemExit("Worker 服务令牌未获授权，请核对 LABREVIEW_WORKER_TOKEN。") from None
                    log.warning("Worker HTTP request returned %s", exc.response.status_code)
                except httpx.RequestError as exc:
                    log.warning("Worker could not contact backend (%s)", type(exc).__name__)
                time.sleep(1)
        except KeyboardInterrupt:
            log.info("Worker stopped")


if __name__ == "__main__":
    main()

