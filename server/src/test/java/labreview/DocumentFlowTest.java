package labreview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFlowTest extends ApiIntegrationTest {
    private static final Path SAMPLES = Path.of("..", "samples", "phase-01");
    private static final String IMPORTS = "/api/order-imports";

    @Test
    void validatesUploadsAndStreamsOriginalsWithoutChangingOrderQuantities() throws Exception {
        byte[] pdf = Files.readAllBytes(SAMPLES.resolve("PO-001.pdf"));
        byte[] jpg = Files.readAllBytes(SAMPLES.resolve("DN-001.jpg"));
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");

        client.login();
        String order = createOrder();
        String documents = order + "/documents";
        client.get(documents).expect(200);
        client.upload(IMPORTS, "wrong.pdf", jpg).expect(415);
        client.upload(IMPORTS, "empty.pdf", new byte[0]).expect(400);
        client.upload(IMPORTS, "large.pdf", Arrays.copyOf(pdf, 20 * 1024 * 1024 + 1)).expect(413);
        client.upload(IMPORTS, "delivery.jpg", jpg).expect(415);
        client.upload("/api/orders/9223372036854775807/documents", "delivery.jpg", jpg).expect(404);

        var uploaded = client.upload(IMPORTS, "purchase.pdf", pdf).expect(202).json();
        assertThat(original(uploaded.get("documentId").asLong(), "application/pdf", pdf)).isPositive();
        var photo = client.upload(documents, "photo.JPG", jpg).expect(202).json();
        assertThat(original(photo.get("documentId").asLong(), "image/jpeg", jpg)).isPositive();
        var image = client.upload(documents, "image.png", png).expect(202).json();
        assertThat(original(image.get("documentId").asLong(), "image/png", png)).isPositive();

        var list = client.get(documents).expect(200).json();
        assertThat(list.size()).isEqualTo(2);
        var latest = list.get(0);
        assertThat(latest.get("taskId").asLong()).isEqualTo(image.get("taskId").asLong());
        assertThat(latest.get("status").asString()).isEqualTo("QUEUED");
        assertThat(latest.get("purpose").asString()).isEqualTo("DELIVERY_NOTE");
        assertThat(latest.get("sha256").asString()).isEqualTo(sha256(png));
        assertThat(latest.get("byteSize").asLong()).isEqualTo(png.length);
        assertThat(latest.get("uploadedBy").asString()).isEqualTo("test-receiver");
        assertThat(latest.get("originalFilename").asString()).isEqualTo("image.png");
        assertThat(client.get(order).expect(200).json().get("lines").get(0).get("acceptedQty").asInt()).isZero();
    }

    @Test
    void confirmsCompletedImportsAtomicallyAndIdempotently() throws Exception {
        client.login();
        byte[] pdf = Files.readAllBytes(SAMPLES.resolve("PO-001.pdf"));
        var uploaded = client.upload(IMPORTS, "purchase.pdf", pdf).expect(202).json();
        long documentId = uploaded.get("documentId").asLong();
        long taskId = uploaded.get("taskId").asLong();
        String confirm = IMPORTS + "/" + documentId + "/confirm";
        String number = uniqueOrder();
        var corrected = order(number, gloves(23));
        client.post(confirm, corrected).expect(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE order_no = ?", Long.class, number))
                .isZero();
        jdbc.update("UPDATE parse_task SET status = 'SUCCEEDED' WHERE id = ?", taskId);

        long orderId;
        try (var requests = Executors.newFixedThreadPool(2)) {
            var first = requests.submit(() -> client.post(confirm, corrected));
            var second = requests.submit(() -> client.post(confirm, corrected));
            var firstResponse = first.get();
            var secondResponse = second.get();
            assertThat(new int[] {firstResponse.status(), secondResponse.status()}).containsExactlyInAnyOrder(201, 200);
            orderId = firstResponse.json().get("id").asLong();
            assertThat(secondResponse.json().get("id").asLong()).isEqualTo(orderId);
        }
        assertThat(client.post(confirm, order(uniqueOrder(), gloves(7))).expect(200).json().get("id").asLong())
                .isEqualTo(orderId);
        var detail = client.get("/api/orders/" + orderId).expect(200).json();
        assertThat(detail.get("importDocumentId").asLong()).isEqualTo(documentId);
        assertThat(detail.get("importTaskId").asLong()).isEqualTo(taskId);
        assertThat(detail.get("lines").get(0).get("orderedQty").asInt()).isEqualTo(23);

        var duplicate = client.upload(IMPORTS, "other.pdf", pdf).expect(202).json();
        long duplicateDocumentId = duplicate.get("documentId").asLong();
        jdbc.update("UPDATE parse_task SET status = 'SUCCEEDED' WHERE id = ?", duplicate.get("taskId").asLong());
        var conflict = client.post(IMPORTS + "/" + duplicateDocumentId + "/confirm", corrected).expect(409).json();
        assertThat(conflict.get("code").asString()).isEqualTo("DUPLICATE_ORDER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_import_confirmation WHERE document_id = ?",
                Long.class, duplicateDocumentId)).isZero();
    }

    private long original(long id, String contentType, byte[] expected) throws Exception {
        String path = "/api/documents/" + id + "/content";
        assertThat(new Client().download(path).statusCode()).isEqualTo(401);
        var response = client.download(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains(contentType);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("inline");
        assertThat(client.download(path + "?download=true").headers()
                .firstValue("Content-Disposition").orElseThrow()).startsWith("attachment");
        assertThat(sha256(response.body())).isEqualTo(sha256(expected));
        return id;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
