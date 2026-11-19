package labreview.task;

import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import labreview.api.ApiException;
import labreview.order.OrderMapper;
import labreview.task.TaskModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Service
class TaskService {
    private static final int ATTEMPTS_PER_CYCLE = 3;
    private static final TypeReference<List<String>> HINTS = new TypeReference<>() {};
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .withCoercionConfig(LogicalType.Enum, config ->
                    config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private final TaskMapper tasks;
    private final OrderMapper orders;
    private final Validator validator;
    private final ObjectMapper json;
    private final int leaseSeconds;

    TaskService(TaskMapper tasks, OrderMapper orders, Validator validator, ObjectMapper json,
                @Value("${labreview.lease-seconds}") int leaseSeconds) {
        if (leaseSeconds < 3 || leaseSeconds > 300) {
            throw new IllegalArgumentException("LABREVIEW_LEASE_SECONDS 必须在 3 至 300 秒之间");
        }
        this.tasks = tasks;
        this.orders = orders;
        this.validator = validator;
        this.json = json;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireExhausted() {
        // Lock only exhausted leases so the sweep does not compete with live callbacks.
        for (long id : tasks.exhausted(ATTEMPTS_PER_CYCLE)) {
            tasks.expireExhausted(id);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Lease lease(String worker) {
        Long id = tasks.next(ATTEMPTS_PER_CYCLE);
        if (id == null) return null;
        tasks.take(id, worker, leaseSeconds);
        Row row = tasks.row(id);
        return new Lease(id, row.documentId(), row.purpose(), row.mimeType(), row.sha256(),
                worker, row.attemptNo(), "/internal/parse-tasks/" + id + "/content", leaseSeconds);
    }

    long documentForActiveAttempt(long id, String worker, long attempt) {
        Row row = find(id);
        requireActive(row, worker, attempt);
        return row.documentId();
    }

    void heartbeat(long id, Claim claim) {
        if (tasks.heartbeat(id, claim.workerId(), claim.attemptNo(), leaseSeconds) == 0) throw stale();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void result(long id, JsonNode payload) {
        var version = payload.path("protocolVersion");
        if (version.isIntegralNumber() && version.canConvertToInt() && version.asInt() == 2) {
            deliveryResult(id, deserialize(payload, DeliveryResult.class, STRICT_JSON));
            return;
        }
        var input = deserialize(payload, Result.class, json);
        if (input.protocolVersion() != 1) throw ApiException.invalid("解析协议版本不受支持", "protocolVersion");
        StoredResult stored = new StoredResult(input.candidate(), input.sources(), input.hints());
        String hash = digest(json.writeValueAsBytes(List.of(input.parserVersion(), input.pages(), stored)));
        Row row = locked(id);
        if (replayed(row, input.workerId(), input.attemptNo(), hash)) return;
        requireActive(row, input.workerId(), input.attemptNo());
        validateResult(row, input);
        for (PageResult page : input.pages()) {
            tasks.insertPage(id, page.pageNo(), page.status(), page.text(), json.writeValueAsString(page.hints()));
        }
        tasks.complete(id, input.parserVersion(), json.writeValueAsString(stored), hash);
    }

    private void deliveryResult(long id, DeliveryResult input) {
        var stored = new DeliveryResult.Stored(input.protocolVersion(), input.documentId(), input.pages(),
                input.hints(), input.delivery());
        String hash = digest(json.writeValueAsBytes(List.of(input.parserVersion(), stored)));
        Row row = locked(id);
        if (replayed(row, input.workerId(), input.attemptNo(), hash)) return;
        requireActive(row, input.workerId(), input.attemptNo());
        DeliveryValidation.validate(row, input);
        for (var page : input.pages()) {
            tasks.insertPage(id, page.pageNo(), page.status(), page.text(), json.writeValueAsString(page.hints()));
        }
        tasks.complete(id, input.parserVersion(), json.writeValueAsString(stored), hash);
    }

    private <T> T deserialize(JsonNode payload, Class<T> type, ObjectMapper mapper) {
        T result;
        try {
            result = mapper.treeToValue(payload, type);
        } catch (JacksonException error) {
            throw ApiException.invalid("请检查解析结果格式", null);
        }
        if (result == null) throw ApiException.invalid("请检查解析结果格式", null);
        var errors = validator.validate(result);
        if (!errors.isEmpty()) {
            var error = errors.iterator().next();
            throw ApiException.invalid(error.getMessage(), error.getPropertyPath().toString());
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failure(long id, Failure input) {
        Row row = locked(id);
        requireActive(row, input.workerId(), input.attemptNo());
        tasks.fail(id, input.code(), input.message(), input.retryable(), ATTEMPTS_PER_CYCLE);
    }

    void retry(long id) {
        if (tasks.retry(id) == 0) {
            find(id);
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_RETRYABLE", "此任务当前无法重新排队", null);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListPage list(Status status, Purpose purpose, int page, int pageSize) {
        if (page < 1) throw ApiException.invalid("页码最小为 1", "page");
        if (pageSize < 1 || pageSize > 100) {
            throw ApiException.invalid("每页条数必须在 1 至 100 之间", "pageSize");
        }
        var items = tasks.list(status, purpose, pageSize, (page - 1L) * pageSize);
        return new ListPage(items, tasks.count(status, purpose), page, pageSize);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long id) {
        Row row = find(id);
        var payload = row.resultJson() == null ? null : json.readTree(row.resultJson());
        if (payload != null && payload.path("protocolVersion").asInt(1) == 2) {
            return deliveryDetail(row, json.treeToValue(payload, DeliveryResult.Stored.class));
        }
        var stored = payload == null ? new StoredResult(null, List.of(), List.of())
                : json.treeToValue(payload, StoredResult.class);
        var pages = tasks.pages(id).stream().map(page ->
                new PageResult(page.pageNo(), page.status(), page.text(), json.readValue(page.hintsJson(), HINTS)))
                .toList();
        return new Detail(id, row.documentId(), row.purpose(), row.status(), row.originalFilename(),
                row.createdAt(), row.attemptNo(), document(row), pages, stored.candidate(), stored.sources(),
                stored.hints(), row.confirmedOrderId(), row.failureCode(), row.failureMessage(),
                row.retryable(), row.parserVersion(), null, null, null);
    }

    private Detail deliveryDetail(Row row, DeliveryResult.Stored stored) {
        var orderLines = orders.lines(row.orderId());
        var recommendations = stored.delivery().lines().stream()
                .map(line -> DeliveryRecommendations.recommend(line, orderLines)).toList();
        return new Detail(row.id(), row.documentId(), row.purpose(), row.status(), row.originalFilename(),
                row.createdAt(), row.attemptNo(), document(row), stored.pages(), null, List.of(),
                stored.hints(), row.confirmedOrderId(), row.failureCode(), row.failureMessage(),
                row.retryable(), row.parserVersion(), stored.protocolVersion(), stored.delivery(), recommendations);
    }

    private static DocumentView document(Row row) {
        return new DocumentView(row.documentId(), row.purpose(), row.orderId(), row.originalFilename(),
                row.mimeType(), row.byteSize(), row.sha256(), row.uploadedBy(), row.uploadedAt(), row.id(), row.status());
    }

    private Row locked(long id) {
        if (tasks.lock(id) == null) throw ApiException.missing("处理任务不存在");
        return find(id);
    }

    private Row find(long id) {
        Row row = tasks.row(id);
        if (row == null) throw ApiException.missing("处理任务不存在");
        return row;
    }

    private static void validateResult(Row row, Result result) {
        boolean order = row.purpose() == Purpose.ORDER_IMPORT;
        if (order != (result.candidate() != null) || (!order && !result.sources().isEmpty())) {
            throw ApiException.invalid("候选值与材料用途不符", "candidate");
        }
        if (order && result.pages().stream().noneMatch(page -> page.status() == PageStatus.TEXT)) {
            throw ApiException.invalid("采购单缺少可用文本层", "pages");
        }
        boolean image = row.mimeType().startsWith("image/");
        if (image && (result.pages().size() != 1 || result.pages().getFirst().status() != PageStatus.NEEDS_OCR)) {
            throw ApiException.invalid("签收图片应登记为一页待 OCR", "pages");
        }
        for (int index = 0; index < result.pages().size(); index++) {
            PageResult page = result.pages().get(index);
            if (page.pageNo() != index + 1) {
                throw ApiException.invalid("页面必须从 1 开始顺序编号", "pages");
            }
            if (page.status() != PageStatus.TEXT && page.status() != PageStatus.NEEDS_OCR) {
                throw ApiException.invalid("当前协议不支持此页面状态", "pages");
            }
            boolean hasText = page.text() != null && !page.text().isBlank();
            if ((page.status() == PageStatus.TEXT) != hasText) {
                throw ApiException.invalid("页面状态与提取文字不符", "pages");
            }
        }
        for (Source source : result.sources()) {
            List<Double> box = source.bbox();
            if (source.pageNo() > result.pages().size()
                    || result.pages().get(source.pageNo() - 1).status() != PageStatus.TEXT
                    || box.stream().anyMatch(value -> !Double.isFinite(value))
                    || box.get(0) > box.get(2) || box.get(1) > box.get(3)) {
                throw ApiException.invalid("字段来源与解析页面不符", "sources");
            }
        }
    }

    private static boolean sameAttempt(Row row, String worker, long attempt) {
        return row.attemptNo() == attempt && worker.equals(row.workerId());
    }

    private static boolean replayed(Row row, String worker, long attempt, String hash) {
        return row.status() == Status.SUCCEEDED && sameAttempt(row, worker, attempt)
                && Objects.equals(row.resultHash(), hash);
    }

    private static void requireActive(Row row, String worker, long attempt) {
        if (row.status() != Status.RUNNING || !row.leaseLive() || !sameAttempt(row, worker, attempt)) throw stale();
    }

    private static ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "STALE_ATTEMPT", "此处理尝试已失效", null);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
