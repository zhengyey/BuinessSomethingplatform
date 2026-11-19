package labreview.document;

import java.util.List;
import labreview.api.InsertedId;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import labreview.document.DocumentModels.*;

@Mapper
interface DocumentMapper {
    @Insert("""
            INSERT INTO document (purpose, order_id, uploaded_by, original_filename, mime_type, byte_size, sha256,
                                  storage_key)
            VALUES (#{purpose}, #{orderId,jdbcType=BIGINT}, #{uploadedBy}, #{originalFilename}, #{mimeType},
                    #{byteSize}, #{sha256}, #{storageKey})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void insertDocument(@Param("key") InsertedId key, @Param("purpose") Purpose purpose,
                        @Param("orderId") Long orderId, @Param("uploadedBy") String uploadedBy,
                        @Param("originalFilename") String originalFilename, @Param("mimeType") String mimeType,
                        @Param("byteSize") long byteSize, @Param("sha256") String sha256,
                        @Param("storageKey") String storageKey);

    @Insert("INSERT INTO parse_task (document_id, task_type) VALUES (#{documentId}, #{purpose})")
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void enqueue(@Param("key") InsertedId key, @Param("documentId") long documentId, @Param("purpose") Purpose purpose);

    @Select("SELECT EXISTS(SELECT 1 FROM purchase_order WHERE id = #{orderId})")
    boolean orderExists(long orderId);

    @Select("""
            SELECT f.document_id, t.id AS task_id, TRUE AS reused, t.status, r.id AS review_id, r.receipt_id
            FROM delivery_file f JOIN parse_task t ON t.document_id=f.document_id
            LEFT JOIN receipt_review r ON r.document_id=f.document_id
            WHERE f.order_id=#{orderId} AND f.sha256=#{sha256}
            """)
    Uploaded reused(long orderId, String sha256);

    @Insert("INSERT INTO delivery_file (order_id, sha256, document_id) VALUES (#{orderId}, #{sha256}, #{documentId})")
    void registerDelivery(long orderId, String sha256, long documentId);

    @Select("SELECT original_filename, mime_type, byte_size, storage_key FROM document WHERE id = #{id}")
    Original original(long id);

    @Select("""
            SELECT d.id, d.purpose, d.order_id, d.original_filename, d.mime_type, d.byte_size, d.sha256,
                   d.uploaded_by, d.uploaded_at, t.id AS task_id, t.status,
                   CASE WHEN t.status='SUCCEEDED' AND t.result_hash IS NOT NULL
                       AND JSON_EXTRACT(t.result_json, '$.protocolVersion')=2 THEN TRUE ELSE FALSE END AS reviewable,
                   r.id AS review_id, r.status AS review_status
            FROM document d JOIN parse_task t ON t.document_id = d.id
            LEFT JOIN receipt_review r ON r.document_id=d.id
            WHERE d.order_id = #{orderId}
            ORDER BY d.uploaded_at DESC, d.id DESC
            """)
    List<Summary> listForOrder(long orderId);
}
