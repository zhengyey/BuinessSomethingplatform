package labreview.order;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import labreview.order.OrderModels.CreateOrder;
import labreview.order.OrderModels.Created;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/order-imports")
class OrderImportController {
    private final OrderImportService imports;

    OrderImportController(OrderImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/{documentId}/confirm")
    ResponseEntity<Created> confirm(@PathVariable long documentId, @Valid @RequestBody CreateOrder input,
                                     Principal principal) {
        var confirmation = imports.confirm(documentId, input, principal.getName());
        var response = new Created(confirmation.id());
        return confirmation.created()
                ? ResponseEntity.created(URI.create("/api/orders/" + confirmation.id())).body(response)
                : ResponseEntity.ok(response);
    }
}
