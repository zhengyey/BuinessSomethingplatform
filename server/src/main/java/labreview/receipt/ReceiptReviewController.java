package labreview.receipt;

import java.security.Principal;
import labreview.receipt.ReceiptReviewModels.Outcome;
import labreview.receipt.ReceiptReviewModels.View;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/orders/{orderId}")
class ReceiptReviewController {
    private final ReceiptReviewService reviews;

    ReceiptReviewController(ReceiptReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/documents/{documentId}/review/open")
    View open(@PathVariable long orderId, @PathVariable long documentId, Principal principal) {
        return reviews.open(orderId, documentId, principal.getName());
    }

    @GetMapping("/reviews/{reviewId}")
    View get(@PathVariable long orderId, @PathVariable long reviewId) {
        return reviews.get(orderId, reviewId);
    }

    @PostMapping("/reviews/{reviewId}/save")
    View save(@PathVariable long orderId, @PathVariable long reviewId, @RequestBody JsonNode body, Principal principal) {
        return reviews.save(orderId, reviewId, body, principal.getName());
    }

    @PostMapping("/reviews/{reviewId}/confirm")
    Outcome confirm(@PathVariable long orderId, @PathVariable long reviewId, @RequestBody JsonNode body,
                    Principal principal) {
        return reviews.confirm(orderId, reviewId, body, principal.getName());
    }
}
