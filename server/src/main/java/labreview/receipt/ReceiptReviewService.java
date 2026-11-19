package labreview.receipt;

import jakarta.validation.Validator;
import java.util.List;
import labreview.api.ApiException;
import labreview.order.OrderLine;
import labreview.order.OrderMapper;
import labreview.receipt.ReceiptModels.CreateReceipt;
import labreview.receipt.ReceiptReviewModels.*;
import labreview.task.ReceiptReviewSource;
import labreview.task.ReceiptReviewSource.Source;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
class ReceiptReviewService {
    private static final ObjectMapper EDIT_JSON = JsonMapper.builder()
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

    private final ReceiptReviewMapper reviews;
    private final ReceiptReviewSource sources;
    private final OrderMapper orders;
    private final ReceiptService receipts;
    private final ReceiptMapper ledger;
    private final ObjectMapper json;
    private final Validator validator;

    ReceiptReviewService(ReceiptReviewMapper reviews, ReceiptReviewSource sources, OrderMapper orders,
                         ReceiptService receipts, ReceiptMapper ledger, ObjectMapper json, Validator validator) {
        this.reviews = reviews;
        this.sources = sources;
        this.orders = orders;
        this.receipts = receipts;
        this.ledger = ledger;
        this.json = json;
        this.validator = validator;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View open(long orderId, long documentId, String username) {
        var existing = reviews.byDocument(orderId, documentId);
        if (existing != null) return view(existing);
        var lines = orders.lines(orderId);
        var source = sources.load(orderId, documentId, lines);
        reviews.open(orderId, documentId, source.taskId(), source.resultHash(),
                json.writeValueAsString(new ReceiptReviewRules(source, lines).initial()), username);
        return view(reviews.byDocument(orderId, documentId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(long orderId, long id) {
        return view(required(reviews.find(orderId, id)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReceiptModels.Detail receipt(long orderId, long receiptId) {
        var header = ledger.header(orderId, receiptId);
        if (header == null) throw ApiException.missing("收货记录不存在");
        Long reviewId = reviews.byReceipt(orderId, receiptId);
        return new ReceiptModels.Detail(header, ledger.details(receiptId), reviewId == null ? "MANUAL" : "MATERIAL",
                reviewId == null ? null : view(required(reviews.find(orderId, reviewId))), ledger.adjustments(receiptId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View save(long orderId, long id, JsonNode body, String username) {
        var row = required(reviews.lock(orderId, id));
        checkWritable(row, version(body));
        var orderLines = orders.lines(orderId);
        var source = current(row, orderLines);
        var date = body.path("receivedOn");
        if (!date.isMissingNode() && !date.isNull() && (!date.isString()
                || !date.asString().matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}"))) {
            throw ApiException.invalid("到货日期须使用 YYYY-MM-DD", "receivedOn");
        }
        Edit edit;
        try {
            var fields = EDIT_JSON.createObjectNode();
            body.properties().forEach(entry -> {
                if (!entry.getKey().equals("version")) fields.set(entry.getKey(), entry.getValue());
            });
            edit = EDIT_JSON.treeToValue(fields, Edit.class);
        } catch (JacksonException error) {
            throw ApiException.invalid("请检查草稿格式、整数数量和日期", null);
        }
        validate(edit);
        new ReceiptReviewRules(source, orderLines).validateReferences(edit);
        if (reviews.save(orderId, id, row.version(), json.writeValueAsString(edit), username) != 1) {
            throw conflict("REVIEW_VERSION_CONFLICT", "草稿已更新，请查看服务器版本");
        }
        return view(required(reviews.find(orderId, id)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome confirm(long orderId, long id, JsonNode body, String username) {
        var row = required(reviews.lock(orderId, id));
        if (row.status() != Status.DRAFT) return new Outcome(row.status(), id, row.receiptId());
        checkWritable(row, version(body));
        if (orders.lockOrder(orderId) == null) throw ApiException.missing("采购单不存在");
        var orderLines = orders.lines(orderId);
        var source = current(row, orderLines);
        var edit = readEdit(row);
        var rules = new ReceiptReviewRules(source, orderLines);
        var lines = rules.confirmedLines(edit);
        Long receiptId = null;
        if (!lines.isEmpty()) {
            var input = new CreateReceipt(edit.deliveryNoteNo(), edit.receivedOn(), lines, edit.noteStatus(),
                    edit.unknownNoteReason(), edit.duplicateReviewReason(), edit.candidateToken());
            validate(input);
            receiptId = receipts.confirm(orderId, input, username, null, row.documentId());
        }
        var status = receiptId == null ? Status.CLOSED_NO_RECEIPT : Status.POSTED;
        if (reviews.finish(id, status, receiptId, json.writeValueAsString(source), username) != 1) {
            throw conflict("REVIEW_FROZEN", "核对记录已结案");
        }
        return new Outcome(status, id, receiptId);
    }

    private View view(Row row) {
        var orderLines = orders.lines(row.orderId());
        var source = row.status() == Status.DRAFT ? sources.load(row.orderId(), row.documentId(), orderLines)
                : json.readValue(row.snapshotJson(), Source.class);
        return new View(row.id(), row.orderId(), row.version(), row.status(), row.updatedAt(), row.updatedBy(),
                row.confirmedAt(), row.confirmedBy(), row.receiptId(), !sameResult(row, source),
                readEdit(row), source, orderLines);
    }

    private Edit readEdit(Row row) {
        return json.readValue(row.reviewJson(), Edit.class);
    }

    private Source current(Row row, List<OrderLine> orderLines) {
        var source = sources.load(row.orderId(), row.documentId(), orderLines);
        if (!sameResult(row, source)) {
            throw conflict("REVIEW_SOURCE_CHANGED", "解析结果已变更，请重新加载并对照最新解析结果");
        }
        return source;
    }

    private static boolean sameResult(Row row, Source source) {
        return row.taskId() == source.taskId() && row.resultHash().equals(source.resultHash());
    }

    private static Row required(Row row) {
        if (row == null) throw ApiException.missing("核对记录不存在");
        return row;
    }

    private static long version(JsonNode body) {
        var value = body.path("version");
        if (!body.isObject() || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) {
            throw ApiException.invalid("草稿版本无效", "version");
        }
        return value.asLong();
    }

    private static void checkWritable(Row row, long version) {
        if (row.status() != Status.DRAFT) throw conflict("REVIEW_FROZEN", "核对记录已结案");
        if (row.version() != version) throw conflict("REVIEW_VERSION_CONFLICT", "草稿已更新，请查看服务器版本");
    }

    private void validate(Object value) {
        var errors = validator.validate(value);
        if (!errors.isEmpty()) {
            var error = errors.iterator().next();
            throw ApiException.invalid(error.getMessage(), error.getPropertyPath().toString());
        }
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, null);
    }
}
