package labreview.order;

import labreview.api.ApiException;
import labreview.api.InsertedId;
import labreview.order.OrderModels.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {
    private final OrderMapper orders;

    OrderService(OrderMapper orders) {
        this.orders = orders;
    }

    @Transactional
    public long create(CreateOrder input, String username) {
        var key = new InsertedId();
        try {
            orders.insertOrder(key, input.orderNo().strip(), input.supplierName().strip(),
                    input.orderedOn(), username);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_ORDER",
                    "该供应商已有相同采购单号的订单", "orderNo");
        }
        int lineNo = 1;
        for (var line : input.lines()) {
            orders.insertLine(key.getId(), lineNo++, new LineInput(line.itemCode().strip(),
                    line.itemName().strip(), line.specification().strip(), line.unit().strip(), line.orderedQty()));
        }
        return key.getId();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String query, Status status, int page, int pageSize) {
        if (page < 1) throw ApiException.invalid("页码最小为 1", "page");
        if (pageSize < 1 || pageSize > 100) {
            throw ApiException.invalid("每页条数必须在 1 至 100 之间", "pageSize");
        }
        query = query.strip();
        long offset = (page - 1L) * pageSize;
        return new Page(orders.list(null, query, status, pageSize, offset), orders.count(null, query, status),
                page, pageSize);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long id) {
        var matches = orders.list(id, "", null, 1, 0);
        if (matches.isEmpty()) throw ApiException.missing("采购单不存在");
        return new Detail(matches.get(0), orders.lines(id), orders.importSource(id));
    }
}
