package labreview.receipt;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

interface ReceiptIdentityMapper {
    @Select("SELECT supplier_name FROM purchase_order WHERE id=#{orderId}")
    String supplier(long orderId);

    String REFERENCES = """
            SELECT r.id AS receipt_id, r.order_id, o.order_no, r.delivery_note_no, r.received_on,
                   v.document_id
            FROM receipt r JOIN purchase_order o ON o.id=r.order_id
            LEFT JOIN receipt_review v ON v.receipt_id=r.id
            """;

    @Select(REFERENCES + """
            WHERE r.supplier_key=#{supplier} AND r.note_no_key=#{noteKey} AND r.id<>#{exclude}
            ORDER BY r.id
            """)
    List<ReceiptIdentity.Reference> exact(byte[] supplier, byte[] noteKey, long exclude);

    @Select(REFERENCES + """
            JOIN document d ON d.id=v.document_id
            JOIN document current_file ON current_file.id=#{documentId}
            WHERE d.order_id=#{orderId} AND current_file.order_id=#{orderId} AND d.sha256=current_file.sha256
            ORDER BY r.id
            """)
    List<ReceiptIdentity.Reference> sameFile(long orderId, long documentId);

    @Select("""
            <script>
            """ + REFERENCES + """
            WHERE r.supplier_key=#{supplier} AND r.received_on=#{receivedOn} AND EXISTS (
                SELECT 1 FROM receipt_line l JOIN purchase_order_line p ON p.id=l.order_line_id
                WHERE l.receipt_id=r.id AND BINARY p.item_code IN
                <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            )
            ORDER BY r.id
            </script>
            """)
    List<ReceiptIdentity.Reference> possible(byte[] supplier, LocalDate receivedOn, List<String> codes);

    @Insert("""
            INSERT INTO receipt_supplier_day (supplier_key, received_on) VALUES (#{supplier}, #{receivedOn})
            ON DUPLICATE KEY UPDATE received_on=receipt_supplier_day.received_on
            """)
    void lockDay(byte[] supplier, LocalDate receivedOn);

    @Insert("""
            INSERT INTO receipt_identity (supplier_key, note_no_key, receipt_id)
            VALUES (#{supplier}, #{noteKey}, #{receiptId})
            ON DUPLICATE KEY UPDATE receipt_id=receipt_identity.receipt_id
            """)
    void claim(byte[] supplier, byte[] noteKey, long receiptId);

    @Select("SELECT receipt_id FROM receipt_identity WHERE supplier_key=#{supplier} AND note_no_key=#{noteKey}")
    long claimedBy(byte[] supplier, byte[] noteKey);
}
