package labreview.task;

import labreview.task.TaskModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
class TaskController {
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    ListPage list(@RequestParam(required = false) Status status, @RequestParam(required = false) Purpose purpose,
                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return tasks.list(status, purpose, page, pageSize);
    }

    @GetMapping("/{id}")
    Detail detail(@PathVariable long id) {
        return tasks.detail(id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(@PathVariable long id) {
        tasks.retry(id);
    }
}
