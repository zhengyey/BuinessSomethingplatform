package labreview;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFlowTest extends ApiIntegrationTest {
    String orderPath;
    long glovesId;
    long filtersId;

    @BeforeEach
    void createFixture() throws Exception {
        client.login();
        orderPath = createOrder();
        var lines = client.get(orderPath).expect(200).json().get("lines");
        glovesId = lines.get(0).get("id").asLong();
        filtersId = lines.get(1).get("id").asLong();
    }

    @Test
    void aggregatesTwoBatchesAndKeepsEachHistorySeparate() throws Exception {
        assertThat(history(glovesId).json().size()).isZero();
        receiveFirstTwoBatches();
        var gloves = history(glovesId).json();
        var filters = history(filtersId).json();
        assertThat(gloves.size()).isEqualTo(2);
        assertThat(filters.size()).isEqualTo(1);
        assertThat(gloves.get(0).get("deliveryNoteNo").asString()).isEqualTo("DN-001");
        assertThat(gloves.get(0).get("deliveredQty").asInt()).isEqualTo(6);
        assertThat(gloves.get(0).get("acceptedQty").asInt()).isEqualTo(5);
        assertThat(gloves.get(0).get("note").asString()).isEqualTo("短 1 箱");
        assertThat(gloves.get(0).get("confirmedBy").asString()).isEqualTo("test-receiver");
        assertThat(gloves.get(0).get("receivedOn").asString()).isEqualTo("2026-11-18");
        assertThat(gloves.get(1).get("deliveryNoteNo").asString()).isEqualTo("DN-002");
        assertThat(gloves.get(1).get("acceptedQty").asInt()).isEqualTo(5);
        assertThat(filters.get(0).get("deliveryNoteNo").asString()).isEqualTo("DN-002");
        assertThat(filters.get(0).get("acceptedQty").asInt()).isEqualTo(3);
    }

    @Test
    void rejectsCrossOrderAndDuplicateLines() throws Exception {
        var otherPath = createOrder();
        var foreignId = client.get(otherPath).expect(200).json().get("lines").get(0).get("id").asLong();
        post("DN-X", line(foreignId, 1)).expect(400);
        post("DN-X", line(glovesId, 1), line(glovesId, 1)).expect(400);
        client.get(orderPath + "/lines/" + foreignId + "/receipts").expect(404);
        assertCounts(0, 0);
    }

    @Test
    void rejectsOverReceiptAndRollsBackValidFirstLineWhenSecondLineConflicts() throws Exception {
        post("DN-001", line(filtersId, 3)).expect(201);
        var result = post("DN-X", line(glovesId, 2), line(filtersId, 3)).expect(409).json();
        assertThat(result.get("message").asString()).contains("滤芯", "2", "盒");
        assertCounts(1, 1);
        post("DN-Y", line(glovesId, 2), line(filtersId, 0)).expect(400);
        assertCounts(1, 1);
        assertState("PARTIAL", 0, 10, 3, 2);
    }

    @Test
    void acceptsUnspecifiedDeliveryQuantityAndShowsCompleteAfterFinalBatch() throws Exception {
        receiveFirstTwoBatches();
        post("DN-003", line(filtersId, 2)).expect(201);
        assertState("COMPLETE", 10, 0, 5, 0);
        assertThat(history(filtersId).json().get(1).get("deliveredQty").isNull()).isTrue();
    }

    @Test
    void concurrentReceiptsCannotBothClaimTheSameRemainingQuantity() throws Exception {
        var other = new Client();
        other.login();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> post("DN-A", line(glovesId, 7)).status());
            var two = executor.submit(() -> other.post(orderPath + "/receipts",
                    receipt("DN-B", line(glovesId, 7))).status());
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
            assertState("PARTIAL", 7, 3, 0, 5);
            assertCounts(1, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void receiveFirstTwoBatches() throws Exception {
        post("DN-001", Map.of("orderLineId", glovesId, "acceptedQty", 5,
                "deliveredQty", 6, "note", "短 1 箱")).expect(201);
        assertState("PARTIAL", 5, 5, 0, 5);
        post("DN-002", Map.of("orderLineId", glovesId, "acceptedQty", 5, "deliveredQty", 5),
                Map.of("orderLineId", filtersId, "acceptedQty", 3, "deliveredQty", 3)).expect(201);
        assertState("PARTIAL", 10, 0, 3, 2);
    }

    private Response post(String number, Object... lines) throws Exception {
        return client.post(orderPath + "/receipts", receipt(number, lines));
    }

    private static Map<String, Object> receipt(String number, Object... lines) {
        return Map.of("deliveryNoteNo", number,
                "receivedOn", number.equals("DN-002") ? "2026-11-19" : "2026-11-18",
                "lines", List.of(lines));
    }

    private static Map<String, Object> line(long id, int accepted) {
        return Map.of("orderLineId", id, "acceptedQty", accepted);
    }

    private Response history(long id) throws Exception {
        return client.get(orderPath + "/lines/" + id + "/receipts").expect(200);
    }

    private void assertCounts(int receipts, int lines) {
        long orderId = Long.parseLong(orderPath.substring(orderPath.lastIndexOf('/') + 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM receipt_line JOIN receipt ON receipt_line.receipt_id = receipt.id
                WHERE receipt.order_id = ?
                """, Integer.class, orderId)).isEqualTo(lines);
    }

    private void assertState(String status, int gloves, int glovesRemaining, int filters, int filtersRemaining)
            throws Exception {
        var detail = client.get(orderPath).expect(200).json();
        assertThat(detail.get("status").asString()).isEqualTo(status);
        var lines = detail.get("lines");
        assertThat(lines.get(0).get("acceptedQty").asInt()).isEqualTo(gloves);
        assertThat(lines.get(0).get("remainingQty").asInt()).isEqualTo(glovesRemaining);
        assertThat(lines.get(1).get("acceptedQty").asInt()).isEqualTo(filters);
        assertThat(lines.get(1).get("remainingQty").asInt()).isEqualTo(filtersRemaining);
    }
}
