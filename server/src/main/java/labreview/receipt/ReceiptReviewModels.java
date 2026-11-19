package labreview.receipt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import labreview.order.OrderLine;
import labreview.task.DeliveryResult.Field;
import labreview.task.ReceiptReviewSource.Source;

final class ReceiptReviewModels {
    enum Status { DRAFT, POSTED, CLOSED_NO_RECEIPT }
    enum Disposition { PENDING, POST, ZERO, EXCLUDED }
    enum PageDisposition { PENDING, REVIEWED, NO_DETAILS }

    record PageEdit(@NotNull @Min(1) @Max(50) Integer pageNo, @NotNull PageDisposition disposition,
                    @Size(max = 500) String reason) {}

    record LineEdit(@NotBlank @Size(max = 64) String lineKey, @NotNull @Min(1) @Max(50) Integer pageNo,
                    @Positive Long orderLineId, @Size(max = 16) String unit,
                    @Min(0) Integer deliveredQty, @Min(0) Integer acceptedQty, @NotNull Disposition disposition,
                    @Size(max = 500) String reason, @Size(max = 500) String note,
                    @Size(max = 2) Map<Field, @NotNull @Min(0) Integer> selectedEvidence) {}

    record Edit(@Size(max = 64) String deliveryNoteNo, LocalDate receivedOn, @Size(max = 500) String noReceiptReason,
                @NotNull @Size(max = 50) List<@NotNull @Valid PageEdit> pages,
                @NotNull @Size(max = 1000) List<@NotNull @Valid LineEdit> lines,
                ReceiptModels.NoteStatus noteStatus, @Size(max = 500) String unknownNoteReason,
                @Size(max = 500) String duplicateReviewReason, @Size(max = 64) String candidateToken) {}

    record Row(long id, long documentId, long taskId, long orderId, String resultHash, long version, Status status,
               String reviewJson, String snapshotJson, String updatedBy, Instant updatedAt, String confirmedBy,
               Instant confirmedAt, Long receiptId) {}

    record View(long id, long orderId, long version, Status status, Instant updatedAt, String updatedBy,
                Instant confirmedAt, String confirmedBy, Long receiptId, boolean sourceChanged,
                Edit edit, Source source, List<OrderLine> orderLines) {}

    record Outcome(Status status, long reviewId, Long receiptId) {}
}
