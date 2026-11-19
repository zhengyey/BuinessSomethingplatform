package labreview.document;

import java.security.Principal;
import java.util.List;
import labreview.api.ApiException;
import labreview.document.DocumentModels.*;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class DocumentController {
    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(value = "/order-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Uploaded> uploadImport(@RequestParam("file") List<MultipartFile> files, Principal principal) {
        return ResponseEntity.accepted()
                .body(documents.upload(singleFile(files), Purpose.ORDER_IMPORT, null, principal.getName()));
    }

    @PostMapping(value = "/orders/{orderId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Uploaded> uploadDelivery(@PathVariable long orderId, @RequestParam("file") List<MultipartFile> files,
                                            Principal principal) {
        var uploaded = documents.upload(singleFile(files), Purpose.DELIVERY_NOTE, orderId, principal.getName());
        return uploaded.reused() ? ResponseEntity.ok(uploaded) : ResponseEntity.accepted().body(uploaded);
    }

    @GetMapping("/orders/{orderId}/documents")
    List<Summary> list(@PathVariable long orderId) {
        return documents.listForOrder(orderId);
    }

    @GetMapping("/documents/{id}/content")
    ResponseEntity<Resource> original(@PathVariable long id, @RequestParam(defaultValue = "false") boolean download) {
        return documents.original(id, download);
    }

    private static MultipartFile singleFile(List<MultipartFile> files) {
        if (files.size() != 1) throw ApiException.invalid("请选择一份文件上传", "file");
        return files.getFirst();
    }
}
