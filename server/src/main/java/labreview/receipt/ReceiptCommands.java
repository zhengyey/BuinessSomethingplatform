package labreview.receipt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import labreview.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ReceiptCommands {
    private final ReceiptCommandMapper commands;
    private final ObjectMapper mapper;

    ReceiptCommands(ReceiptCommandMapper commands, ObjectMapper mapper) {
        this.commands = commands;
        this.mapper = mapper;
    }

    <T> T replay(String operation, long resource, String key, String hash, Class<T> type) {
        if (key == null) return null;
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.invalid("请求键格式无效", "Idempotency-Key");
        }
        var previous = commands.find(operation, resource, key);
        if (previous == null) return null;
        if (!previous.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一请求键已用于不同内容", null);
        }
        return mapper.readValue(previous.resultJson(), type);
    }

    void store(String operation, long resource, String key, String hash, Object result) {
        if (key != null) commands.store(operation, resource, key, hash, json(result));
    }

    String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    String json(Object value) {
        return mapper.writeValueAsString(value);
    }
}
