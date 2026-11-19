package labreview.document;

import java.time.Instant;

final class DocumentModels {
    enum Purpose { ORDER_IMPORT, DELIVERY_NOTE }

    record Uploaded(long documentId, long taskId, boolean reused, String status, Long reviewId, Long receiptId) {}

    record Original(String originalFilename, String mimeType, long byteSize, String storageKey) {}

    record Summary(long id, Purpose purpose, Long orderId, String originalFilename, String mimeType,
                   long byteSize, String sha256, String uploadedBy, Instant uploadedAt, long taskId, String status,
                   boolean reviewable, Long reviewId, String reviewStatus) {}
}
