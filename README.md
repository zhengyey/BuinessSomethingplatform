# 采购到货核对工作台

收货员可从文字型 PDF 导入采购单，也可手工建立采购单、登记分批到货，并从每行物料追溯签收记录。任务中心保存处理进度、原件和识别结果。

## 本地启动

需要 JDK 21、Node.js 22.12+（22.x）或 24.x、能运行 Compose v2 的 Docker，以及 Python 3.12。Maven Wrapper 会在首次运行时下载 Maven。可先运行 `java -version`，并将 `JAVA_HOME` 指向本机的 JDK 21 安装目录。

在项目根目录打开 PowerShell。首次运行复制环境配置，并按需调整本地账号、密码和端口。

```powershell
Copy-Item .env.example .env
docker compose up -d mysql
docker compose ps
```

首次配置时，将 `.env` 中的 `LABREVIEW_WORKER_TOKEN` 换成本地专用的随机值。等 MySQL 状态变成 `healthy`。在根目录的后端终端载入配置，再启动 Java 应用。Worker 和测试终端也可以从项目根目录用同一段命令载入配置。

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2]
    }
}
Set-Location server
.\mvnw.cmd spring-boot:run
```

在另一终端从项目根目录载入配置并启动 Worker。基础依赖支持采购单和文字型签收 PDF。需要处理照片或扫描件时，把 PaddleOCR 依赖装到运行 Worker 的同一环境。

```powershell
if (!$env:LABREVIEW_API_PORT) { $env:LABREVIEW_API_PORT = '8080' }
$env:LABREVIEW_API_URL = "http://127.0.0.1:$env:LABREVIEW_API_PORT"
Set-Location worker
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
# 识别照片或扫描件时安装，首次识别可能下载 PP-OCRv5 模型。
.\.venv\Scripts\python.exe -m pip install -r tools/requirements-paddleocr.txt
.\.venv\Scripts\python.exe -X utf8 -m labreview_worker
```

在第四个终端从项目根目录启动 Web。

```powershell
Set-Location web
npm ci
npm run dev
```

打开 [本地工作台](http://127.0.0.1:5173)。示例配置的用户名为 `receiver`，密码为 `local-receiver-password`。后端默认端口为 `8080`，MySQL 默认端口为 `3306`。端口被占用时，可在项目的 `.env` 中设置 `LABREVIEW_API_PORT` 或 `LABREVIEW_DB_PORT`。Vite 从项目根目录的 `.env` 获取后端端口。用 `docker compose stop mysql` 停止数据库，数据保存在 Compose 的 `mysql-data` 持久卷中。

| 环境变量 | 用途 |
|---|---|
| `LABREVIEW_DB_USER`、`LABREVIEW_DB_PASSWORD` | MySQL 应用账号和密码 |
| `LABREVIEW_DB_ROOT_PASSWORD` | Compose 初始化、创建测试库时使用 |
| `LABREVIEW_DB_NAME` | 后端连接的库名，默认 `labreview` |
| `LABREVIEW_DB_PORT`、`LABREVIEW_API_PORT` | 本机 MySQL 和 Java 端口 |
| `LABREVIEW_WEB_PORT` | Vite 的本机端口，默认 `5173` |
| `LABREVIEW_USERNAME`、`LABREVIEW_PASSWORD` | 本地收货员账号和密码 |
| `LABREVIEW_WORKER_TOKEN` | Java 与 Worker 共用的服务令牌 |
| `LABREVIEW_API_URL` | Worker 直连 Java 的地址，默认 `http://127.0.0.1:8080` |
| `LABREVIEW_DOCUMENTS_DIR` | Java 原件目录。按上述方式从 `server/` 启动时，默认在项目的 `var/documents/` |
| `LABREVIEW_LEASE_SECONDS` | 默认 30 秒，每 10 秒续租。可在隔离库用更短租约演示恢复 |
| `LABREVIEW_UPLOAD_MAX_BYTES` | 每份原件默认上限 20 MiB。提高上限时可同步调整 `LABREVIEW_MULTIPART_MAX_REQUEST` |
| `PADDLE_PDX_CACHE_HOME` | 可选的 PaddleOCR 模型缓存目录，默认 `worker/.paddle-cache/` |
| `ARK_API_KEY`、`LABREVIEW_DOUBAO_MODEL` | 可选的方舟凭据与视觉模型 ID 或推理接入点 |
| `LABREVIEW_DOUBAO_TIMEOUT_SECONDS` | 可选的豆包请求超时，默认 35 秒 |

从阶段二升级时，先等旧 Worker 完成在手任务并退出，再启动新版 Java 以应用 Flyway V3，之后更新 Web 和 Worker。旧签收任务仍按原状态查看。体验新版候选时，重新上传一份样例。

## 浏览器演示

### 导入采购单和登记签收材料

1. 从「采购订单」点「导入采购单」，上传 `samples/phase-01/PO-001.pdf`，记下任务编号后离开。Worker 使用 PDF 文本层在本地解析，安装好基础依赖后，导入不需要云端配置。
2. 打开「任务中心」，进入该任务。打开原件预览，对照供应商、采购日期和两行物料。若演示库已有 PO-001，可改用 `PO-002`。修正需要的字段并确认建单。
3. 在订单详情打开「上传签收材料」，先后上传 `DN-001.jpg` 和 `DN-002.pdf`。在任务详情逐页查看候选值，展开送货数和实收数可查页码、原文与识别方法。物料的已收与待收数量仍由收货确认更新。
4. 要演示租约恢复，可在隔离环境让 Worker 领取后停止它。重启 Worker 后，原租约一到期就会重新领取，任务详情中的尝试编号递增。也可在测试环境运行 `./mvnw.cmd -Dtest=TaskFlowTest test`，用可控的过期记录复现接管与迟到回调。终态可重试时，任务详情会显示「重新排队」。

浏览器可直接预览原件或使用下载入口。上传支持的扩展名与文件头必须一致。PDF 最多 50 页。没有文本层的采购单会提示手工建单。损坏或加密文件的任务可在任务详情查看原因和原件。若任务长时间排队，检查 Worker 的 API 地址、服务令牌和启动日志。若原件无法打开，检查 Java 配置的存储目录及权限。备份时同时保留数据库卷和原件目录。

### 验证签收识别分流

- `samples/phase-01/DN-002.pdf` 从 PDF 文字层取值。手套送 5、实收 5 箱，滤芯送 3、实收 3 盒。
- `samples/phase-03/DN-scan.pdf` 是同一份单据的纯扫描版，使用 PaddleOCR。 `DN-rotated.jpg` 可验证照片方向校正。
- `DN-delivery-only.pdf` 仅载明送货 6 箱，实收保持「待现场确认」。 `DN-zero.pdf` 的明确实收 0 单独显示。 `DN-conflict.jpg` 和 `DN-overlay.pdf` 保留印刷与手写分歧。 `DN-mixed.pdf` 保留第 1 页候选，并为第 2 页显示单独的失败原因。

识别时可选的豆包请求会把疑难页的必要图像与字段说明发往方舟。每页最多一次、每份材料最多三次。缺少云配置、超时或返回结构异常时，页面仍可查看已识别的本地证据。使用两项云配置启用该路径。共享契约和正反示例位于 [contracts/parse-result](./contracts/parse-result/)，样例真值与路由记录见 [samples/phase-03/expected.json](./samples/phase-03/expected.json)。方舟请求格式可参阅 [图像理解](https://docs.volcengine.com/docs/ark/image-understanding?lang=zh) 和 [结构化输出](https://docs.volcengine.com/docs/ark/structured-output-beta?lang=zh)。

### 原图核对与收货确认

在独立测试库新建一张订单，每次使用新的采购单号。录入 G-01 丁腈手套 10 箱和 F-02 滤芯 5 盒，编码、名称、规格和单位可照 [示例订单](./samples/phase-01/expected.json) 填写。

1. 从订单详情的「签收材料」上传 `samples/phase-01/DN-001.jpg`。等待 Worker 完成，点击材料列表或任务详情中的「核对签收单」。
2. 展开「原始候选与证据」，点击实收证据，在左侧核对手写实收 5 箱。核实签收单号和到货日期。将页面处置设为「已核对明细」，选择订单物料和行处置「入账」，填写手写数量的核对理由。
3. 点击「保存草稿」，返回订单。手套仍待收 10 箱。从「继续核对」打开草稿，检查恢复的数据。确认入账后，手套待收 5 箱。
4. 对同一测试订单上传并核对 `DN-002.pdf`，确认手套 5 箱、滤芯 3 盒。手套待收 0 箱，滤芯待收 2 盒。展开手套的累计已收，点击两个签收单号，可分别查看确认人、时间和原件证据。
5. 用 [阶段三样例](./samples/phase-03/) 复现边界场景。空实收需核实，明确的零可选择「本行未收」。解析失败页可按页补录，或标记为无明细并说明原因。整单没有正数实收时，填写结案原因后选择「完成核对，无入账」。

存量手工收货可以从物料历史打开，来源显示为「手工登记」。旧版签收任务支持查看原件，可通过订单的「登记本次到货」收货。阶段四的联调数据和旋转图高亮见 [验收记录](./docs/phase-04-acceptance.md)。

### 重复材料、无号核对与实收更正

1. 在独立测试库用新订单完成 DN-001、DN-002 的收货。把 DN-001 改文件名后重新上传，「材料已上传」会链接到原任务、原核对或收货记录。换角度重拍或改变文件编码后会登记新材料，确认相同供应商及签收单号时会链接到已有收货。
2. 原件缺号或号码无法辨认时，在手工收货或材料核对页勾选「单号无法核实」，填写缺号原因。点「检查重复收货」，比较同供应商、同到货日、共享物料编码的记录。确认为另一次到货时填写核重理由后提交。保存过的候选发生变化时，确认会提示重新核实。
3. 从订单物料的收货历史点击签收单号，再点「更正实收」。填入新的实收总数和原因。例如 DN-001 由 5 箱改为 4 箱，DN-002 保持 5 箱，手套累计为 9 箱、待收 1 箱。详情显示原确认量、有效量、原因、确认人和更正时间。

第五阶段的并发、迁移对账及测试记录见 [验收记录](./docs/phase-05-acceptance.md)。历史迁移练习可使用 [V4 测试样本](./server/src/test/resources/phase5-v4-fixture.sql)，该脚本面向已迁移至 V4 的独立测试库。

### 手工采购与分批收货

原件与标注在 [samples/phase-01](samples/phase-01/)。登录后选择「新建采购单」，按 `PO-001.pdf` 录入采购单和两行物料。进入详情后选择「登记本次到货」。

| 操作 | 丁腈手套 G-01（已收 / 待收） | 滤芯 F-02（已收 / 待收） |
|---|---|---|
| 创建 PO-001 | 0 / 10 箱 | 0 / 5 盒 |
| DN-001 手套送 6、实收 5，备注「短 1 箱」 | 5 / 5 箱 | 0 / 5 盒 |
| DN-002 手套送 5 收 5，滤芯送 3 收 3 | 10 / 0 箱 | 3 / 2 盒 |

刷新页面，返回列表按 `PO-001` 和「部分到货」筛选。再次进入详情，展开每行「已收」数量。手套应有 DN-001 和 DN-002 两笔各 5 箱的实收记录，滤芯应只有 DN-002 的 3 盒。补收滤芯 2 盒的用例在测试库执行，演示订单保持两批收货的结果。

## 运行测试

测试库使用同一 MySQL 服务中的独立 schema。首次在根目录运行下列命令，进入 MySQL 后按提示输入 `.env` 中的 root 密码。

```powershell
docker compose exec mysql mysql -uroot -p
```

```sql
CREATE DATABASE IF NOT EXISTS labreview_test CHARACTER SET utf8mb4;
GRANT ALL ON labreview_test.* TO 'labreview'@'%';
EXIT;
```

如果修改过应用用户名，用实际用户名替换授权语句中的 `labreview`。按本地启动步骤载入环境后，在 `server` 运行 `.\mvnw.cmd test`。测试用采购单号每次生成，数据库名默认 `labreview_test`，可用 `LABREVIEW_TEST_DB_NAME` 指定其他以 `_test` 结尾的库。JUnit 启动独立的 Java 进程内服务端口。

浏览器回归需要让 Java 和 Worker 使用同一测试后端，并为 Java 指定单独的原件目录。以下示例使用测试后端端口 `8081` 和测试 Web 端口 `5174`。如遇端口占用，可换用空闲端口，并在三个终端使用同一个测试后端端口。

```powershell
# 后端终端. 载入 .env 后，在 server/ 运行
$env:LABREVIEW_DB_NAME = 'labreview_test'
$env:LABREVIEW_API_PORT = '8081'
$env:LABREVIEW_DOCUMENTS_DIR = Join-Path (Split-Path $PWD -Parent) 'var/web-test-documents'
.\mvnw.cmd spring-boot:run
```

```powershell
# Worker 终端. 载入 .env 后，从项目根目录进入 worker/
Set-Location worker
$env:LABREVIEW_API_URL = 'http://127.0.0.1:8081'
# 完整浏览器用例需要在此环境安装 PaddleOCR。
.\.venv\Scripts\python.exe -X utf8 -m labreview_worker
```

```powershell
# Web 终端. 载入 .env 后，从项目根目录进入 web/
Set-Location web
npm ci
npm run build
npx playwright install chromium
$env:LABREVIEW_API_PORT = '8081'
$env:LABREVIEW_WEB_PORT = '5174'
npm run test:e2e
```

已有 Microsoft Edge 时，可设置 `LABREVIEW_BROWSER_CHANNEL=msedge` 使用该浏览器。浏览器用例默认使用独立订单号。每次请求以内置会话和 CSRF 校验确认登录身份，收货人由后端从会话取得。

Python 测试在 `worker/` 运行。

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -X utf8 -m pytest
```

## 识别环境探针

从项目根目录运行下列命令。两项探针读取同一份自制 `DN-001.jpg`。

```powershell
python -m venv worker/.venv-ocr
.\worker\.venv-ocr\Scripts\python.exe -m pip install -r worker/tools/requirements-paddleocr.txt
.\worker\.venv-ocr\Scripts\python.exe -X utf8 worker/tools/probe_paddleocr.py

# 用环境变量注入 ARK_API_KEY 和 LABREVIEW_DOUBAO_MODEL 后运行
.\worker\.venv\Scripts\python.exe -X utf8 worker/tools/probe_doubao.py
```

PaddleOCR 首次运行会下载模型，输出模型缓存路径、版本、耗时和实读片段。若下载失败，可先检查模型源是否可达。豆包探针使用开户方舟的实际模型或推理接入点，输出真实调用结果和耗时。缺少云端配置时，探针会标注待验证，本地导入仍可运行。

[阶段四验收记录](./docs/phase-04-acceptance.md) · [阶段五验收记录](./docs/phase-05-acceptance.md)
