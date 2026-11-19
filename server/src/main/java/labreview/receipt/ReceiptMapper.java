package labreview.receipt;

import java.time.LocalDate;
import java.util.List;
import labreview.api.InsertedId;
import labreview.receipt.ReceiptModels.History;
import labreview.receipt.ReceiptModels.LineInput;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

interface ReceiptMapper {
    @Insert("""
            INSERT INTO receipt (order_id, delivery_note_no, received_on, confirmed_by, note_status,
                unknown_note_reason, duplicate_review_reason, duplicate_candidates, supplier_key, note_no_key)
            VALUES (#{orderId}, #{input.deliveryNoteNo,jdbcType=VARCHAR}, #{input.receivedOn}, #{confirmedBy},
                #{input.noteStatus}, #{input.unknownNoteReason,jdbcType=VARCHAR}, #{input.duplicateReviewReason,jdbcType=VARCHAR},
                #{candidates,jdbcType=VARCHAR}, #{supplier}, #{noteKey,jdbcType=VARBINARY})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void insertReceipt(@Param("key") InsertedId key, @Param("orderId") long orderId,
                       @Param("input") ReceiptModels.CreateReceipt input, @Param("confirmedBy") String confirmedBy,
                       @Param("supplier") byte[] supplier, @Param("noteKey") byte[] noteKey,
                       @Param("candidates") String candidates);

    @Insert("""
            INSERT INTO receipt_line (receipt_id, order_line_id, delivered_qty, accepted_qty, note)
            VALUES (#{receiptId}, #{line.orderLineId}, #{line.deliveredQty,jdbcType=INTEGER},
                    #{line.acceptedQty}, #{line.note,jdbcType=VARCHAR})
            """)
    void insertLine(@Param("receiptId") long receiptId, @Param("line") LineInput line);

    @Select("""
            SELECT rl.id, rl.id AS receipt_line_id, rl.receipt_id, r.delivery_note_no, r.received_on, r.confirmed_by,
                   r.confirmed_at, rl.delivered_qty, rl.accepted_qty, rl.note, rl.original_accepted_qty,
                   rl.accepted_qty AS effective_accepted_qty, rl.revision
            FROM effective_receipt_line rl
            JOIN receipt r ON r.id = rl.receipt_id
            WHERE r.order_id = #{orderId} AND rl.order_line_id = #{lineId}
            ORDER BY r.confirmed_at, rl.id
            """)
    List<History> history(@Param("orderId") long orderId, @Param("lineId") long lineId);

    @Select("""
            SELECT id, delivery_note_no, received_on, confirmed_by, confirmed_at, note_status, unknown_note_reason,
                   duplicate_review_reason, duplicate_candidates
            FROM receipt WHERE order_id=#{orderId} AND id=#{receiptId}
            """)
    ReceiptModels.Header header(long orderId, long receiptId);

    @Select("""
            SELECT l.id AS receipt_line_id, l.order_line_id, o.item_code, o.item_name, o.specification, o.unit,
                   l.delivered_qty, l.accepted_qty, l.note, l.original_accepted_qty, l.accepted_qty AS effective_accepted_qty,
                   l.revision, o.ordered_qty - COALESCE((
                       SELECT SUM(r2.accepted_qty) FROM effective_receipt_line r2 WHERE r2.order_line_id=o.id
                   ), 0) AS remaining_qty
            FROM effective_receipt_line l JOIN purchase_order_line o ON o.id=l.order_line_id
            WHERE l.receipt_id=#{receiptId} ORDER BY o.line_no
            """)
    List<ReceiptModels.DetailLine> details(long receiptId);

    @Select("""
            SELECT l.id FROM receipt_line l JOIN receipt r ON r.id=l.receipt_id
            WHERE l.id=#{lineId} AND r.id=#{receiptId} AND r.order_id=#{orderId} FOR UPDATE
            """)
    Long lockLine(long orderId, long receiptId, long lineId);

    @Select("SELECT order_line_id, accepted_qty, revision FROM effective_receipt_line WHERE id=#{lineId}")
    ReceiptModels.AdjustmentTarget target(long lineId);

    @Insert("""
            INSERT INTO receipt_adjustment (receipt_line_id, revision, before_qty, after_qty, reason, actor)
            VALUES (#{lineId}, #{revision}, #{before}, #{after}, #{reason}, #{actor})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void adjust(@Param("key") InsertedId key, @Param("lineId") long lineId, @Param("revision") int revision,
                @Param("before") int before, @Param("after") int after, @Param("reason") String reason,
                @Param("actor") String actor);

    @Select("""
            SELECT a.id, a.receipt_line_id, a.revision, a.before_qty, a.after_qty, a.reason, a.actor, a.created_at
            FROM receipt_adjustment a JOIN receipt_line l ON l.id=a.receipt_line_id
            WHERE l.receipt_id=#{receiptId} ORDER BY a.receipt_line_id, a.revision
            """)
    List<ReceiptModels.Adjustment> adjustments(long receiptId);
}
