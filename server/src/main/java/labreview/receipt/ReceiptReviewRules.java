package labreview.receipt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import labreview.api.ApiException;
import labreview.order.OrderLine;
import labreview.receipt.ReceiptModels.LineInput;
import labreview.receipt.ReceiptReviewModels.*;
import labreview.task.DeliveryRecommendations.Recommendation;
import labreview.task.DeliveryRecommendations.Status;
import labreview.task.DeliveryResult;
import labreview.task.DeliveryResult.Field;
import labreview.task.ReceiptReviewSource.Source;
import org.springframework.http.HttpStatus;

final class ReceiptReviewRules {
    private final Source source;
    private final Map<Integer, DeliveryResult.Page> pages;
    private final Map<String, DeliveryResult.Line> originals;
    private final Map<String, Recommendation> recommendations;
    private final Map<Long, OrderLine> orderLines;

    ReceiptReviewRules(Source source, List<OrderLine> orderLines) {
        this.source = source;
        this.pages = index(source.pages(), DeliveryResult.Page::pageNo);
        this.originals = index(source.delivery().lines(), DeliveryResult.Line::lineKey);
        this.recommendations = index(source.recommendations(), Recommendation::lineKey);
        this.orderLines = index(orderLines, OrderLine::id);
    }

    Edit initial() {
        return new Edit(null, null, null, source.pages().stream()
                .map(page -> new PageEdit(page.pageNo(), PageDisposition.PENDING, null)).toList(),
                source.delivery().lines().stream().map(line -> new LineEdit(line.lineKey(), line.pageNo(),
                        suggested(line.lineKey()), line.unit(), line.deliveredQty(), line.acceptedQty(),
                        Disposition.PENDING, null, null, Map.of())).toList(), null, null, null, null);
    }

    void validateReferences(Edit edit) {
        var usedPages = new HashSet<Integer>();
        for (var page : edit.pages()) {
            if (!pages.containsKey(page.pageNo()) || !usedPages.add(page.pageNo())) {
                throw ApiException.invalid("页码无效或重复", pageField(page.pageNo()));
            }
        }
        var usedLines = new HashSet<String>();
        for (var line : edit.lines()) {
            String field = lineField(line, "lineKey");
            var original = originals.get(line.lineKey());
            if (!usedLines.add(line.lineKey()) || !pages.containsKey(line.pageNo())
                    || (original != null && !original.pageNo().equals(line.pageNo()))
                    || (original == null && !line.lineKey().matches("manual-[0-9a-f-]{36}"))) {
                throw ApiException.invalid("来源行、补录键或页码无效", field);
            }
            if (line.orderLineId() != null && !orderLines.containsKey(line.orderLineId())) {
                throw ApiException.invalid("所选物料不属于当前采购单", lineField(line, "orderLineId"));
            }
            validateEvidence(line);
        }
        for (var original : originals.values()) {
            if (!usedLines.contains(original.lineKey())) {
                throw ApiException.invalid("请保留来源行并选择处置", "line:" + original.lineKey());
            }
        }
    }

    private void validateEvidence(LineEdit line) {
        if (line.selectedEvidence() == null) return;
        for (var selected : line.selectedEvidence().entrySet()) {
            var field = selected.getKey();
            int index = selected.getValue();
            Integer quantity = switch (field) {
                case acceptedQty -> line.acceptedQty();
                case deliveredQty -> line.deliveredQty();
                default -> null;
            };
            var evidence = index < source.delivery().evidence().size()
                    ? source.delivery().evidence().get(index) : null;
            if (quantity == null || evidence == null || !evidence.lineKey().equals(line.lineKey())
                    || evidence.field() != field || evidence.value() == null || !evidence.value().isIntegralNumber()
                    || !evidence.value().canConvertToInt()
                    || evidence.value().asInt() != quantity) {
                throw ApiException.invalid("所选证据与核对数量不一致", lineField(line, field.name()));
            }
        }
    }

    List<LineInput> confirmedLines(Edit edit) {
        validateReferences(edit);
        ReceiptIdentity.noteKey(edit.noteStatus(), edit.deliveryNoteNo());
        if (edit.noteStatus() == ReceiptModels.NoteStatus.UNKNOWN && blank(edit.unknownNoteReason())) {
            throw ApiException.invalid("请填写单号无法核实的原因", "unknownNoteReason");
        }
        if (edit.receivedOn() == null) throw ApiException.invalid("请选择到货日期", "receivedOn");
        var pageEdits = index(edit.pages(), PageEdit::pageNo);
        var pageLines = edit.lines().stream().collect(Collectors.groupingBy(LineEdit::pageNo));
        for (var page : source.pages()) {
            var update = pageEdits.get(page.pageNo());
            String field = pageField(page.pageNo());
            if (update == null || update.disposition() == PageDisposition.PENDING) {
                throw ApiException.invalid("请处理第 " + page.pageNo() + " 页", field);
            }
            var lines = pageLines.getOrDefault(page.pageNo(), List.of());
            if (update.disposition() == PageDisposition.NO_DETAILS) {
                if (blank(update.reason()) || lines.stream().anyMatch(line -> line.disposition() != Disposition.EXCLUDED)) {
                    throw ApiException.invalid("本页无明细时请填写原因，并排除本页所有行", field);
                }
            } else if (lines.stream().noneMatch(line -> line.disposition() == Disposition.POST
                    || line.disposition() == Disposition.ZERO)) {
                throw ApiException.invalid("请在本页补录实收或未收明细，或说明本页无收货明细", field);
            }
        }

        Map<Long, List<LineEdit>> groups = new LinkedHashMap<>();
        for (var line : edit.lines()) {
            validateDisposition(line);
            if (line.disposition() == Disposition.POST) {
                groups.computeIfAbsent(line.orderLineId(), ignored -> new ArrayList<>()).add(line);
            }
        }
        if (groups.isEmpty() && blank(edit.noReceiptReason())) {
            throw ApiException.invalid("请填写无入账结案原因", "noReceiptReason");
        }
        return groups.entrySet().stream().map(entry -> aggregate(entry.getKey(), entry.getValue())).toList();
    }

    private void validateDisposition(LineEdit line) {
        switch (line.disposition()) {
            case PENDING -> throw ApiException.invalid("请选择该行处置", lineField(line, "disposition"));
            case EXCLUDED -> requireReason(line, "请说明排除原因");
            case ZERO -> {
                if (!Integer.valueOf(0).equals(line.acceptedQty())) {
                    throw ApiException.invalid("未收行须明确填写实收 0", lineField(line, "acceptedQty"));
                }
                if (line.orderLineId() != null) validateUnit(line);
                requireReason(line, "请说明未收原因");
            }
            case POST -> {
                if (line.acceptedQty() == null || line.acceptedQty() < 1) {
                    throw ApiException.invalid("请输入核实后的正整数实收量", lineField(line, "acceptedQty"));
                }
                validateUnit(line);
                var original = originals.get(line.lineKey());
                if (original != null && (!original.issues().isEmpty()
                        || !Objects.equals(suggested(line.lineKey()), line.orderLineId())
                        || !Objects.equals(strip(original.unit()), strip(line.unit()))
                        || !Objects.equals(original.deliveredQty(), line.deliveredQty())
                        || !Objects.equals(original.acceptedQty(), line.acceptedQty()))) {
                    requireReason(line, "请说明异常处置或人工修正原因");
                }
            }
        }
    }

    private void validateUnit(LineEdit line) {
        var orderLine = orderLines.get(line.orderLineId());
        if (orderLine == null) throw ApiException.invalid("请选择订单物料", lineField(line, "orderLineId"));
        if (!orderLine.unit().equals(strip(line.unit()))) {
            throw ApiException.invalid("核实单位须与订单单位一致：" + orderLine.unit(), lineField(line, "unit"));
        }
    }

    private LineInput aggregate(long id, List<LineEdit> lines) {
        long accepted = lines.stream().mapToLong(LineEdit::acceptedQty).sum();
        var field = lineField(lines.getFirst(), "acceptedQty");
        if (accepted > Integer.MAX_VALUE) {
            throw ApiException.invalid("该物料实收数合计超出整数范围", field);
        }
        Integer delivered = null;
        if (lines.stream().allMatch(line -> line.deliveredQty() != null)) {
            long sum = lines.stream().mapToLong(LineEdit::deliveredQty).sum();
            if (sum > Integer.MAX_VALUE) {
                throw ApiException.invalid("该物料送货数合计超出整数范围", lineField(lines.getFirst(), "deliveredQty"));
            }
            delivered = (int) sum;
        }
        return new LineInput(id, delivered, (int) accepted, lines.size() == 1 ? lines.getFirst().note() : null);
    }

    private Long suggested(String key) {
        var recommendation = recommendations.get(key);
        return recommendation != null && recommendation.status() == Status.CODE_MATCH
                && recommendation.candidates().size() == 1 ? recommendation.candidates().getFirst().orderLineId() : null;
    }

    private static void requireReason(LineEdit line, String message) {
        if (blank(line.reason())) throw ApiException.invalid(message, lineField(line, "reason"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static String pageField(int number) { return "page:" + number; }
    private static String lineField(LineEdit line, String field) { return "line:" + line.lineKey() + ":" + field; }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
