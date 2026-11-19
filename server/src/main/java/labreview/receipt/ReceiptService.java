package labreview.receipt;

import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import labreview.api.ApiException;
import labreview.api.InsertedId;
import labreview.order.OrderLine;
import labreview.order.OrderMapper;
import labreview.receipt.ReceiptModels.CreateReceipt;
import labreview.receipt.ReceiptModels.History;
import labreview.receipt.ReceiptModels.LineInput;
import labreview.receipt.ReceiptModels.AdjustmentInput;
import labreview.receipt.ReceiptModels.AdjustmentResult;
import labreview.receipt.ReceiptModels.NoteStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReceiptService {
    private final OrderMapper orders;
    private final ReceiptMapper receipts;
    private final ReceiptIdentity identities;
    private final ReceiptCommands commands;

    ReceiptService(OrderMapper orders, ReceiptMapper receipts, ReceiptIdentity identities, ReceiptCommands commands) {
        this.orders = orders;
        this.receipts = receipts;
        this.identities = identities;
        this.commands = commands;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long confirm(long orderId, CreateReceipt body, String username, String requestKey, Long documentId) {
        // Every receipt for this order takes the same lock before checking the latest balance.
        if (orders.lockOrder(orderId) == null) throw ApiException.missing("采购单不存在");

        var status = body.noteStatus() == null ? NoteStatus.VERIFIED : body.noteStatus();
        var input = new CreateReceipt(ReceiptIdentity.clean(body.deliveryNoteNo()), body.receivedOn(),
                body.lines().stream().map(line -> new LineInput(line.orderLineId(), line.deliveredQty(),
                        line.acceptedQty(), ReceiptIdentity.clean(line.note()))).toList(),
                status, ReceiptIdentity.clean(body.unknownNoteReason()), ReceiptIdentity.clean(body.duplicateReviewReason()),
                ReceiptIdentity.clean(body.candidateToken()));
        byte[] noteKey = ReceiptIdentity.noteKey(status, input.deliveryNoteNo());
        var hash = commands.hash(Arrays.asList(noteKey, status, input.receivedOn(),
                input.lines().stream().sorted(Comparator.comparing(LineInput::orderLineId)).toList(),
                input.unknownNoteReason(), input.duplicateReviewReason(), input.candidateToken()));
        var replay = commands.replay("POST", orderId, requestKey, hash, ReceiptModels.Created.class);
        if (replay != null) return replay.id();

        var orderLines = orders.lines(orderId).stream()
                .collect(Collectors.toMap(OrderLine::id, Function.identity()));
        var selected = new HashSet<Long>();
        for (int index = 0; index < input.lines().size(); index++) {
            var line = input.lines().get(index);
            var orderLine = orderLines.get(line.orderLineId());
            var field = "lines[" + index + "]";
            if (orderLine == null) {
                throw ApiException.invalid("所选物料不属于当前采购单", field + ".orderLineId");
            }
            if (!selected.add(line.orderLineId())) {
                throw ApiException.invalid("同一签收单内请勿重复选择物料行", field + ".orderLineId");
            }
        }
        var identity = identities.checkBeforePosting(orderId, input, documentId);
        for (int index = 0; index < input.lines().size(); index++) {
            var line = input.lines().get(index);
            var orderLine = orderLines.get(line.orderLineId());
            if (line.acceptedQty() > orderLine.remainingQty()) {
                throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_EXCEEDS_REMAINING",
                        "%s（%s）当前可收 %d %s".formatted(orderLine.itemName(), orderLine.itemCode(),
                                orderLine.remainingQty(), orderLine.unit()), "lines[" + index + "].acceptedQty",
                        Map.of("orderLineId", orderLine.id(), "remainingQty", orderLine.remainingQty()));
            }
        }

        var key = new InsertedId();
        receipts.insertReceipt(key, orderId, input, username, identity.supplier(), identity.noteKey(), identity.candidates());
        for (var line : input.lines()) {
            receipts.insertLine(key.getId(), line);
        }
        identities.claim(identity, key.getId());
        commands.store("POST", orderId, requestKey, hash, new ReceiptModels.Created(key.getId()));
        return key.getId();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdjustmentResult adjust(long orderId, long receiptId, long lineId, AdjustmentInput input,
                                   String username, String requestKey) {
        if (orders.lockOrder(orderId) == null || receipts.lockLine(orderId, receiptId, lineId) == null) {
            throw ApiException.missing("收货明细不存在");
        }
        if (requestKey == null) throw ApiException.invalid("缺少更正请求键", "Idempotency-Key");
        String reason = ReceiptIdentity.clean(input.reason());
        if (reason == null) throw ApiException.invalid("请输入更正原因", "reason");
        String hash = commands.hash(new AdjustmentInput(input.newAcceptedQty(), reason, input.expectedRevision()));
        var replay = commands.replay("ADJUST", lineId, requestKey, hash, AdjustmentResult.class);
        if (replay != null) return replay;
        var existing = receipts.target(lineId);
        if (existing.revision() != input.expectedRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "ADJUSTMENT_VERSION_CONFLICT", "该明细已更正，请查看最新版本",
                    "expectedRevision", Map.of("revision", existing.revision(), "effectiveAcceptedQty", existing.acceptedQty()));
        }
        if (input.newAcceptedQty() == existing.acceptedQty()) {
            throw ApiException.invalid("新实收数应与当前实收数不同", "newAcceptedQty");
        }
        var orderLine = orders.lines(orderId).stream().filter(line -> line.id() == existing.orderLineId()).findFirst()
                .orElseThrow(() -> ApiException.missing("订单物料行不存在"));
        long maximum = (long) orderLine.remainingQty() + existing.acceptedQty();
        if (input.newAcceptedQty() > maximum) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_EXCEEDS_REMAINING",
                    "当前该笔实收最多可更正为 %d %s".formatted(maximum, orderLine.unit()), "newAcceptedQty",
                    Map.of("maximumAcceptedQty", maximum, "remainingQty", orderLine.remainingQty()));
        }
        var key = new InsertedId();
        int nextRevision = existing.revision() + 1;
        receipts.adjust(key, lineId, nextRevision, existing.acceptedQty(), input.newAcceptedQty(), reason, username);
        var result = new AdjustmentResult(key.getId(), input.newAcceptedQty(), nextRevision);
        commands.store("ADJUST", lineId, requestKey, hash, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<History> history(long orderId, long lineId) {
        if (!orders.hasLine(orderId, lineId)) throw ApiException.missing("订单物料行不存在");
        return receipts.history(orderId, lineId);
    }
}
