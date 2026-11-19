package labreview.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class OrderModels {
    enum Status { PENDING, PARTIAL, COMPLETE }

    record CreateOrder(
            @NotBlank(message = "请输入采购单号") @Size(max = 64) String orderNo,
            @NotBlank(message = "请输入供应商") @Size(max = 120) String supplierName,
            @NotNull(message = "请选择采购日期") LocalDate orderedOn,
            @NotEmpty(message = "采购单至少需要一行物料") List<@NotNull @Valid LineInput> lines) {}

    record LineInput(
            @NotBlank(message = "请输入物料编码") @Size(max = 64) String itemCode,
            @NotBlank(message = "请输入物料名称") @Size(max = 120) String itemName,
            @NotBlank(message = "请输入规格") @Size(max = 120) String specification,
            @NotBlank(message = "请输入单位") @Size(max = 16) String unit,
            @NotNull(message = "请输入订购量") @Positive(message = "订购量必须为正整数") Integer orderedQty) {}

    record Summary(long id, String orderNo, String supplierName, LocalDate orderedOn,
                   String createdBy, Instant createdAt, Status status) {}

    record Detail(long id, String orderNo, String supplierName, LocalDate orderedOn,
                  String createdBy, Instant createdAt, Status status, List<OrderLine> lines,
                  Long importDocumentId, Long importTaskId) {
        Detail(Summary order, List<OrderLine> lines, OrderMapper.ImportSource source) {
            this(order.id(), order.orderNo(), order.supplierName(), order.orderedOn(), order.createdBy(),
                    order.createdAt(), order.status(), lines, source == null ? null : source.documentId(),
                    source == null ? null : source.taskId());
        }
    }

    record Page(List<Summary> items, long total, int page, int pageSize) {}
    record Created(long id) {}
}
