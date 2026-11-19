package labreview.receipt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class ReceiptModels {
    enum NoteStatus { VERIFIED, UNKNOWN }

    record CreateReceipt(
            @Size(max = 64) String deliveryNoteNo,
            @NotNull(message = "请选择到货日期") LocalDate receivedOn,
            @NotEmpty(message = "请选择至少一行收货物料") @Size(max = 1000) List<@NotNull @Valid LineInput> lines,
            NoteStatus noteStatus, @Size(max = 500) String unknownNoteReason,
            @Size(max = 500) String duplicateReviewReason, @Size(max = 64) String candidateToken) {}

    record LineInput(
            @NotNull(message = "请选择订单物料") @Positive Long orderLineId,
            @PositiveOrZero(message = "送货量必须为非负整数") Integer deliveredQty,
            @NotNull(message = "请输入实收量") @Positive(message = "实收量必须为正整数") Integer acceptedQty,
            @Size(max = 500) String note) {}

    record History(long id, long receiptLineId, long receiptId, String deliveryNoteNo, LocalDate receivedOn,
                   String confirmedBy, Instant confirmedAt, Integer deliveredQty, int acceptedQty, String note,
                   int originalAcceptedQty, int effectiveAcceptedQty, int revision) {}

    record Created(long id) {}

    record Header(long id, String deliveryNoteNo, LocalDate receivedOn, String confirmedBy, Instant confirmedAt,
                  NoteStatus noteStatus, String unknownNoteReason, String duplicateReviewReason, String duplicateCandidates) {}
    record DetailLine(long receiptLineId, long orderLineId, String itemCode, String itemName, String specification, String unit,
                      Integer deliveredQty, int acceptedQty, String note, int originalAcceptedQty,
                      int effectiveAcceptedQty, int revision, int remainingQty) {}
    record Detail(Header header, List<DetailLine> lines, String source, ReceiptReviewModels.View review,
                  List<Adjustment> adjustments) {}
    record Adjustment(long id, long receiptLineId, int revision, int beforeQty, int afterQty, String reason,
                      String actor, Instant createdAt) {}
    record AdjustmentInput(@NotNull @PositiveOrZero Integer newAcceptedQty,
                           @NotBlank(message = "请输入更正原因") @Size(max = 500) String reason,
                           @NotNull @PositiveOrZero Integer expectedRevision) {}
    record AdjustmentResult(long adjustmentId, int effectiveAcceptedQty, int revision) {}
    record AdjustmentTarget(long orderLineId, int acceptedQty, int revision) {}
}
