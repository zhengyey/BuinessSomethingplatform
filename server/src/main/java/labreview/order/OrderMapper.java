package labreview.order;

import java.time.LocalDate;
import java.util.List;
import labreview.api.InsertedId;
import labreview.order.OrderModels.LineInput;
import labreview.order.OrderModels.Status;
import labreview.order.OrderModels.Summary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OrderMapper {
    record ImportSource(long documentId, long taskId) {}

    String ACCEPTED_FOR_LINE = """
            COALESCE((SELECT SUM(rl.accepted_qty) FROM effective_receipt_line rl WHERE rl.order_line_id = l.id), 0)
            """;
    String ORDER_STATUS = """
            CASE
                WHEN MAX(l.accepted_qty) = 0 THEN 'PENDING'
                WHEN MAX(l.ordered_qty - l.accepted_qty) = 0 THEN 'COMPLETE'
                ELSE 'PARTIAL'
            END
            """;
    String SUMMARIES = """
            WITH matching_orders AS (
                SELECT * FROM purchase_order
                WHERE (#{id,jdbcType=BIGINT} IS NULL OR id = #{id,jdbcType=BIGINT})
                  AND LOCATE(#{query}, order_no) > 0
            ), line_totals AS (
                SELECT l.order_id, l.ordered_qty, """ + ACCEPTED_FOR_LINE + """
                 AS accepted_qty
                FROM purchase_order_line l JOIN matching_orders o ON o.id = l.order_id
            ), order_statuses AS (
                SELECT l.order_id, """ + ORDER_STATUS + """
                 AS status
                FROM line_totals l GROUP BY l.order_id
            )
            """;

    @Insert("""
            INSERT INTO purchase_order (order_no, supplier_name, ordered_on, created_by)
            VALUES (#{orderNo}, #{supplierName}, #{orderedOn}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void insertOrder(@Param("key") InsertedId key, @Param("orderNo") String orderNo,
                     @Param("supplierName") String supplierName, @Param("orderedOn") LocalDate orderedOn,
                     @Param("createdBy") String createdBy);

    @Insert("""
            INSERT INTO purchase_order_line (order_id, line_no, item_code, item_name, specification, unit, ordered_qty)
            VALUES (#{orderId}, #{lineNo}, #{line.itemCode}, #{line.itemName}, #{line.specification},
                    #{line.unit}, #{line.orderedQty})
            """)
    void insertLine(@Param("orderId") long orderId, @Param("lineNo") int lineNo, @Param("line") LineInput line);

    @Select(SUMMARIES + """
            SELECT o.id, o.order_no, o.supplier_name, o.ordered_on, o.created_by, o.created_at, s.status
            FROM matching_orders o JOIN order_statuses s ON s.order_id = o.id
            WHERE (#{status,jdbcType=VARCHAR} IS NULL OR s.status = #{status,jdbcType=VARCHAR})
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Summary> list(@Param("id") Long id, @Param("query") String query, @Param("status") Status status,
                       @Param("limit") int limit, @Param("offset") long offset);

    @Select(SUMMARIES + """
            SELECT COUNT(*) FROM order_statuses s
            WHERE (#{status,jdbcType=VARCHAR} IS NULL OR s.status = #{status,jdbcType=VARCHAR})
            """)
    long count(@Param("id") Long id, @Param("query") String query, @Param("status") Status status);

    @Select("""
            SELECT l.id, l.line_no, l.item_code, l.item_name, l.specification, l.unit, l.ordered_qty,
            """ + ACCEPTED_FOR_LINE + """
             AS accepted_qty
            FROM purchase_order_line l
            WHERE l.order_id = #{orderId} ORDER BY l.line_no
            """)
    List<OrderLine> lines(long orderId);

    @Select("SELECT id FROM purchase_order WHERE id = #{orderId} FOR UPDATE")
    Long lockOrder(long orderId);

    @Select("SELECT EXISTS(SELECT 1 FROM purchase_order_line WHERE id = #{lineId} AND order_id = #{orderId})")
    boolean hasLine(@Param("orderId") long orderId, @Param("lineId") long lineId);

    @Select("SELECT purpose FROM document WHERE id = #{documentId} FOR UPDATE")
    String lockDocument(long documentId);

    @Select("SELECT order_id FROM order_import_confirmation WHERE document_id = #{documentId}")
    Long confirmedOrder(long documentId);

    @Select("SELECT status FROM parse_task WHERE document_id = #{documentId}")
    String importTaskStatus(long documentId);

    @Insert("""
            INSERT INTO order_import_confirmation (document_id, order_id, confirmed_by)
            VALUES (#{documentId}, #{orderId}, #{username})
            """)
    void confirmImport(@Param("documentId") long documentId, @Param("orderId") long orderId,
                       @Param("username") String username);

    @Select("""
            SELECT c.document_id, t.id AS task_id
            FROM order_import_confirmation c JOIN parse_task t ON t.document_id = c.document_id
            WHERE c.order_id = #{orderId}
            """)
    ImportSource importSource(long orderId);
}
