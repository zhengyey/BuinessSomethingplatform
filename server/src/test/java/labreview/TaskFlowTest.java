package labreview;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TaskFlowTest extends ApiIntegrationTest {
    private static final String INTERNAL = "/internal/parse-tasks";
    private static final String TOKEN = "integration-worker-token";
    private final HttpClient worker = HttpClient.newHttpClient();

    @BeforeEach
    void excludeUnfinishedFixturesFromPriorRuns() {
        jdbc.update("""
                UPDATE parse_task t JOIN document d ON d.id=t.document_id
                SET t.status='FAILED', t.lease_until=NULL
                WHERE d.original_filename LIKE 'task-flow-%' AND t.status IN ('QUEUED','RUNNING')
                """);
    }

    @Test
    void browserAndWorkerUseSeparateCredentials() throws Exception {
        client.login();
        client.post(INTERNAL + "/lease", Map.of("workerId", "browser")).expect(401);
        var forbidden = request("POST", "/api/tasks/1/retry", Map.of(), TOKEN);
        forbidden.expect(401);
        request("POST", INTERNAL + "/lease", Map.of("workerId", "worker"), "wrong").expect(401);
    }

    @Test
    void deliveryV2AcceptsPageFailureAndPreservesItOnReplay() throws Exception {
        client.login();
        String order = createOrder();
        var before = client.get(order).expect(200).json();
        var lease = deliveryLease(order, "DN-002.pdf");
        long id = lease.get("taskId").asLong();
        var payload = callbackFixture("v2-valid.json", lease);
        evidence(payload, 0).put("rawText", "MAT-001 实收 0 箱；送 货 数 量 5");
        evidence(payload, 1).put("rawText", "手写 实 收 数 量 0，短 5");
        addModelEvidence(payload, true);
        publish(id, payload).expect(204);
        publish(id, payload).expect(204);
        publish(id, payload.deepCopy().put("attemptNo", 99)).expect(409);
        var changed = payload.deepCopy();
        evidence(changed, 1).put("rawText", "实收 0 箱");
        publish(id, changed).expect(409);
        var detail = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(detail.get("protocolVersion").asInt()).isEqualTo(2);
        assertThat(detail.at("/pages/1/status").asString()).isEqualTo("PAGE_FAILED");
        assertThat(detail.at("/pages/1/width").asInt()).isEqualTo(595);
        assertThat(detail.at("/delivery/lines/0/deliveredQty").asInt()).isEqualTo(5);
        assertThat(detail.at("/delivery/lines/0/acceptedQty").asInt(-1)).isZero();
        assertThat(detail.at("/delivery/evidence/1/value").asInt(-1)).isZero();
        assertThat(detail.at("/delivery/evidence/1/rawText").asString()).isEqualTo("手写 实 收 数 量 0，短 5");
        assertThat(detail.at("/delivery/evidence/2/supporting").asBoolean()).isTrue();
        assertThat(detail.at("/recommendations/0/status").asString()).isEqualTo("CODE_MATCH");
        assertThat(detail.at("/recommendations/0/candidates/0/orderLineId"))
                .isEqualTo(before.at("/lines/0/id"));
        assertThat(client.get(order).expect(200).json().get("lines")).isEqualTo(before.get("lines"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parse_page WHERE task_id=?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM receipt_line rl JOIN receipt r ON r.id=rl.receipt_id WHERE r.order_id=?
                """, Integer.class, before.get("id").asLong())).isZero();
    }

    @Test
    void deliveryV2RejectsInvalidReferencesTypesAndInventedQuantitiesWithoutWriting() throws Exception {
        client.login();
        var lease = deliveryLease(createOrder(), "DN-002.pdf");
        long id = lease.get("taskId").asLong();
        publish(id, callbackFixture("v2-invalid.json", lease)).expect(400);
        var valid = callbackFixture("v2-valid.json", lease);
        List<Consumer<ObjectNode>> mutations = List.of(
                payload -> payload.put("documentId", lease.get("documentId").asLong() + 1),
                payload -> ((ObjectNode) payload.at("/pages/1")).put("pageNo", 3),
                payload -> ((ObjectNode) payload.at("/pages/0")).put("status", 2),
                payload -> ((ObjectNode) payload.at("/pages/0")).put("width", "595"),
                payload -> ((ArrayNode) payload.at("/delivery/lines")).add(line(payload).deepCopy()),
                payload -> line(payload).put("lineKey", "p2-r1"),
                payload -> line(payload).put("acceptedQty", "0"),
                payload -> line(payload).put("acceptedQty", 0.0),
                payload -> line(payload).put("acceptedQty", 1),
                payload -> evidence(payload, 1).put("pageNo", 2),
                payload -> evidence(payload, 1).put("supporting", false),
                payload -> evidence(payload, 1).put("rawText", "送货数量 0"),
                payload -> evidence(payload, 1).put("rawText", "实收 0+5"),
                payload -> evidence(payload, 1).put("rawText", "实收 0 － 5"),
                payload -> evidence(payload, 1).put("rawText", "实收 0.5"),
                payload -> evidence(payload, 1).put("rawText", "实收 0，000"),
                payload -> evidence(payload, 1).put("rawText", "实收 0 万"),
                payload -> evidence(payload, 1).put("rawText", "预计 实 收 0"),
                payload -> evidence(payload, 1).put("rawText", "未实收 0"),
                payload -> evidence(payload, 1).put("rawText", "手写 实收 1 箱；实收 0 箱"),
                payload -> evidence(payload, 1).put("supporting", "true"),
                payload -> evidence(payload, 1).remove("value"),
                payload -> evidence(payload, 1).put("value", 1),
                payload -> line(payload).put("orderLineId", 1),
                payload -> evidence(payload, 1).put("method", "DOUBAO"),
                payload -> addModelEvidence(payload, true).putArray("bbox").add(0.1).add(0.1).add(0.2).add(0.2),
                payload -> addModelEvidence(payload, true).put("precision", "PAGE").putNull("bbox"),
                payload -> addModelEvidence(payload, true).put("rawText", "实收 0；手写 实收 2 箱"),
                TaskFlowTest::addConflict
        );
        for (var mutation : mutations) {
            var invalid = valid.deepCopy();
            mutation.accept(invalid);
            publish(id, invalid).expect(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parse_page WHERE task_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT result_json FROM parse_task WHERE id=?", String.class, id)).isNull();
        assertThat(client.get("/api/tasks/" + id).expect(200).json().get("status").asString()).isEqualTo("RUNNING");
        evidence(valid, 1).put("rawText", "手写 实收 1 箱；实收 0 箱");
        addModelEvidence(valid, false).put("value", 3).put("rawText", "实收 3 箱");
        line(valid).putNull("acceptedQty").putArray("issues").add("CONFLICT");
        publish(id, valid).expect(204);
        var detail = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(detail.at("/delivery/lines/0/acceptedQty").isNull()).isTrue();
        assertThat(detail.at("/delivery/evidence").size()).isEqualTo(3);
    }

    @Test
    void deliveryRecommendationsUseOnlyCurrentOrderAndKeepAmbiguityVisible() throws Exception {
        client.login();
        String order = "/api/orders/" + client.post("/api/orders", order(uniqueOrder(),
                gloves(10), sku("G-01", "丁腈手套", "L 码", "箱"), filters(5),
                sku("O-03", "标签", "S", "卷"), sku("PO-04", "材料 A", "S", "卷"),
                sku("P0-04", "材料 B", "S", "卷"))).expect(201).json().get("id").asLong();
        client.post("/api/orders", order(uniqueOrder(), sku("OTHER", "外部材料", "S", "卷"))).expect(201);
        var lease = deliveryLease(order, "DN-002.pdf");
        var payload = callbackFixture("v2-valid.json", lease);
        var lines = (ArrayNode) payload.at("/delivery/lines");
        lines.add(matchingLine(payload, 2, null, "滤芯", "10 英寸", "盒"));
        lines.add(matchingLine(payload, 3, "F-02", "滤芯", "10 英寸", "箱"));
        lines.add(matchingLine(payload, 4, "0-03", null, "S", "卷"));
        lines.add(matchingLine(payload, 5, "OTHER", "外部材料", "S", "卷"));
        lines.add(matchingLine(payload, 6, "PO-04", "材料 A", "S", "卷"));
        long id = lease.get("taskId").asLong();
        publish(id, payload).expect(204);
        var recommendations = client.get("/api/tasks/" + id).expect(200).json().get("recommendations");
        var statuses = new ArrayList<String>();
        recommendations.forEach(recommendation -> statuses.add(recommendation.get("status").asString()));
        assertThat(statuses).containsExactly("REVIEW", "NAME_MATCH", "REVIEW", "REVIEW", "UNMATCHED", "REVIEW");
        assertThat(recommendations.at("/0/candidates").size()).isEqualTo(2);
        assertThat(recommendations.at("/3/candidates/0/itemCode").asString()).isEqualTo("O-03");
        assertThat(recommendations.at("/4/candidates").isEmpty()).isTrue();
        assertThat(recommendations.at("/5/candidates").size()).isEqualTo(2);
    }

    @Test
    void legacyDeliveryFixtureCanStillBePublishedAndRead() throws Exception {
        client.login();
        var lease = deliveryLease(createOrder(), "DN-001.jpg");
        long id = lease.get("taskId").asLong();
        var payload = callbackFixture("v1-delivery.json", lease);
        publish(id, payload).expect(204);
        publish(id, payload).expect(204);
        var detail = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(detail.has("protocolVersion")).isFalse();
        assertThat(detail.has("delivery")).isFalse();
        assertThat(detail.at("/pages/0/status").asString()).isEqualTo("NEEDS_OCR");
        assertThat(detail.at("/pages/0/text").isNull()).isTrue();
    }

    @Test
    void purchaseFixtureKeepsTheV1HashAndCannotUseTheDeliveryProtocol() throws Exception {
        long id = fixture("PO-001.pdf").get("taskId").asLong();
        var lease = lease("purchase").expect(200).json();
        assertThat(lease.get("taskId").asLong()).isEqualTo(id);
        publish(id, callbackFixture("v2-valid.json", lease)).expect(400);
        var payload = callbackFixture("v1-purchase.json", lease);
        publish(id, payload).expect(204);
        assertThat(jdbc.queryForObject("SELECT result_hash FROM parse_task WHERE id=?", String.class, id))
                .isEqualTo("9d39516add43f078fff0385a351bbebb9f4795748ed60c4512195ebb86cbb469");
        publish(id, payload).expect(204);
        var detail = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(detail.at("/candidate/orderedOn").asString()).isEqualTo("2026-11-17");
        assertThat(detail.at("/sources/0/field").asString()).isEqualTo("orderNo");
    }

    @Test
    void competingWorkersCanOnlyLeaseOneCopyAndOldAttemptCannotPublish() throws Exception {
        var task = fixture("PO-001.pdf");
        long id = task.get("taskId").asLong();
        var pool = Executors.newFixedThreadPool(2);
        JsonNode held;
        try {
            var a = pool.submit(() -> lease("A"));
            var b = pool.submit(() -> lease("B"));
            var leases = List.of(a.get(), b.get());
            var matches = leases.stream().filter(response -> response.status() == 200)
                    .map(Response::json).filter(row -> row.get("taskId").asLong() == id).toList();
            assertThat(matches).hasSize(1);
            held = matches.getFirst();
        } finally {
            pool.shutdownNow();
        }
        var holder = held.get("workerId").asString();
        long attempt = held.get("attemptNo").asLong();
        request("POST", INTERNAL + "/" + id + "/heartbeat", claim(holder, attempt), TOKEN).expect(204);
        var path = held.get("contentUrl").asString();
        var original = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + path + "?workerId=" + holder + "&attemptNo=" + attempt))
                .header("Authorization", "Bearer " + TOKEN).build();
        var downloaded = worker.send(original, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).isEqualTo(sample("PO-001.pdf"));

        expire(id);
        var takeover = lease("C").expect(200).json();
        assertThat(takeover.get("taskId").asLong()).isEqualTo(id);
        long next = takeover.get("attemptNo").asLong();
        assertThat(next).isEqualTo(attempt + 1);
        request("POST", INTERNAL + "/" + id + "/result", result(holder, attempt, "old"), TOKEN).expect(409);
        request("POST", INTERNAL + "/" + id + "/heartbeat", claim(holder, attempt), TOKEN).expect(409);
        var accepted = result("C", next, "original");
        request("POST", INTERNAL + "/" + id + "/result", accepted, TOKEN).expect(204);
        request("POST", INTERNAL + "/" + id + "/result", accepted, TOKEN).expect(204);
        request("POST", INTERNAL + "/" + id + "/result", result("C", next, "different"), TOKEN).expect(409);
        var detail = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(detail.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(detail.get("pages").get(0).get("text").asString()).isEqualTo("original");
        assertThat(detail.get("candidate").get("orderNo").asString()).isEqualTo("PO-001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parse_page WHERE task_id=?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void lastExpiredAttemptBecomesVisibleAndManualRetryKeepsSequence() throws Exception {
        long id = fixture("PO-001.pdf").get("taskId").asLong();
        jdbc.update("UPDATE parse_task SET cycle_attempts=2, attempt_no=2 WHERE id=?", id);
        var third = lease("A").expect(200).json();
        assertThat(third.get("taskId").asLong()).isEqualTo(id);
        assertThat(third.get("attemptNo").asLong()).isEqualTo(3);
        expire(id);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(client.get("/api/tasks/" + id).expect(200).json().get("status").asString())
                        .isEqualTo("FAILED"));
        JsonNode details = client.get("/api/tasks/" + id).expect(200).json();
        assertThat(details.get("failureCode").asString()).isEqualTo("LEASE_EXPIRED");
        assertThat(details.get("retryable").asBoolean()).isTrue();
        client.post("/api/tasks/" + id + "/retry", Map.of()).expect(204);
        prioritize(id);
        var next = lease("B").expect(200).json();
        assertThat(next.get("taskId").asLong()).isEqualTo(id);
        assertThat(next.get("attemptNo").asLong()).isEqualTo(4);
        request("POST", INTERNAL + "/" + id + "/result", result("A", 3, "old"), TOKEN).expect(409);
        request("POST", INTERNAL + "/" + id + "/failure",
                Map.of("workerId", "B", "attemptNo", 4, "code", "BROKEN_PDF", "message", "文件损坏", "retryable", false),
                TOKEN).expect(204);
        client.post("/api/tasks/" + id + "/retry", Map.of()).expect(409);
        assertThat(client.get("/api/tasks?purpose=ORDER_IMPORT&status=FAILED").expect(200).json()
                .get("total").asLong()).isPositive();
    }

    @Test
    void temporaryFailureQueuesWithDelayAndInvalidResultDoesNotWritePages() throws Exception {
        long id = fixture("PO-001.pdf").get("taskId").asLong();
        var first = lease("A").expect(200).json();
        assertThat(first.get("taskId").asLong()).isEqualTo(id);
        long attempt = first.get("attemptNo").asLong();
        var invalid = new LinkedHashMap<>(result("A", attempt, "bad"));
        invalid.put("pages", List.of(Map.of("pageNo", 2, "status", "TEXT", "text", "bad", "hints", List.of())));
        request("POST", INTERNAL + "/" + id + "/result", invalid, TOKEN).expect(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parse_page WHERE task_id=?", Integer.class, id)).isZero();
        request("POST", INTERNAL + "/" + id + "/failure",
                Map.of("workerId", "A", "attemptNo", attempt, "code", "IO_ERROR", "message", "暂时中断", "retryable", true),
                TOKEN).expect(204);
        assertThat(jdbc.queryForObject("SELECT available_at > created_at FROM parse_task WHERE id=?",
                Boolean.class, id)).isTrue();
        assertThat(client.get("/api/tasks/" + id).expect(200).json().get("status").asString()).isEqualTo("QUEUED");
        prioritize(id);
        assertThat(lease("B").expect(200).json().get("attemptNo").asLong()).isEqualTo(attempt + 1);
        request("POST", INTERNAL + "/" + id + "/result", result("B", attempt + 1, "text"), TOKEN).expect(204);
    }

    private JsonNode fixture(String filename) throws Exception {
        client.login();
        var data = client.upload("/api/order-imports", "task-flow-" + filename, sample(filename)).expect(202).json();
        prioritize(data.get("taskId").asLong());
        return data;
    }

    private JsonNode deliveryLease(String order, String filename) throws Exception {
        var document = client.upload(order + "/documents", "task-flow-" + filename, sample(filename)).expect(202).json();
        long id = document.get("taskId").asLong();
        prioritize(id);
        var lease = lease("delivery").expect(200).json();
        assertThat(lease.get("taskId").asLong()).isEqualTo(id);
        return lease;
    }

    private ObjectNode callbackFixture(String name, JsonNode lease) throws Exception {
        var payload = (ObjectNode) json.readTree(Files.readString(Path.of("../contracts/parse-result", name)));
        payload.put("workerId", lease.get("workerId").asString()).put("attemptNo", lease.get("attemptNo").asLong());
        if (payload.has("documentId")) payload.put("documentId", lease.get("documentId").asLong());
        return payload;
    }

    private Response publish(long id, ObjectNode payload) throws Exception {
        return request("POST", INTERNAL + "/" + id + "/result", payload, TOKEN);
    }

    private static ObjectNode line(ObjectNode payload) {
        return (ObjectNode) payload.at("/delivery/lines/0");
    }

    private static ObjectNode evidence(ObjectNode payload, int index) {
        return (ObjectNode) payload.at("/delivery/evidence/" + index);
    }

    private static void addConflict(ObjectNode payload) {
        var conflicting = evidence(payload, 1).deepCopy().put("value", 1).put("rawText", "实收 1 箱");
        ((ArrayNode) payload.at("/delivery/evidence")).add(conflicting);
    }

    private static ObjectNode addModelEvidence(ObjectNode payload, boolean supporting) {
        var model = evidence(payload, 1).deepCopy().put("method", "DOUBAO").put("supporting", supporting);
        ((ArrayNode) payload.at("/delivery/evidence")).add(model);
        return model;
    }

    private static ObjectNode matchingLine(ObjectNode payload, int index, String code, String name,
                                           String specification, String unit) {
        return line(payload).deepCopy().put("lineKey", "p1-r" + index).put("itemCode", code)
                .put("itemName", name).put("specification", specification).put("unit", unit)
                .putNull("deliveredQty").putNull("acceptedQty");
    }

    private static Map<String, Object> sku(String code, String name, String specification, String unit) {
        return Map.of("itemCode", code, "itemName", name, "specification", specification, "unit", unit, "orderedQty", 10);
    }

    private static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(Path.of("../samples/phase-01", name));
    }

    private void prioritize(long id) {
        jdbc.update("UPDATE parse_task SET available_at='2000-01-01', lease_until=NULL WHERE id=?", id);
    }

    private void expire(long id) {
        jdbc.update("UPDATE parse_task SET lease_until='2000-01-01' WHERE id=?", id);
    }

    private Response lease(String workerId) throws Exception {
        return request("POST", INTERNAL + "/lease", Map.of("workerId", workerId), TOKEN);
    }

    private Response request(String method, String route, Object body, String token) throws Exception {
        var response = worker.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static Map<String, Object> claim(String workerId, long attempt) {
        return Map.of("workerId", workerId, "attemptNo", attempt);
    }

    private static Map<String, Object> result(String workerId, long attempt, String text) {
        return Map.of("workerId", workerId, "attemptNo", attempt, "protocolVersion", 1,
                "parserVersion", "test/1", "candidate", Map.of("orderNo", "PO-001", "lines", List.of()),
                "pages", List.of(Map.of("pageNo", 1, "status", "TEXT", "text", text, "hints", List.of())),
                "sources", List.of(), "hints", List.of());
    }
}
