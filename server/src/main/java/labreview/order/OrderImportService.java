package labreview.order;

import labreview.api.ApiException;
import labreview.order.OrderModels.CreateOrder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderImportService {
    private final OrderService orders;
    private final OrderMapper mapper;

    OrderImportService(OrderService orders, OrderMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Transactional
    public Confirmation confirm(long documentId, CreateOrder input, String username) {
        String purpose = mapper.lockDocument(documentId);
        if (purpose == null) throw ApiException.missing("材料不存在");
        if (!"ORDER_IMPORT".equals(purpose)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_IMPORT_DOCUMENT", "该材料不是采购单导入原件", null);
        }

        Long confirmedOrder = mapper.confirmedOrder(documentId);
        if (confirmedOrder != null) return new Confirmation(confirmedOrder, false);
        if (!"SUCCEEDED".equals(mapper.importTaskStatus(documentId))) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_NOT_READY", "采购单尚未完成解析", null);
        }

        long id = orders.create(input, username);
        mapper.confirmImport(documentId, id, username);
        return new Confirmation(id, true);
    }

    record Confirmation(long id, boolean created) {}
}
