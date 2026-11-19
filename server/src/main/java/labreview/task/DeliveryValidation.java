package labreview.task;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import labreview.api.ApiException;
import labreview.task.DeliveryResult.*;
import labreview.task.TaskModels.PageStatus;
import labreview.task.TaskModels.Purpose;
import labreview.task.TaskModels.Row;
import tools.jackson.databind.JsonNode;

final class DeliveryValidation {
    private static final Set<PageStatus> STATUSES = Set.of(PageStatus.EXTRACTED, PageStatus.REVIEW, PageStatus.PAGE_FAILED);
    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
    private static final Pattern LINE_KEY = Pattern.compile("p([1-9][0-9]*)-r[1-9][0-9]*");
    private static final String QUANTITY = "\\s*[:=]?\\s*([0-9]+)";
    private static final String NUMBER_LABEL = "\\s*数(?:\\s*量)?";
    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern DELIVERED = Pattern.compile(
            "(?:送\\s*货(?:" + NUMBER_LABEL + ")?|发\\s*货\\s*数\\s*量)" + QUANTITY, UNICODE);
    private static final Pattern ACCEPTED = Pattern.compile(
            "(?:实\\s*收(?:" + NUMBER_LABEL + ")?|验\\s*收\\s*数\\s*量|签\\s*收\\s*数\\s*量)" + QUANTITY,
            UNICODE);
    private static final Pattern NON_ACTUAL_PREFIX = Pattern.compile(
            "(?:未|无|非|预\\s*计|应|拟|计\\s*划|暂\\s*定)\\s*[:=]?\\s*$", UNICODE);
    private static final Pattern NON_LITERAL_SUFFIX = Pattern.compile(
            "\\s*(?:[+\\-−–—*/÷×~～万千百亿]|[.,、至到]\\s*[0-9])", UNICODE);

    private DeliveryValidation() {}

    static void validate(Row row, DeliveryResult result) {
        if (row.purpose() != Purpose.DELIVERY_NOTE) {
            throw ApiException.invalid("签收解析协议仅适用于签收材料", "protocolVersion");
        }
        if (result.documentId() != row.documentId()) {
            throw ApiException.invalid("解析材料 ID 与当前任务不符", "documentId");
        }
        validatePages(result.pages(), row.mimeType().startsWith("image/"));
        var lines = new HashMap<String, Line>();
        for (Line line : result.delivery().lines()) {
            var key = LINE_KEY.matcher(line.lineKey());
            if (line.pageNo() > result.pages().size() || !key.matches()
                    || !key.group(1).equals(line.pageNo().toString())
                    || lines.putIfAbsent(line.lineKey(), line) != null) {
                throw ApiException.invalid("签收行键重复或与页号不符", "delivery.lines");
            }
        }
        var quantities = new HashMap<QuantityField, Set<Integer>>();
        var localRegions = new HashMap<RegionalClaim, List<List<Double>>>();
        var modelClaims = new ArrayList<Evidence>();
        for (Evidence evidence : result.delivery().evidence()) {
            validateEvidence(evidence, lines);
            if (!evidence.supporting()) continue;
            if (evidence.method() == Method.DOUBAO) {
                modelClaims.add(evidence);
                continue;
            }
            if (evidence.precision() == Precision.REGION) {
                localRegions.computeIfAbsent(RegionalClaim.of(evidence), unused -> new ArrayList<>())
                        .add(evidence.bbox());
            }
            var label = switch (evidence.field()) {
                case deliveredQty -> DELIVERED;
                case acceptedQty -> ACCEPTED;
                default -> null;
            };
            if (label != null) {
                quantities.computeIfAbsent(new QuantityField(evidence.lineKey(), evidence.field()),
                        unused -> new HashSet<>()).addAll(parseQuantities(evidence, label));
            }
        }
        for (Evidence model : modelClaims) validateModelClaim(model, localRegions);
        for (Line line : lines.values()) {
            validateQuantity(line, Field.deliveredQty, line.deliveredQty(), quantities);
            validateQuantity(line, Field.acceptedQty, line.acceptedQty(), quantities);
        }
    }

    private static void validatePages(List<Page> pages, boolean image) {
        if (image && pages.size() != 1) {
            throw ApiException.invalid("签收图片必须登记为一页", "pages");
        }
        int doubaoCalls = 0;
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            if (page.pageNo() != index + 1 || !STATUSES.contains(page.status())
                    || !Double.isFinite(page.width()) || !Double.isFinite(page.height())
                    || !ROTATIONS.contains(page.rotation())
                    || new HashSet<>(page.methods()).size() != page.methods().size()
                    || (page.ocrCalls() == 1 && !page.methods().contains(Method.PADDLE_OCR))
                    || (page.doubaoCalls() == 1 && !page.methods().contains(Method.DOUBAO))) {
                throw ApiException.invalid("页号、状态、尺寸、方向或识别方法无效", "pages");
            }
            doubaoCalls += page.doubaoCalls();
        }
        if (doubaoCalls > 3) {
            throw ApiException.invalid("每份签收材料最多调用三次模型", "pages");
        }
    }

    private static void validateEvidence(Evidence evidence, Map<String, Line> lines) {
        Line line = lines.get(evidence.lineKey());
        if (line == null || !line.pageNo().equals(evidence.pageNo())) {
            throw ApiException.invalid("字段依据与签收行或原件页号不符", "delivery.evidence");
        }
        var value = evidence.value();
        if (value != null && !value.isNull()
                && !(value.isString() && !value.asString().isEmpty() && value.asString().length() <= 120)
                && !(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0)) {
            throw ApiException.invalid("字段依据的值必须为文字、非负整数或空值", "delivery.evidence");
        }
        var box = evidence.bbox();
        if (evidence.precision() == Precision.PAGE ? box != null : !validRegion(box)) {
            throw ApiException.invalid("字段依据坐标超出页面或与定位精度不符", "delivery.evidence");
        }
    }

    private static boolean validRegion(List<Double> box) {
        return box != null && box.stream().allMatch(value -> Double.isFinite(value) && value >= 0 && value <= 1)
                && box.get(0) < box.get(2) && box.get(1) < box.get(3);
    }

    private static Set<Integer> parseQuantities(Evidence evidence, Pattern label) {
        var value = evidence.value();
        String raw = Normalizer.normalize(evidence.rawText(), Normalizer.Form.NFKC);
        var matches = label.matcher(raw);
        var quantities = new HashSet<Integer>();
        while (matches.find()) {
            if (NON_ACTUAL_PREFIX.matcher(raw).region(0, matches.start()).find()
                    || NON_LITERAL_SUFFIX.matcher(raw).region(matches.end(), raw.length()).lookingAt()
                    || matches.group(1).length() > 10) {
                continue;
            }
            try {
                quantities.add(Integer.parseInt(matches.group(1)));
            } catch (NumberFormatException ignored) {
                // Quantity printed in the document exceeds the supported integer range.
            }
        }
        if (value != null && value.isIntegralNumber() && quantities.contains(value.asInt())) return quantities;
        throw ApiException.invalid("数量依据必须包含对应的数量标签和相同的整数", "delivery.evidence");
    }

    private static void validateModelClaim(Evidence model, Map<RegionalClaim, List<List<Double>>> localRegions) {
        if (model.precision() != Precision.REGION || model.value() == null || model.value().isNull()
                || localRegions.getOrDefault(RegionalClaim.of(model), List.of()).stream()
                        .noneMatch(local -> overlaps(local, model.bbox()))) {
            throw ApiException.invalid("模型依据需要与同字段、同值的本地识别区域重合", "delivery.evidence");
        }
        var label = switch (model.field()) {
            case deliveredQty -> DELIVERED;
            case acceptedQty -> ACCEPTED;
            default -> null;
        };
        if (label != null && parseQuantities(model, label).size() != 1) {
            throw ApiException.invalid("模型依据包含多个不同数量", "delivery.evidence");
        }
    }

    private static boolean overlaps(List<Double> a, List<Double> b) {
        return Math.min(a.get(2), b.get(2)) > Math.max(a.get(0), b.get(0))
                && Math.min(a.get(3), b.get(3)) > Math.max(a.get(1), b.get(1));
    }

    private static void validateQuantity(Line line, Field field, Integer quantity,
                                         Map<QuantityField, Set<Integer>> quantities) {
        Set<Integer> values = quantities.getOrDefault(new QuantityField(line.lineKey(), field), Set.of());
        if (quantity != null && (values.size() != 1 || !values.contains(quantity))) {
            throw ApiException.invalid("数量缺少对应依据或存在冲突", "delivery.lines");
        }
        if (values.size() > 1 && !line.issues().contains("CONFLICT")) {
            throw ApiException.invalid("冲突数量必须标记为待核对", "delivery.lines");
        }
    }

    private record QuantityField(String lineKey, Field field) {}

    private record RegionalClaim(String lineKey, Field field, JsonNode value) {
        static RegionalClaim of(Evidence evidence) {
            return new RegionalClaim(evidence.lineKey(), evidence.field(), evidence.value());
        }
    }
}
