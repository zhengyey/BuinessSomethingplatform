package labreview.task;

import java.util.List;
import labreview.task.TaskModels.*;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

interface TaskMapper {
    String FILTERS = """
            WHERE (#{status,jdbcType=VARCHAR} IS NULL OR t.status=#{status,jdbcType=VARCHAR})
              AND (#{purpose,jdbcType=VARCHAR} IS NULL OR t.task_type=#{purpose,jdbcType=VARCHAR})
            """;
    String ROW_SQL = """
            SELECT t.id, t.document_id, t.task_type AS purpose, t.status, d.original_filename, t.created_at,
                   t.attempt_no, t.worker_id,
                   COALESCE(t.lease_until > CURRENT_TIMESTAMP(6), FALSE) AS lease_live,
                   d.order_id, d.mime_type, d.byte_size, d.sha256, d.uploaded_by, d.uploaded_at,
                   c.order_id AS confirmed_order_id, t.failure_code, t.failure_message, t.retryable,
                   t.parser_version, t.result_json, t.result_hash
            FROM parse_task t JOIN document d ON d.id=t.document_id
            LEFT JOIN order_import_confirmation c ON c.document_id=t.document_id
            WHERE t.id=#{id}
            """;

    @Select("""
            SELECT t.id, t.document_id, t.task_type AS purpose, t.status, d.original_filename,
                   t.created_at, t.attempt_no
            FROM parse_task t JOIN document d ON d.id=t.document_id
            """ + FILTERS + " ORDER BY t.created_at DESC, t.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Item> list(@Param("status") Status status, @Param("purpose") Purpose purpose,
                    @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM parse_task t " + FILTERS)
    long count(@Param("status") Status status, @Param("purpose") Purpose purpose);

    @Select(ROW_SQL)
    Row row(long id);

    @Select("""
            SELECT t.id FROM parse_task t JOIN document d ON d.id=t.document_id
            WHERE d.id=#{documentId} AND d.order_id=#{orderId}
                AND d.purpose='DELIVERY_NOTE' AND t.task_type='DELIVERY_NOTE'
            """)
    Long reviewTask(long orderId, long documentId);

    @Select("SELECT id FROM parse_task WHERE id=#{id} FOR UPDATE")
    Long lock(long id);

    @Select("""
            SELECT id FROM parse_task
            WHERE cycle_attempts < #{limit} AND (
                (status='QUEUED' AND available_at <= CURRENT_TIMESTAMP(6))
                OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6))
            )
            ORDER BY CASE WHEN status='QUEUED' THEN available_at ELSE lease_until END, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Long next(int limit);

    @Update("""
            UPDATE parse_task SET status='RUNNING', worker_id=#{worker}, attempt_no=attempt_no+1,
                cycle_attempts=cycle_attempts+1,
                lease_until=TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP(6))
            WHERE id=#{id}
            """)
    void take(@Param("id") long id, @Param("worker") String worker, @Param("seconds") int seconds);

    @Update("""
            UPDATE parse_task SET lease_until=TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP(6))
            WHERE id=#{id} AND status='RUNNING' AND worker_id=#{worker}
              AND attempt_no=#{attempt} AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int heartbeat(@Param("id") long id, @Param("worker") String worker, @Param("attempt") long attempt,
                  @Param("seconds") int seconds);

    @Select("""
            SELECT id FROM parse_task FORCE INDEX (ix_parse_task_lease)
            WHERE status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND cycle_attempts >= #{limit}
            ORDER BY lease_until, id
            LIMIT 100 FOR UPDATE SKIP LOCKED
            """)
    List<Long> exhausted(int limit);

    @Update("""
            UPDATE parse_task SET status='FAILED', lease_until=NULL, retryable=TRUE,
                failure_code='LEASE_EXPIRED', failure_message='处理服务多次中断，可重新排队。',
                completed_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{id}
            """)
    void expireExhausted(long id);

    @Update("""
            UPDATE parse_task SET status='QUEUED', worker_id=NULL, lease_until=NULL, cycle_attempts=0,
                available_at=CURRENT_TIMESTAMP(6), completed_at=NULL,
                failure_code=NULL, failure_message=NULL, retryable=FALSE
            WHERE id=#{id} AND status='FAILED' AND retryable=TRUE
              AND NOT EXISTS (SELECT 1 FROM order_import_confirmation c WHERE c.document_id=parse_task.document_id)
            """)
    int retry(long id);

    @Update("""
            UPDATE parse_task
            SET status=IF(#{retryable} AND cycle_attempts < #{limit}, 'QUEUED', 'FAILED'),
                available_at=TIMESTAMPADD(SECOND, 5, CURRENT_TIMESTAMP(6)),
                completed_at=IF(#{retryable} AND cycle_attempts < #{limit}, NULL, CURRENT_TIMESTAMP(6)),
                lease_until=NULL, failure_code=#{code}, failure_message=#{message}, retryable=#{retryable}
            WHERE id=#{id}
            """)
    void fail(@Param("id") long id, @Param("code") String code, @Param("message") String message,
              @Param("retryable") boolean retryable, @Param("limit") int limit);

    @Insert("""
            INSERT INTO parse_page(task_id, page_no, status, extracted_text, hints_json)
            VALUES(#{id},#{number},#{status},#{text,jdbcType=LONGVARCHAR},#{hints})
            """)
    void insertPage(@Param("id") long id, @Param("number") int number, @Param("status") PageStatus status,
                    @Param("text") String text, @Param("hints") String hints);

    @Select("""
            SELECT page_no, status, extracted_text AS text, hints_json FROM parse_page
            WHERE task_id=#{id} ORDER BY page_no
            """)
    List<PageRow> pages(long id);

    @Update("""
            UPDATE parse_task SET status='SUCCEEDED', lease_until=NULL, completed_at=CURRENT_TIMESTAMP(6),
                failure_code=NULL, failure_message=NULL, retryable=FALSE,
                parser_version=#{version}, result_json=#{result}, result_hash=#{hash}
            WHERE id=#{id}
            """)
    void complete(@Param("id") long id, @Param("version") String version,
                  @Param("result") String result, @Param("hash") String hash);
}
