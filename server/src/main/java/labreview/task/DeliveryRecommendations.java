package labreview.task;

import java.util.List;
import labreview.order.OrderLine;
import labreview.task.DeliveryResult.Line;

public final class DeliveryRecommendations {
    public enum Status { CODE_MATCH, NAME_MATCH, REVIEW, UNMATCHED }
    public record Candidate(long orderLineId, int lineNo, String itemCode, String itemName, String specification, String unit) {}
    public record Recommendation(String lineKey, Status status, String reason, List<Candidate> candidates) {}

    private DeliveryRecommendations() {}

    static Recommendation recommend(Line line, List<OrderLine> orderLines) {
        if (present(line.itemCode())) {
            var sameCode = orderLines.stream().filter(order -> equal(line.itemCode(), order.itemCode())).toList();
            if (sameCode.size() > 1) return result(line, Status.REVIEW, "当前订单有多行使用此编码", sameCode);
            var similarCodes = orderLines.stream().filter(order ->
                    comparableCode(line.itemCode()).equals(comparableCode(order.itemCode()))).toList();
            if (similarCodes.size() > sameCode.size()) {
                return result(line, Status.REVIEW, "编码可能混淆字母 O 和数字 0，请核对原件", similarCodes);
            }
            if (sameCode.size() == 1) {
                if (compatible(line, sameCode.getFirst())) {
                    return result(line, Status.CODE_MATCH, "编码唯一且规格、单位一致", sameCode);
                }
                return result(line, Status.REVIEW, "编码相同，规格或单位缺失或不一致", sameCode);
            }
        }

        var sameName = orderLines.stream().filter(order -> equal(line.itemName(), order.itemName())).toList();
        if (sameName.isEmpty()) {
            return result(line, Status.UNMATCHED, "当前订单没有匹配的编码或物料名称", List.of());
        }
        if (present(line.itemCode())) {
            return result(line, Status.REVIEW, "物料名称相同，编码未匹配", sameName);
        }
        var complete = sameName.stream().filter(order -> compatible(line, order)).toList();
        if (complete.size() == 1) {
            return result(line, Status.NAME_MATCH, "名称、规格及单位匹配，请人工确认", complete);
        }
        if (complete.size() > 1) {
            return result(line, Status.REVIEW, "当前订单有多行的名称、规格及单位一致", complete);
        }
        return result(line, Status.REVIEW, "物料名称相同，规格或单位缺失或不一致", sameName);
    }

    private static Recommendation result(Line line, Status status, String reason, List<OrderLine> orderLines) {
        return new Recommendation(line.lineKey(), status, reason, orderLines.stream()
                .map(order -> new Candidate(order.id(), order.lineNo(), order.itemCode(), order.itemName(),
                        order.specification(), order.unit()))
                .toList());
    }

    private static boolean compatible(Line line, OrderLine order) {
        return equal(line.specification(), order.specification()) && equal(line.unit(), order.unit());
    }

    private static boolean equal(String a, String b) {
        return present(a) && present(b) && a.strip().equals(b.strip());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String comparableCode(String code) {
        return code.strip().replace('O', '0').replace('o', '0');
    }
}
