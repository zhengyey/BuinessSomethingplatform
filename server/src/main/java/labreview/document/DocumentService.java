package labreview.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import labreview.api.ApiException;
import labreview.api.InsertedId;
import labreview.document.DocumentModels.*;
import labreview.order.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private static final HexFormat HEX = HexFormat.of();
    private final DocumentMapper documents;
    private final OrderMapper orders;
    private final Path storageDir;
    private final long maxBytes;

    DocumentService(DocumentMapper documents, OrderMapper orders,
                    @Value("${labreview.documents-dir:../var/documents}") String storageDir,
                    @Value("${labreview.upload-max-bytes:20971520}") long maxBytes) {
        this.documents = documents;
        this.orders = orders;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Uploaded upload(MultipartFile file, Purpose purpose, Long orderId, String username) {
        if (orderId != null) requireOrder(orderId);
        if (file.isEmpty()) throw ApiException.invalid("请选择非空文件", "file");
        if (file.getSize() > maxBytes) throw tooLarge();
        String filename = filename(file.getOriginalFilename());
        FileFormat format = FileFormat.forFilename(filename, purpose);

        Path temporary = null;
        try {
            Files.createDirectories(storageDir);
            temporary = Files.createTempFile(storageDir, "upload-", ".tmp");
            var stored = writeUpload(file, temporary, format);
            if (purpose == Purpose.DELIVERY_NOTE) {
                if (orders.lockOrder(orderId) == null) throw ApiException.missing("采购单不存在");
                var reused = documents.reused(orderId, stored.sha256());
                if (reused != null) return reused;
            }
            String storageKey = UUID.randomUUID().toString();
            Path target = storageDir.resolve(storageKey);
            Files.move(temporary, target);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) deleteTemporary(target);
                }
            });

            var document = new InsertedId();
            var task = new InsertedId();
            documents.insertDocument(document, purpose, orderId, username, filename, format.mimeType,
                    stored.size(), stored.sha256(), storageKey);
            documents.enqueue(task, document.getId(), purpose);
            if (purpose == Purpose.DELIVERY_NOTE) {
                documents.registerDelivery(orderId, stored.sha256(), document.getId());
            }
            return new Uploaded(document.getId(), task.getId(), false, "QUEUED", null, null);
        } catch (IOException error) {
            LOG.error("Unable to store uploaded document", error);
            throw storageError();
        } finally {
            if (temporary != null) deleteTemporary(temporary);
        }
    }

    @Transactional(readOnly = true)
    public List<Summary> listForOrder(long orderId) {
        requireOrder(orderId);
        return documents.listForOrder(orderId);
    }

    public ResponseEntity<Resource> original(long documentId) {
        return original(documentId, true);
    }

    ResponseEntity<Resource> original(long documentId, boolean download) {
        var document = documents.original(documentId);
        if (document == null) throw ApiException.missing("材料不存在");
        Path path = storageDir.resolve(document.storageKey()).normalize();
        if (!storageDir.equals(path.getParent()) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            LOG.error("Original file unavailable for document {}", documentId);
            throw storageError();
        }
        var disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.mimeType()))
                .contentLength(document.byteSize())
                .cacheControl(CacheControl.noStore())
                .headers(headers -> headers.setContentDisposition(
                        disposition.filename(document.originalFilename(), StandardCharsets.UTF_8).build()))
                .body(new FileSystemResource(path));
    }

    private Stored writeUpload(MultipartFile file, Path target, FileFormat format) throws IOException {
        var digest = sha256();
        try (var input = file.getInputStream(); var output = Files.newOutputStream(target)) {
            byte[] header = input.readNBytes(format.signature.length);
            if (!MessageDigest.isEqual(header, format.signature)) throw unsupportedType();
            long size = header.length;
            if (size > maxBytes) throw tooLarge();
            output.write(header);
            digest.update(header);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > maxBytes) throw tooLarge();
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            return new Stored(size, HEX.formatHex(digest.digest()));
        }
    }

    private void requireOrder(long orderId) {
        if (!documents.orderExists(orderId)) throw ApiException.missing("采购单不存在");
    }

    private static String filename(String original) {
        if (original == null) throw ApiException.invalid("缺少文件名", "file");
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).strip();
        if (name.isEmpty() || name.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalid("文件名无效", "file");
        }
        return name;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            LOG.warn("Unable to remove uncommitted document {}", path.getFileName(), error);
        }
    }

    private static ApiException storageError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_STORAGE_ERROR", "材料存储暂不可用", "file");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "文件超过上传大小限制", "file");
    }

    private static ApiException unsupportedType() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                "文件类型不受支持或文件内容与扩展名不一致", "file");
    }

    private record Stored(long size, String sha256) {}

    private enum FileFormat {
        PDF("application/pdf", "255044462d"),
        JPEG("image/jpeg", "ffd8ff"),
        PNG("image/png", "89504e470d0a1a0a");

        private final String mimeType;
        private final byte[] signature;

        FileFormat(String mimeType, String signature) {
            this.mimeType = mimeType;
            this.signature = HEX.parseHex(signature);
        }

        static FileFormat forFilename(String filename, Purpose purpose) {
            int dot = filename.lastIndexOf('.');
            if (dot <= 0) throw unsupportedType();
            var format = switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
                case "pdf" -> PDF;
                case "jpg", "jpeg" -> JPEG;
                case "png" -> PNG;
                default -> throw unsupportedType();
            };
            if (purpose == Purpose.ORDER_IMPORT && format != PDF) throw unsupportedType();
            return format;
        }
    }
}
