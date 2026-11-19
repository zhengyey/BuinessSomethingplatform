package labreview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageFailureTest extends ApiIntegrationTest {
    @Value("${labreview.documents-dir}") Path documentsDirectory;

    @Test
    void storageFailureCreatesNeitherDocumentNorTask() throws Exception {
        client.login();
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM parse_task", Long.class);
        String filename = "storage-failure-" + UUID.randomUUID() + ".pdf";
        byte[] pdf = Files.readAllBytes(Path.of("../samples/phase-01/PO-001.pdf"));
        assertThat(documentsDirectory.getFileName().toString()).startsWith("labreview-test-documents-");
        Path backup = documentsDirectory.resolveSibling("labreview-documents-backup-" + UUID.randomUUID());
        Files.move(documentsDirectory, backup);
        try {
            Files.createFile(documentsDirectory);
            var error = client.upload("/api/order-imports", filename, pdf).expect(500).json();
            assertThat(error.get("code").asString()).isEqualTo("DOCUMENT_STORAGE_ERROR");
        } finally {
            Files.deleteIfExists(documentsDirectory);
            Files.move(backup, documentsDirectory);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE original_filename=?", Long.class, filename))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parse_task", Long.class)).isEqualTo(count);
    }
}
