package labreview.order;

public record OrderLine(long id, int lineNo, String itemCode, String itemName, String specification,
                        String unit, int orderedQty, int acceptedQty) {
    public int remainingQty() {
        return orderedQty - acceptedQty;
    }

    public int getRemainingQty() {
        return remainingQty();
    }
}
