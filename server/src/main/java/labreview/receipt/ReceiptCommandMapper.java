package labreview.receipt;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

interface ReceiptCommandMapper {
    record Stored(String requestHash, String resultJson) {}

    @Select("""
            SELECT request_hash, result_json FROM receipt_command
            WHERE operation=#{operation} AND resource_id=#{resource} AND request_key=#{key}
            """)
    Stored find(String operation, long resource, String key);

    @Insert("""
            INSERT INTO receipt_command (operation, resource_id, request_key, request_hash, result_json)
            VALUES (#{operation}, #{resource}, #{key}, #{hash}, #{result})
            """)
    void store(String operation, long resource, String key, String hash, String result);
}
