package labreview.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class TaskModels {
    enum Purpose { ORDER_IMPORT, DELIVERY_NOTE }
    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    enum PageStatus { TEXT, NEEDS_OCR, EXTRACTED, REVIEW, PAGE_FAILED }

    record LeaseRequest(@NotBlank @Size(max = 100) String workerId) {}
    record Claim(@NotBlank @Size(max = 100) String workerId, @NotNull @Positive Long attemptNo) {}
    record Lease(long taskId, long documentId, Purpose purpose, String mimeType, String sha256,
                 String workerId, long attemptNo, String contentUrl, int leaseSeconds) {}
    record Failure(@NotBlank @Size(max = 100) String workerId, @NotNull @Positive Long attemptNo,
                   @NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 1000) String message,
                   @NotNull Boolean retryable) {}

    record Candidate(@Size(max = 64) String orderNo, @Size(max = 120) String supplierName, LocalDate orderedOn,
                     @NotNull @Size(max = 500) List<@NotNull @Valid CandidateLine> lines) {}
    record CandidateLine(@Size(max = 64) String itemCode, @Size(max = 120) String itemName,
                         @Size(max = 120) String specification, @Size(max = 16) String unit,
                         @Positive Integer orderedQty) {}
    record Source(@NotBlank @Size(max = 160) String field, @NotNull @Positive @Max(50) Integer pageNo,
                  @NotBlank @Size(max = 1000) String text,
                  @NotNull @Size(min = 4, max = 4) List<@NotNull Double> bbox) {}
    interface PageView {}
    record PageResult(@NotNull @Positive @Max(50) Integer pageNo, @NotNull PageStatus status,
                      @Size(max = 100_000) String text,
                      @NotNull @Size(max = 200) List<@NotBlank @Size(max = 500) String> hints) implements PageView {}
    record Result(@NotBlank @Size(max = 100) String workerId, @NotNull @Positive Long attemptNo,
                  @NotNull Integer protocolVersion, @NotBlank @Size(max = 80) String parserVersion,
                  @NotEmpty @Size(max = 50) List<@NotNull @Valid PageResult> pages,
                  @Valid Candidate candidate,
                  @NotNull @Size(max = 3000) List<@NotNull @Valid Source> sources,
                  @NotNull @Size(max = 200) List<@NotBlank @Size(max = 500) String> hints) {}
    record StoredResult(Candidate candidate, List<Source> sources, List<String> hints) {}
    record PageRow(int pageNo, PageStatus status, String text, String hintsJson) {}
    record Item(long id, long documentId, Purpose purpose, Status status, String originalFilename,
                Instant createdAt, long attemptNo) {}
    record DocumentView(long id, Purpose purpose, Long orderId, String originalFilename, String mimeType,
                        long byteSize, String sha256, String uploadedBy, Instant uploadedAt, long taskId,
                        Status status) {}
    record ListPage(List<Item> items, long total, int page, int pageSize) {}
    record Detail(long id, long documentId, Purpose purpose, Status status, String originalFilename,
                  Instant createdAt, long attemptNo, DocumentView document, List<? extends PageView> pages,
                  Candidate candidate, List<Source> sources, List<String> hints, Long confirmedOrderId,
                  String failureCode, String failureMessage, boolean retryable, String parserVersion,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Integer protocolVersion,
                  @JsonInclude(JsonInclude.Include.NON_NULL) DeliveryResult.Delivery delivery,
                  @JsonInclude(JsonInclude.Include.NON_NULL) List<DeliveryRecommendations.Recommendation> recommendations) {}

    record Row(long id, long documentId, Purpose purpose, Status status, String originalFilename,
               Instant createdAt, long attemptNo, String workerId, boolean leaseLive,
               Long orderId, String mimeType, long byteSize, String sha256, String uploadedBy, Instant uploadedAt,
               Long confirmedOrderId, String failureCode, String failureMessage, boolean retryable,
               String parserVersion, String resultJson, String resultHash) {}
}
