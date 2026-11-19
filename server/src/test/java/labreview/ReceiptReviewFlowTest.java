package labreview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptReviewFlowTest extends ApiIntegrationTest {
    String order;
    long gloves;

    @BeforeEach
    void setupOrder() throws Exception {
        client.login();
        order = createOrder();
        gloves = client.get(order).expect(200).json().path("lines").get(0).path("id").asLong();
    }

    @Test
    void opensOnceAndPostsOnlyTheSavedReview() throws Exception {
        long document = fixture(1, original(1, 6, 5));
        String opening = order + "/documents/" + document + "/review/open";
        var opened = client.post(opening, Map.of()).expect(200).json();
        long id = opened.path("id").asLong();
        String review = order + "/reviews/" + id;
        assertThat(client.post(opening, Map.of()).expect(200).json().path("id").asLong()).isEqualTo(id);
        String foreign = createOrder();
        client.post(foreign + "/documents/" + document + "/review/open", Map.of()).expect(404);
        client.get(foreign + "/reviews/" + id).expect(404);
        var save = Map.of("version", opened.path("version").asLong(), "deliveryNoteNo", "DN-001",
                "receivedOn", "2026-11-18", "pages", List.of(Map.of("pageNo", 1, "disposition", "REVIEWED")),
                "lines", List.of(Map.of("lineKey", "p1-1", "pageNo", 1, "orderLineId", gloves, "unit", "箱",
                        "deliveredQty", 6, "acceptedQty", 5, "disposition", "POST")));
        long version = client.post(review + "/save", save).expect(200).json().path("version").asLong();
        client.post(review + "/save", save).expect(409);
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt())
                .isEqualTo(10);
        var posted = client.post(review + "/confirm", Map.of("version", version)).expect(200).json();
        assertThat(posted.path("status").asString()).isEqualTo("POSTED");
        assertThat(client.post(review + "/confirm", Map.of("version", version)).expect(200).json()).isEqualTo(posted);
        client.post(review + "/save", save).expect(409);
        client.get(order + "/receipts/" + posted.path("receiptId").asLong()).expect(200);
        assertThat(client.get(order).expect(200).json().path("lines").get(0).path("remainingQty").asInt())
                .isEqualTo(5);
    }

    @Test
    void preservesNullAndZeroAndClosesWithoutCreatingALedgerEntry() throws Exception {
        String path = open(fixture(2, original(1, 6, null), original(2, 1, 0)));
        var opened = client.get(path).expect(200).json();
        var actual = opened.path("edit").path("lines");
        assertThat(actual.get(0).path("acceptedQty").isNull()).isTrue();
        assertThat(actual.get(1).path("acceptedQty").asInt()).isZero();
        var lines = List.of(edit(1, 6, null, "EXCLUDED", "箱"), edit(2, 1, 0, "ZERO", "箱"));
        List<Map<String, Object>> pages = List.of(Map.of("pageNo", 1, "disposition", "NO_DETAILS", "reason", "只有送货数，供应商确认未交付"),
                Map.of("pageNo", 2, "disposition", "REVIEWED"));
        long version = save(path, 1, lines, pages, "本次未交付");
        var readBack = client.get(path).expect(200).json().path("edit").path("lines");
        assertThat(readBack.get(0).path("acceptedQty").isNull()).isTrue();
        assertThat(readBack.get(1).path("acceptedQty").asInt()).isZero();
        var closed = confirm(path, version).expect(200).json();
        assertThat(closed.path("status").asString()).isEqualTo("CLOSED_NO_RECEIPT");
        assertThat(closed.path("receiptId").isNull()).isTrue();
        assertThat(history().size()).isZero();
    }

    @Test
    void requiresEveryPageAndTheCorrectUnitAndPostsCrossPageLinesOnce() throws Exception {
        String path = open(fixture(2, original(1, null, 2), original(2, 3, 3)));
        long version = save(path, 1, List.of(edit(1, null, 2, "POST", "箱"), edit(2, 3, 3, "POST", "箱")),
                reviewed(1), null);
        assertThat(confirm(path, version).expect(400).json().path("field").asString()).isEqualTo("page:2");
        version = save(path, version, List.of(edit(1, null, 2, "POST", "箱"), edit(2, 3, 3, "POST", "盒")),
                reviewed(1, 2), null);
        assertThat(confirm(path, version).expect(400).json().path("field").asString()).isEqualTo("line:p2-1:unit");
        long saved = save(path, version, List.of(edit(1, null, 2, "POST", "箱"), edit(2, 3, 3, "POST", "箱")),
                reviewed(1, 2), null);
        var other = new Client();
        other.login();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirm(path, saved).expect(200).json());
            var second = executor.submit(() -> other.post(path + "/confirm", Map.of("version", saved)).expect(200).json());
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(history().size()).isEqualTo(1);
        assertThat(history().get(0).path("acceptedQty").asInt()).isEqualTo(5);
        assertThat(history().get(0).path("deliveredQty").isNull()).isTrue();
    }

    @Test
    void rechecksCurrentBalanceAndDisallowsWritesAfterSourceHashChanges() throws Exception {
        long document = fixture(1, original(1, 6, 5));
        String path = open(document);
        long version = save(path, 1, List.of(edit(1, 6, 5, "POST", "箱")), reviewed(1), null);
        long manual = client.post(order + "/receipts", Map.of("deliveryNoteNo", "DN-MANUAL",
                "receivedOn", "2026-11-18", "lines", List.of(Map.of("orderLineId", gloves, "acceptedQty", 7))))
                .expect(201).json().path("id").asLong();
        assertThat(client.get(order + "/receipts/" + manual).expect(200).json().path("source").asString())
                .isEqualTo("MANUAL");
        assertThat(confirm(path, version).expect(409).json().path("message").asString()).contains("当前可收 3");
        assertThat(history().size()).isEqualTo(1);
        jdbc.update("UPDATE parse_task SET result_hash=REPEAT('b', 64) WHERE document_id=?", document);
        assertThat(client.get(path).expect(200).json().path("sourceChanged").asBoolean()).isTrue();
        assertThat(confirm(path, version).expect(409).json().path("code").asString()).isEqualTo("REVIEW_SOURCE_CHANGED");
    }

    @Test
    void oldSameFileDraftCannotPostAgainEvenWithADifferentNumber() throws Exception {
        long first = fixture(1, original(1, 6, 5));
        long second = fixtureWithContent("%PDF-1.4\nlegacy-copy", 1, original(1, 6, 5));
        jdbc.update("""
                UPDATE document copy JOIN document original ON original.id=?
                SET copy.sha256=original.sha256 WHERE copy.id=?
                """, first, second);
        String primary = open(first);
        String alias = open(second);
        long saved = save(primary, 1, List.of(edit(1, 6, 5, "POST", "箱")), reviewed(1), null);
        var originalReceipt = confirm(primary, saved).expect(200).json().path("receiptId");
        var aliasInput = Map.of("version", 1, "deliveryNoteNo", "DN-alias", "receivedOn", "2026-11-18",
                "pages", reviewed(1), "lines", List.of(edit(1, 6, 5, "POST", "箱")));
        long aliasVersion = client.post(alias + "/save", aliasInput).expect(200).json().path("version").asLong();
        var conflict = confirm(alias, aliasVersion).expect(409).json();
        assertThat(conflict.path("code").asString()).isEqualTo("DUPLICATE_RECEIPT");
        assertThat(conflict.path("details").path("candidates").get(0).path("reference").path("receiptId"))
                .isEqualTo(originalReceipt);
        assertThat(history().size()).isEqualTo(1);
    }

    @Test
    void twoDifferentMaterialsRaceForOneBusinessIdentity() throws Exception {
        String a = open(fixture(1, original(1, 6, 5)));
        String b = open(fixtureWithContent("%PDF-1.4\nre-encoded", 1, original(1, 6, 5)));
        long versionA = save(a, 1, List.of(edit(1, 6, 5, "POST", "箱")), reviewed(1), null);
        long versionB = save(b, 1, List.of(edit(1, 6, 5, "POST", "箱")), reviewed(1), null);
        var other = new Client();
        other.login();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return confirm(a, versionA); });
            var second = executor.submit(() -> {
                start.await();
                return other.post(b + "/confirm", Map.of("version", versionB));
            });
            start.countDown();
            assertThat(List.of(first.get().status(), second.get().status())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(history().size()).isEqualTo(1);
    }

    @Test
    void rollsBackLedgerIfBindingTheReviewFails() throws Exception {
        long document = fixture(1, original(1, 6, 5));
        String hash = "%064x".formatted(document);
        jdbc.update("UPDATE parse_task SET result_hash=? WHERE document_id=?", hash, document);
        String path = open(document);
        long version = save(path, 1, List.of(edit(1, 6, 5, "POST", "箱")), reviewed(1), null);
        String constraint = "test_fail_review_" + document;
        jdbc.execute("""
                ALTER TABLE receipt_review ADD CONSTRAINT %s CHECK (status <> 'POSTED' OR result_hash <> '%s')
                """.formatted(constraint, hash));
        try {
            confirm(path, version).expect(500);
            assertThat(history().size()).isZero();
            assertThat(client.get(path).expect(200).json().path("status").asString()).isEqualTo("DRAFT");
        } finally {
            jdbc.execute("ALTER TABLE receipt_review DROP CHECK " + constraint);
        }
        confirm(path, version).expect(200);
        assertThat(history().size()).isEqualTo(1);
    }

    private long fixture(int pageCount, String... lines) throws Exception {
        return fixtureWithContent("%PDF-1.4\n", pageCount, lines);
    }

    private long fixtureWithContent(String content, int pageCount, String... lines) throws Exception {
        var upload = client.upload(order + "/documents", "DN-fixture.pdf", content.getBytes()).expect(202).json();
        long document = upload.path("documentId").asLong();
        var pages = new ArrayList<String>();
        for (int page = 1; page <= pageCount; page++) {
            pages.add("""
                    {"pageNo":%d,"status":"EXTRACTED","text":null,"hints":[],"width":100,"height":200,
                    "rotation":0,"methods":[],"ocrCalls":0,"doubaoCalls":0}
                    """.formatted(page));
        }
        jdbc.update("""
                UPDATE parse_task SET status='SUCCEEDED', result_hash=REPEAT('a', 64), result_json=? WHERE id=?
                """, """
                {"protocolVersion":2,"documentId":%d,"pages":[%s],
                "hints":[],"delivery":{"lines":[%s],"evidence":[]}}
                """.formatted(document, String.join(",", pages), String.join(",", lines)), upload.path("taskId").asLong());
        return document;
    }

    private static String original(int page, Integer delivered, Integer accepted) {
        return """
                {"lineKey":"p%d-1","pageNo":%d,"itemCode":"G-01","itemName":"丁腈手套","specification":"M 码",
                "unit":"箱","deliveredQty":%s,"acceptedQty":%s,"issues":[]}
                """.formatted(page, page, delivered, accepted);
    }

    private String open(long document) throws Exception {
        var opened = client.post(order + "/documents/" + document + "/review/open", Map.of()).expect(200).json();
        return order + "/reviews/" + opened.path("id").asLong();
    }

    private Map<String, Object> edit(int page, Integer delivered, Integer accepted, String disposition, String unit) {
        var row = new LinkedHashMap<String, Object>();
        row.put("lineKey", "p" + page + "-1");
        row.put("pageNo", page);
        row.put("orderLineId", gloves);
        row.put("unit", unit);
        row.put("deliveredQty", delivered);
        row.put("acceptedQty", accepted);
        row.put("disposition", disposition);
        row.put("reason", "已核实原件");
        return row;
    }

    private static List<Map<String, Object>> reviewed(int... numbers) {
        return java.util.Arrays.stream(numbers).mapToObj(number ->
                Map.<String, Object>of("pageNo", number, "disposition", "REVIEWED")).toList();
    }

    private long save(String path, long version, List<Map<String, Object>> lines, List<Map<String, Object>> pages,
                      String reason) throws Exception {
        var payload = new LinkedHashMap<>(Map.of("version", version, "deliveryNoteNo", "DN-FIXTURE",
                "receivedOn", "2026-11-18", "lines", lines, "pages", pages));
        payload.put("noReceiptReason", reason);
        return client.post(path + "/save", payload).expect(200).json().path("version").asLong();
    }

    private Response confirm(String path, long version) throws Exception {
        return client.post(path + "/confirm", Map.of("version", version));
    }

    private JsonNode history() throws Exception {
        return client.get(order + "/lines/" + gloves + "/receipts").expect(200).json();
    }
}
