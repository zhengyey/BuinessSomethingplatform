package labreview;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiIntegrationTest {
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) {
        var port = System.getenv().getOrDefault("LABREVIEW_DB_PORT", "3306");
        var schema = System.getenv().getOrDefault("LABREVIEW_TEST_DB_NAME", "labreview_test");
        if (!schema.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("测试数据库名称必须以 _test 结尾");
        }
        properties.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + port + "/" + schema);
        properties.add("labreview.username", () -> "test-receiver");
        properties.add("labreview.password", () -> "test-receiver-password");
        properties.add("labreview.worker-token", () -> "integration-worker-token");
        try {
            var documentsDir = Files.createTempDirectory("labreview-test-documents-");
            properties.add("labreview.documents-dir", documentsDir::toString);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    Client client;
    String fixtureSupplier;

    @BeforeEach
    void setupClient() {
        client = new Client();
        fixtureSupplier = "独立测试供应商 " + UUID.randomUUID();
    }

    final class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String csrf;
        String csrfHeader;
        String idempotencyKey;

        Response postWithKey(String path, Object data, String key) throws Exception {
            idempotencyKey = key;
            try {
                return post(path, data);
            } finally {
                idempotencyKey = null;
            }
        }

        Response get(String path) throws Exception {
            return call("GET", path, "", null);
        }

        Response post(String path, Object data) throws Exception {
            return call("POST", path, json.writeValueAsString(data), "application/json");
        }

        Response upload(String path, String filename, byte[] bytes) throws Exception {
            String boundary = "labreview-" + UUID.randomUUID();
            var body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--" + boundary
                            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                            + "\"\r\nContent-Type: application/octet-stream\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var response = send("POST", path, body, "multipart/form-data; boundary=" + boundary);
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }

        HttpResponse<byte[]> download(String path) throws Exception {
            return send("GET", path, HttpRequest.BodyPublishers.noBody(), null);
        }

        Response session() throws Exception {
            var response = get("/api/session").expect(200);
            csrf = response.json().get("csrfToken").asString();
            csrfHeader = response.json().get("csrfHeader").asString();
            return response;
        }

        void login() throws Exception {
            session();
            call("POST", "/api/login", "username=test-receiver&password=test-receiver-password",
                    "application/x-www-form-urlencoded").expect(204);
            assertThat(session().json().get("username").asString()).isEqualTo("test-receiver");
        }

        Response call(String method, String path, String body, String type) throws Exception {
            var response = send(method, path, HttpRequest.BodyPublishers.ofString(body), type);
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }

        private HttpResponse<byte[]> send(String method, String path, HttpRequest.BodyPublisher body, String type)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (type != null) request.header("Content-Type", type);
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            if (csrf != null && method.equals("POST")) request.header(csrfHeader, csrf);
            return http.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    record Response(int status, String body) {
        Response expect(int wanted) {
            assertThat(status).as(body).isEqualTo(wanted);
            return this;
        }

        JsonNode json() {
            return new ObjectMapper().readTree(body);
        }
    }

    static String uniqueOrder() {
        return "PO-001-" + UUID.randomUUID();
    }

    static Map<String, Object> order(String number, Object... lines) {
        return Map.of("orderNo", number, "supplierName", " 示例实验耗材供应商 ",
                "orderedOn", "2026-11-17", "lines", java.util.List.of(lines));
    }

    static Map<String, Object> gloves(Object quantity) {
        return Map.of("itemCode", "G-01", "itemName", "丁腈手套", "specification", "M 码",
                "unit", "箱", "orderedQty", quantity);
    }

    static Map<String, Object> filters(int quantity) {
        return Map.of("itemCode", "F-02", "itemName", "滤芯", "specification", "10 英寸",
                "unit", "盒", "orderedQty", quantity);
    }

    String createOrder() throws Exception {
        var input = new java.util.HashMap<>(order(uniqueOrder(), gloves(10), filters(5)));
        input.put("supplierName", fixtureSupplier);
        var created = client.post("/api/orders", input).expect(201);
        return "/api/orders/" + created.json().get("id").asLong();
    }
}
