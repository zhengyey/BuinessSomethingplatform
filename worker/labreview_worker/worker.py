import asyncio
import hashlib
import logging

import httpx

from .parsers import ParseError, parse

ROUTE = "/internal/parse-tasks"
CALLBACK_ATTEMPTS = 3
log = logging.getLogger(__name__)


class Worker:
    def __init__(self, api, worker_id, parser=parse):
        self.api = api
        self.worker_id = worker_id
        self.parser = parser

    def run_once(self):
        response = self.api.post(ROUTE + "/lease", json={"workerId": self.worker_id})
        response.raise_for_status()
        if response.status_code == 204:
            return False
        asyncio.run(self.process(response.json()))
        return True

    async def process(self, task):
        task_path = f"{ROUTE}/{task['taskId']}"
        credentials = {"workerId": self.worker_id, "attemptNo": task["attemptNo"]}
        lease_lost = asyncio.Event()
        attempt = asyncio.create_task(self.complete_attempt(task, task_path, credentials, lease_lost))
        interval = min(10, task["leaseSeconds"] / 3)
        while not attempt.done():
            done, _ = await asyncio.wait({attempt}, timeout=interval)
            if done:
                break
            try:
                heartbeat = await asyncio.to_thread(
                    self.api.post, task_path + "/heartbeat", json=credentials,
                )
                if heartbeat.status_code == 409:
                    lease_lost.set()
                    break
                heartbeat.raise_for_status()
            except httpx.HTTPError as exc:
                log.warning("Task %s heartbeat failed (%s)", task["taskId"], type(exc).__name__)
        await attempt

    async def complete_attempt(self, task, task_path, credentials, lease_lost):
        callback = await asyncio.to_thread(self.prepare_callback, task, credentials)
        if callback is not None and not lease_lost.is_set():
            operation, payload = callback
            # A result may have been committed before its response was lost. Keep
            # replaying its exact payload even if a heartbeat now returns 409.
            await self.send_callback(task_path, operation, credentials | payload)

    def prepare_callback(self, task, credentials):
        try:
            content_url = self.api.base_url.join(task["contentUrl"])
            if content_url.netloc != self.api.base_url.netloc or content_url.scheme != self.api.base_url.scheme:
                raise ParseError("DOWNLOAD_FAILED", "原件下载地址无效。")
            response = self.api.get(content_url, params=credentials)
            if response.status_code == 409:
                return None
            response.raise_for_status()
            content = response.content
            if hashlib.sha256(content).hexdigest() != task["sha256"].lower():
                raise ParseError("HASH_MISMATCH", "原件摘要校验失败，请重新上传原件。")
            result = self.parser(task["purpose"], task["mimeType"], content)
            if result.get("protocolVersion") == 2:
                return "result", result | {"documentId": task["documentId"]}
            return "result", {"protocolVersion": 1} | result
        except ParseError as exc:
            return "failure", {"code": exc.code, "message": str(exc), "retryable": exc.retryable}
        except httpx.HTTPError as exc:
            retryable = isinstance(exc, httpx.RequestError) or exc.response.status_code >= 500
            return "failure", {"code": "DOWNLOAD_FAILED", "message": "无法下载原件，请重试或重新上传。",
                               "retryable": retryable}
        except Exception:
            log.exception("Task %s could not be parsed", task["taskId"])
            return "failure", {"code": "PARSE_FAILED", "message": "解析出现异常，请重试或联系管理员。",
                               "retryable": True}

    async def send_callback(self, task_path, operation, payload):
        for attempt in range(CALLBACK_ATTEMPTS):
            try:
                response = await asyncio.to_thread(self.api.post, f"{task_path}/{operation}", json=payload)
                if response.status_code == 409:
                    log.info("%s callback lost its lease for %s", operation, task_path)
                    return
                response.raise_for_status()
                return
            except httpx.HTTPError as exc:
                if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code < 500:
                    raise
                if attempt == CALLBACK_ATTEMPTS - 1:
                    raise
                await asyncio.sleep(0.25 * 2 ** attempt)
