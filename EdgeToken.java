/**
 * Directed traversal (from -> to) of one physical line. Carries zero demand and zero sources.
 */
public class EdgeToken extends Token {
    private final int fromBusIndex;
    private final int toBusIndex;
    private final int lineId;

    /**
     * Worst-case time: O(1) - assigns three fields.
     * Auxiliary space: O(1).
     */
    public EdgeToken(int fromBusIndex, int toBusIndex, int lineId) {
        this.fromBusIndex = fromBusIndex;
        this.toBusIndex = toBusIndex;
        this.lineId = lineId;
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - edges contribute 0.0 kW. */
    public double getP() { return 0.0; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - edges contribute 0.0 kVAr. */
    public double getQ() { return 0.0; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - edges contribute 0 customers. */
    public int getCustomers() { return 0; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - edges contribute 0 sources. */
    public int getSourceCount() { return 0; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public boolean isBusToken() { return false; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getBusIndex() { return -1; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getStartVertex() { return fromBusIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getEndVertex() { return toBusIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getFromBusIndex() { return fromBusIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getToBusIndex() { return toBusIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getLineId() { return lineId; }
}
