/**
 * Canonical self-loop token (u -> u) carrying the bus demand and source flag.
 */
public class BusToken extends Token {
    private final int busIndex;
    private final int busId;
    private final double pKw;
    private final double qKvar;
    private final int customers;
    private final int sourceCount;

    /**
     * Worst-case time: O(1) - assigns six fields.
     * Auxiliary space: O(1).
     */
    public BusToken(int busIndex, int busId, double pKw, double qKvar, int customers, boolean hasSource) {
        this.busIndex = busIndex;
        this.busId = busId;
        this.pKw = pKw;
        this.qKvar = qKvar;
        this.customers = customers;
        this.sourceCount = hasSource ? 1 : 0;
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public double getP() { return pKw; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public double getQ() { return qKvar; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getCustomers() { return customers; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getSourceCount() { return sourceCount; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public boolean isBusToken() { return true; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getBusIndex() { return busIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - start vertex equals the bus itself. */
    public int getStartVertex() { return busIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - end vertex equals the bus itself. */
    public int getEndVertex() { return busIndex; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - external bus ID, used for reporting. */
    public int getBusId() { return busId; }
}
