package labreview.task;

import java.util.List;
import labreview.api.ApiException;
import labreview.order.OrderLine;
import labreview.task.DeliveryRecommendations.Recommendation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReceiptReviewSource {
    private final TaskMapper tasks;
    private final ObjectMapper json;

    ReceiptReviewSource(TaskMapper tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    public record Source(long taskId, long documentId, String resultHash, String originalFilename, String mimeType,
                         List<DeliveryResult.Page> pages, DeliveryResult.Delivery delivery,
                         List<Recommendation> recommendations) {}

    public Source load(long orderId, long documentId, List<OrderLine> orderLines) {
        Long taskId = tasks.reviewTask(orderId, documentId);
        if (taskId == null) throw ApiException.missing("此订单没有该签收材料");
        var row = tasks.row(taskId);
        var payload = row.resultJson() == null ? null : json.readTree(row.resultJson());
        if (row.status() != TaskModels.Status.SUCCEEDED || row.resultHash() == null
                || payload == null || payload.path("protocolVersion").asInt() != 2) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_AVAILABLE", "材料尚无可核对的 v2 解析结果", null);
        }
        var result = json.treeToValue(payload, DeliveryResult.Stored.class);
        return new Source(taskId, documentId, row.resultHash(), row.originalFilename(), row.mimeType(),
                result.pages(), result.delivery(), result.delivery().lines().stream()
                .map(line -> DeliveryRecommendations.recommend(line, orderLines)).toList());
    }
}
