package labreview;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptIntegrityTest extends ApiIntegrationTest {
    @Test
    void sharesOneUploadPerOrderAndHash() throws Exception {
        client.login();
        var other = new Client();
        other.login();
        String path = createOrder() + "/documents";
        var bytes = "%PDF-1.4\nphase-five".getBytes();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return client.upload(path, "first.pdf", bytes); });
            var second = pool.submit(() -> { start.await(); return other.upload(path, "renamed.pdf", bytes); });
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertThat(List.of(a.status(), b.status())).containsExactlyInAnyOrder(200, 202);
            assertThat(a.json().path("documentId")).isEqualTo(b.json().path("documentId"));
            assertThat(a.json().path("taskId")).isEqualTo(b.json().path("taskId"));
            assertThat(client.get(path).expect(200).json().size()).isEqualTo(1);
            var foreign = client.upload(createOrder() + "/documents", "first.pdf", bytes).expect(202);
            assertThat(foreign.json().path("documentId")).isNotEqualTo(a.json().path("documentId"));
        }
    }

    @Test
    void rejectsANormalizedDuplicateAndRecalculatesTheBalanceAfterAdjustment() throws Exception {
        client.login();
        String order = createOrder();
        String other = createOrder();
        String note = "DN-" + java.util.UUID.randomUUID();
        long firstLine = line(order);
        var posted = client.post(order + "/receipts", receipt(note, firstLine)).expect(201).json();
        var duplicate = client.post(other + "/receipts", receipt(" " + note.toUpperCase(java.util.Locale.ROOT) + " ", line(other)))
                .expect(409).json();
        assertThat(duplicate.path("code").asString()).isEqualTo("DUPLICATE_RECEIPT");
        client.post(order + "/receipts", receipt(note + "-2", firstLine)).expect(201);
        var history = client.get(order + "/lines/" + firstLine + "/receipts").expect(200).json();
        long receiptLine = history.get(0).path("id").asLong();
        String path = order + "/receipts/" + posted.path("id").asLong() + "/lines/" + receiptLine + "/adjustments";
        var edit = Map.of("newAcceptedQty", 4, "expectedRevision", 0, "reason", "原件复核");
        var first = client.postWithKey(path, edit, "adjust-one").expect(200).json();
        assertThat(client.postWithKey(path, edit, "adjust-one").expect(200).json()).isEqualTo(first);
        assertThat(first.path("effectiveAcceptedQty").asInt()).isEqualTo(4);
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt()).isEqualTo(1);
        var revised = client.get(order + "/lines/" + firstLine + "/receipts").expect(200).json().get(0);
        assertThat(revised.path("acceptedQty").asInt()).isEqualTo(4);
        assertThat(revised.path("originalAcceptedQty").asInt()).isEqualTo(5);
        client.postWithKey(path, Map.of("newAcceptedQty", 3, "expectedRevision", 1, "reason", "再核对"),
                "adjust-one").expect(409);
        client.postWithKey(path, Map.of("newAcceptedQty", 3, "expectedRevision", 0, "reason", "旧版本"),
                "adjust-stale").expect(409);
        client.postWithKey(path, Map.of("newAcceptedQty", 6, "expectedRevision", 1, "reason", "超出余量"),
                "adjust-over").expect(409);
        client.postWithKey(path, Map.of("newAcceptedQty", 3, "expectedRevision", 1, "reason", "再核对"),
                "adjust-two").expect(200);
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt()).isEqualTo(2);
        client.postWithKey(path, Map.of("newAcceptedQty", 0, "expectedRevision", 2, "reason", "最终未交付"),
                "adjust-zero").expect(200);
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt()).isEqualTo(5);
        assertThat(client.get(order + "/receipts/" + posted.path("id").asLong()).expect(200).json()
                .path("adjustments").size()).isEqualTo(3);
    }

    @Test
    void repeatedManualCommandReturnsTheOriginalIdInParallelAndRejectsChangedContent() throws Exception {
        client.login();
        var other = new Client();
        other.login();
        var order = createOrder();
        var note = "DN-replay";
        long material = line(order);
        var body = receipt(note, material);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return client.postWithKey(order + "/receipts", body, "same-key"); });
            var second = pool.submit(() -> { start.await(); return other.postWithKey(order + "/receipts", body, "same-key"); });
            start.countDown();
            assertThat(first.get().expect(201).json()).isEqualTo(second.get().expect(201).json());
        }
        var same = client.postWithKey(order + "/receipts", receipt("  DN-REPLAY ", material), "same-key").expect(201);
        assertThat(same.json().path("id").asLong()).isPositive();
        client.postWithKey(order + "/receipts", receipt(note + "-changed", material), "same-key").expect(409);
        assertThat(client.get(order + "/lines/" + material + "/receipts").expect(200).json().size()).isEqualTo(1);
    }

    @Test
    void crossOrderRaceOnDifferentDatesHasOneWinnerAndLeavesOneLedgerEntry() throws Exception {
        client.login();
        var other = new Client();
        other.login();
        var firstOrder = createOrder();
        var secondOrder = createOrder();
        var note = "DN-race";
        long firstLine = line(firstOrder);
        long secondLine = line(secondOrder);
        var secondBody = new HashMap<>(receipt(note.toUpperCase(java.util.Locale.ROOT), secondLine));
        secondBody.put("receivedOn", "2026-11-19");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return client.post(firstOrder + "/receipts", receipt(note, firstLine)); });
            var second = pool.submit(() -> { start.await(); return other.post(secondOrder + "/receipts", secondBody); });
            start.countDown();
            var one = first.get();
            var two = second.get();
            assertThat(List.of(one.status(), two.status())).containsExactlyInAnyOrder(201, 409);
            var success = one.status() == 201 ? one : two;
            var conflict = one.status() == 409 ? one : two;
            assertThat(conflict.json().path("details").path("candidates").get(0).path("reference").path("receiptId"))
                    .isEqualTo(success.json().path("id"));
        }
        assertThat(client.get(firstOrder + "/lines/" + firstLine + "/receipts").expect(200).json().size()
                + client.get(secondOrder + "/lines/" + secondLine + "/receipts").expect(200).json().size()).isEqualTo(1);
        fixtureSupplier += "-不同供应商";
        var foreign = createOrder();
        client.post(foreign + "/receipts", receipt(note, line(foreign))).expect(201);
    }

    @Test
    void unknownNumberRequiresReasonsAndRechecksCandidatesAddedAfterThePreview() throws Exception {
        client.login();
        String order = createOrder();
        String other = createOrder();
        long material = line(order);
        long foreign = line(other);
        var body = new HashMap<>(receipt("DN-placeholder", material));
        body.remove("deliveryNoteNo");
        body.put("noteStatus", "UNKNOWN");
        client.post(order + "/receipts", body).expect(400);
        body.put("unknownNoteReason", "纸面缺号");
        var check = Map.of("noteStatus", "UNKNOWN", "receivedOn", "2026-11-18", "orderLineIds", List.of(material));
        var none = client.post(order + "/receipts/duplicate-check", check).expect(200).json();
        assertThat(none.path("status").asString()).isEqualTo("NONE");
        body.put("candidateToken", none.path("candidateToken").asString());
        client.post(other + "/receipts", receipt("DN-new", foreign)).expect(201);
        var conflict = client.post(order + "/receipts", body).expect(409).json();
        assertThat(conflict.path("code").asString()).isEqualTo("POSSIBLE_DUPLICATE_RECEIPT");
        assertThat(conflict.path("details").path("candidates").size()).isEqualTo(1);
        body.put("candidateToken", conflict.path("details").path("candidateToken").asString());
        client.post(order + "/receipts", body).expect(409);
        body.put("duplicateReviewReason", "交接时间与原记录不同");
        var id = client.post(order + "/receipts", body).expect(201).json().path("id").asLong();
        var header = client.get(order + "/receipts/" + id).expect(200).json().path("header");
        assertThat(header.path("deliveryNoteNo").isNull()).isTrue();
        assertThat(header.path("duplicateReviewReason").asString()).isEqualTo("交接时间与原记录不同");
    }

    @Test
    void adjustmentAndPostingCompeteForTheSameBalanceAndCommandFailureRollsBack() throws Exception {
        client.login();
        var orderNo = uniqueOrder();
        var input = new HashMap<>(order(orderNo, gloves(10)));
        input.put("supplierName", fixtureSupplier);
        var order = "/api/orders/" + client.post("/api/orders", input).expect(201).json().path("id").asLong();
        long orderId = Long.parseLong(order.substring(order.lastIndexOf('/') + 1));
        long material = line(order);
        long id = client.post(order + "/receipts", receipt("DN-existing", material)).expect(201).json().path("id").asLong();
        long lineId = client.get(order + "/lines/" + material + "/receipts").expect(200).json().get(0).path("id").asLong();
        String path = order + "/receipts/" + id + "/lines/" + lineId + "/adjustments";
        String check = "test_block_adjustment_" + lineId;
        jdbc.execute("ALTER TABLE receipt_command ADD CONSTRAINT " + check
                + " CHECK (operation <> 'ADJUST' OR resource_id <> " + lineId + ")");
        try {
            client.postWithKey(path, Map.of("newAcceptedQty", 4, "reason", "复点", "expectedRevision", 0),
                    "blocked-command").expect(500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_adjustment WHERE receipt_line_id=?",
                    Integer.class, lineId)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE receipt_command DROP CHECK " + check);
        }
        var other = new Client();
        other.login();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                start.await();
                return client.postWithKey(path, Map.of("newAcceptedQty", 10, "reason", "补正", "expectedRevision", 0),
                        "balance-race");
            });
            var two = pool.submit(() -> {
                start.await();
                return other.post(order + "/receipts", receipt("DN-remaining", material));
            });
            start.countDown();
            assertThat(List.of(one.get().status(), two.get().status())).contains(409).anyMatch(status -> status == 200 || status == 201);
        }
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt()).isZero();
        var list = client.get("/api/orders?status=COMPLETE&query=" + orderNo).expect(200).json().path("items");
        assertThat(list.valueStream().anyMatch(item -> item.path("id").asLong() == orderId)).isTrue();
    }

    private long line(String order) throws Exception {
        return client.get(order).expect(200).json().path("lines").get(0).path("id").asLong();
    }

    private static Map<String, Object> receipt(String note, long line) {
        return Map.of("deliveryNoteNo", note, "receivedOn", "2026-11-18",
                "lines", List.of(Map.of("orderLineId", line, "acceptedQty", 5)));
    }
}
