package labreview.receipt;

import labreview.receipt.ReceiptReviewModels.Row;
import labreview.receipt.ReceiptReviewModels.Status;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

interface ReceiptReviewMapper {
    @Select("SELECT * FROM receipt_review WHERE order_id=#{orderId} AND document_id=#{documentId}")
    Row byDocument(long orderId, long documentId);

    @Select("SELECT * FROM receipt_review WHERE order_id=#{orderId} AND id=#{id}")
    Row find(long orderId, long id);

    @Select("SELECT * FROM receipt_review WHERE order_id=#{orderId} AND id=#{id} FOR UPDATE")
    Row lock(long orderId, long id);

    @Select("SELECT id FROM receipt_review WHERE order_id=#{orderId} AND receipt_id=#{receiptId}")
    Long byReceipt(long orderId, long receiptId);

    @Insert("""
            INSERT INTO receipt_review (order_id, document_id, task_id, result_hash, review_json, updated_by)
            VALUES (#{orderId}, #{documentId}, #{taskId}, #{resultHash}, #{reviewJson}, #{username})
            ON DUPLICATE KEY UPDATE id=id
            """)
    void open(long orderId, long documentId, long taskId, String resultHash, String reviewJson, String username);

    @Update("""
            UPDATE receipt_review
            SET review_json=#{reviewJson}, version=version+1, updated_by=#{username}, updated_at=CURRENT_TIMESTAMP(6)
            WHERE order_id=#{orderId} AND id=#{id} AND version=#{version} AND status='DRAFT'
            """)
    int save(long orderId, long id, long version, String reviewJson, String username);

    @Update("""
            UPDATE receipt_review
            SET status=#{status}, receipt_id=#{receiptId,jdbcType=BIGINT}, snapshot_json=#{snapshotJson},
                confirmed_by=#{username}, confirmed_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{id} AND status='DRAFT'
            """)
    int finish(long id, Status status, Long receiptId, String snapshotJson, String username);
}
