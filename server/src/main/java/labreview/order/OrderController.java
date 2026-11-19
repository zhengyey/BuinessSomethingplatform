package labreview.order;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import labreview.order.OrderModels.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
class OrderController {
    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    ResponseEntity<Created> create(@Valid @RequestBody CreateOrder input, Principal principal) {
        long id = orders.create(input, principal.getName());
        return ResponseEntity.created(URI.create("/api/orders/" + id)).body(new Created(id));
    }

    @GetMapping
    Page list(@RequestParam(defaultValue = "") String query, @RequestParam(required = false) Status status,
              @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return orders.list(query, status, page, pageSize);
    }

    @GetMapping("/{orderId}")
    Detail detail(@PathVariable long orderId) {
        return orders.detail(orderId);
    }
}
