package labreview.task;

import jakarta.validation.Valid;
import labreview.document.DocumentService;
import labreview.task.TaskModels.*;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/parse-tasks")
class InternalTaskController {
    private final TaskService tasks;
    private final DocumentService documents;

    InternalTaskController(TaskService tasks, DocumentService documents) {
        this.tasks = tasks;
        this.documents = documents;
    }

    @PostMapping("/lease")
    ResponseEntity<Lease> lease(@Valid @RequestBody LeaseRequest input) {
        Lease lease = tasks.lease(input.workerId());
        return lease == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(lease);
    }

    @GetMapping("/{id}/content")
    ResponseEntity<Resource> content(@PathVariable long id, @RequestParam String workerId,
                                     @RequestParam long attemptNo) {
        return documents.original(tasks.documentForActiveAttempt(id, workerId, attemptNo));
    }

    @PostMapping("/{id}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void heartbeat(@PathVariable long id, @Valid @RequestBody Claim claim) {
        tasks.heartbeat(id, claim);
    }

    @PostMapping("/{id}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void result(@PathVariable long id, @RequestBody JsonNode result) {
        tasks.result(id, result);
    }

    @PostMapping("/{id}/failure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void failure(@PathVariable long id, @Valid @RequestBody Failure failure) {
        tasks.failure(id, failure);
    }
}
