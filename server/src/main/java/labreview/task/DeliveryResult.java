package labreview.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import labreview.task.TaskModels.PageStatus;
import labreview.task.TaskModels.PageView;
import tools.jackson.databind.JsonNode;

public record DeliveryResult(@NotBlank @Size(max = 100) String workerId, @NotNull @Positive Long attemptNo,
                      @NotNull Integer protocolVersion, @NotNull @Positive Long documentId,
                      @NotBlank @Size(max = 80) String parserVersion,
                      @NotEmpty @Size(max = 50) List<@NotNull @Valid Page> pages,
                      @NotNull @Size(max = 200) List<@NotBlank @Size(max = 500) String> hints,
                      @NotNull @Valid Delivery delivery) {
    enum Method { PDF_TEXT, PADDLE_OCR, DOUBAO }
    public enum Field { itemCode, itemName, specification, unit, deliveredQty, acceptedQty }
    enum Precision { REGION, PAGE }

    public record Page(@NotNull @Positive @Max(50) Integer pageNo, @NotNull PageStatus status,
                @JsonProperty(required = true) @Size(max = 100_000) String text,
                @NotNull @Size(max = 200) List<@NotBlank @Size(max = 500) String> hints,
                @NotNull @Positive Double width, @NotNull @Positive Double height,
                @NotNull Integer rotation, @NotNull @Size(max = 3) List<@NotNull Method> methods,
                @NotNull @Min(0) @Max(1) Integer ocrCalls, @NotNull @Min(0) @Max(1) Integer doubaoCalls)
            implements PageView {}

    public record Delivery(@NotNull @Size(max = 500) List<@NotNull @Valid Line> lines,
                    @NotNull @Size(max = 6000) List<@NotNull @Valid Evidence> evidence) {}

    public record Line(@NotBlank @Size(max = 32) String lineKey, @NotNull @Positive @Max(50) Integer pageNo,
                @JsonProperty(required = true) @Size(min = 1, max = 64) String itemCode,
                @JsonProperty(required = true) @Size(min = 1, max = 120) String itemName,
                @JsonProperty(required = true) @Size(min = 1, max = 120) String specification,
                @JsonProperty(required = true) @Size(min = 1, max = 16) String unit,
                @JsonProperty(required = true) @Min(0) Integer deliveredQty,
                @JsonProperty(required = true) @Min(0) Integer acceptedQty,
                @NotNull @Size(max = 30) List<@NotBlank @Size(max = 64) String> issues) {}

    public record Evidence(@NotBlank @Size(max = 32) String lineKey, @NotNull Field field, @NotNull Method method,
                    @NotBlank @Size(max = 1000) String rawText,
                    @JsonProperty(required = true) JsonNode value, @NotNull @Positive @Max(50) Integer pageNo,
                    @JsonProperty(required = true) @Size(min = 4, max = 4) List<@NotNull Double> bbox,
                    @NotNull Precision precision, @NotNull Boolean supporting) {}

    record Stored(int protocolVersion, long documentId, List<Page> pages, List<String> hints, Delivery delivery) {}
}
