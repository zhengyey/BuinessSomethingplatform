package labreview.receipt;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import labreview.receipt.ReceiptModels.CreateReceipt;
import labreview.receipt.ReceiptModels.Created;
import labreview.receipt.ReceiptModels.History;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{orderId}")
class ReceiptController {
    private final ReceiptService receipts;
    private final ReceiptReviewService reviews;
    private final ReceiptIdentity identities;

    ReceiptController(ReceiptService receipts, ReceiptReviewService reviews, ReceiptIdentity identities) {
        this.receipts = receipts;
        this.reviews = reviews;
        this.identities = identities;
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    Created confirm(@PathVariable long orderId, @Valid @RequestBody CreateReceipt input,
                    @RequestHeader(name = "Idempotency-Key", required = false) String key, Principal principal) {
        return new Created(receipts.confirm(orderId, input, principal.getName(), key, null));
    }

    @PostMapping("/receipts/duplicate-check")
    ReceiptIdentity.Match duplicateCheck(@PathVariable long orderId, @Valid @RequestBody ReceiptIdentity.CheckInput input) {
        return identities.check(orderId, input);
    }

    @PostMapping("/receipts/{receiptId}/lines/{lineId}/adjustments")
    ReceiptModels.AdjustmentResult adjust(@PathVariable long orderId, @PathVariable long receiptId, @PathVariable long lineId,
                                          @Valid @RequestBody ReceiptModels.AdjustmentInput input,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                          Principal principal) {
        return receipts.adjust(orderId, receiptId, lineId, input, principal.getName(), key);
    }

    @GetMapping("/lines/{lineId}/receipts")
    List<History> history(@PathVariable long orderId, @PathVariable long lineId) {
        return receipts.history(orderId, lineId);
    }

    @GetMapping("/receipts/{receiptId}")
    ReceiptModels.Detail detail(@PathVariable long orderId, @PathVariable long receiptId) {
        return reviews.receipt(orderId, receiptId);
    }
}
