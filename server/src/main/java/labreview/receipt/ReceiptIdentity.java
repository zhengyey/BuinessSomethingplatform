package labreview.receipt;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import labreview.api.ApiException;
import labreview.order.OrderMapper;
import labreview.receipt.ReceiptModels.CreateReceipt;
import labreview.receipt.ReceiptModels.DetailLine;
import labreview.receipt.ReceiptModels.NoteStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReceiptIdentity {
    private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$");
    private final ReceiptIdentityMapper identities;
    private final ReceiptMapper receipts;
    private final OrderMapper orders;
    private final ReceiptCommands commands;

    ReceiptIdentity(ReceiptIdentityMapper identities, ReceiptMapper receipts, OrderMapper orders, ReceiptCommands commands) {
        this.identities = identities;
        this.receipts = receipts;
        this.orders = orders;
        this.commands = commands;
    }

    record CheckInput(@Size(max = 64) String deliveryNoteNo, NoteStatus noteStatus,
                      @NotNull LocalDate receivedOn, @NotNull @Size(max = 1000) List<@NotNull @Positive Long> orderLineIds) {}
    record Reference(long receiptId, long orderId, String orderNo, String deliveryNoteNo,
                     LocalDate receivedOn, Long documentId) {}
    record Candidate(Reference reference, List<DetailLine> lines) {}
    record Match(String status, String candidateToken, List<Candidate> candidates) {}
    record Evidence(byte[] supplier, byte[] noteKey, String candidates) {}

    @Transactional(readOnly = true)
    public Match check(long orderId, CheckInput input) {
        byte[] supplier = supplier(orderId);
        var noteKey = noteKey(input.noteStatus(), input.deliveryNoteNo());
        if (noteKey != null) return match("EXACT", identities.exact(supplier, noteKey, 0), null);
        validateDate(input.receivedOn());
        var possible = possible(orderId, input.orderLineIds(), supplier, input.receivedOn());
        return possibleMatch(supplier, input.receivedOn(), possible);
    }

    Evidence checkBeforePosting(long orderId, CreateReceipt input, Long documentId) {
        byte[] supplier = supplier(orderId);
        byte[] key = noteKey(input.noteStatus(), input.deliveryNoteNo());
        validateDate(input.receivedOn());
        identities.lockDay(supplier, input.receivedOn());
        if (documentId != null) {
            rejectExact(identities.sameFile(orderId, documentId));
        }
        if (key != null) {
            rejectExact(identities.exact(supplier, key, 0));
            return new Evidence(supplier, key, null);
        }
        if (clean(input.unknownNoteReason()) == null) {
            throw ApiException.invalid("请填写单号无法核实的原因", "unknownNoteReason");
        }
        var refs = possible(orderId, input.lines().stream().map(ReceiptModels.LineInput::orderLineId).toList(),
                supplier, input.receivedOn());
        var token = token(supplier, input.receivedOn(), refs);
        if ((!refs.isEmpty() || input.candidateToken() != null)
                && (!Objects.equals(input.candidateToken(), token)
                    || (!refs.isEmpty() && clean(input.duplicateReviewReason()) == null))) {
            throw new ApiException(HttpStatus.CONFLICT, "POSSIBLE_DUPLICATE_RECEIPT",
                    refs.isEmpty() ? "候选记录已变化，请重新核实" : "可能是同一次到货，请核实已有记录",
                    "duplicateReviewReason", possibleMatch(supplier, input.receivedOn(), refs));
        }
        return new Evidence(supplier, null, commands.json(refs.stream().map(Reference::receiptId).toList()));
    }

    void claim(Evidence evidence, long receiptId) {
        if (evidence.noteKey() == null) return;
        identities.claim(evidence.supplier(), evidence.noteKey(), receiptId);
        if (identities.claimedBy(evidence.supplier(), evidence.noteKey()) != receiptId) {
            rejectExact(identities.exact(evidence.supplier(), evidence.noteKey(), receiptId));
            throw new IllegalStateException("Claimed receipt identity has no ledger record");
        }
    }

    private List<Reference> possible(long orderId, List<Long> ids, byte[] supplier, LocalDate date) {
        var selected = new HashSet<>(ids);
        var lines = orders.lines(orderId).stream().filter(line -> selected.contains(line.id())).toList();
        if (lines.size() != selected.size()) throw ApiException.invalid("所选物料不属于当前采购单", "orderLineIds");
        var codes = lines.stream().map(labreview.order.OrderLine::itemCode).distinct().toList();
        return codes.isEmpty() ? List.of() : identities.possible(supplier, date, codes);
    }

    private Match possibleMatch(byte[] supplier, LocalDate date, List<Reference> refs) {
        return match("POSSIBLE", refs, token(supplier, date, refs));
    }

    private String token(byte[] supplier, LocalDate date, List<Reference> refs) {
        return commands.hash(List.of(supplier, date, refs.stream().map(Reference::receiptId).toList()));
    }

    private Match match(String status, List<Reference> refs, String token) {
        return new Match(refs.isEmpty() ? "NONE" : status, token,
                refs.stream().map(ref -> new Candidate(ref, receipts.details(ref.receiptId()))).toList());
    }

    private void rejectExact(List<Reference> refs) {
        if (refs.isEmpty()) return;
        throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RECEIPT", "已处理，请查看原收货记录",
                "deliveryNoteNo", match("EXACT", refs, null));
    }

    private byte[] supplier(long orderId) {
        String name = identities.supplier(orderId);
        if (name == null) throw ApiException.missing("采购单不存在");
        return EDGE_SPACE.matcher(name).replaceAll("").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] noteKey(NoteStatus status, String value) {
        if (status == NoteStatus.UNKNOWN) {
            if (clean(value) != null) throw ApiException.invalid("单号无法核实时请清空签收单号", "deliveryNoteNo");
            return null;
        }
        String note = clean(value);
        if (note == null) throw ApiException.invalid("请输入签收单号", "deliveryNoteNo");
        var key = new StringBuilder(note.length());
        note.codePoints().forEach(c -> key.appendCodePoint(c >= 'a' && c <= 'z' ? c - 'a' + 'A' : c));
        return key.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String clean(String value) {
        if (value == null) return null;
        String trimmed = EDGE_SPACE.matcher(value).replaceAll("");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void validateDate(LocalDate date) {
        if (date.getYear() < 1000 || date.getYear() > 9999) {
            throw ApiException.invalid("到货日期须使用 YYYY-MM-DD", "receivedOn");
        }
    }
}
