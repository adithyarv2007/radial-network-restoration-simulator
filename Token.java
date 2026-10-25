/**
 * Abstract Euler-tour token. Each token is stored in exactly one AVLNode and is never mutated.
 */
public abstract class Token {
    /** Worst-case time: O(1) | Auxiliary space: O(1) - active demand contribution (kW). */
    public abstract double getP();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - reactive demand contribution (kVAr). */
    public abstract double getQ();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - customer contribution. */
    public abstract int getCustomers();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - source contribution (0 or 1). */
    public abstract int getSourceCount();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - true only for BusToken. */
    public abstract boolean isBusToken();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - bus index for BusToken, -1 for EdgeToken. */
    public abstract int getBusIndex();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - tour start vertex. */
    public abstract int getStartVertex();

    /** Worst-case time: O(1) | Auxiliary space: O(1) - tour end vertex. */
    public abstract int getEndVertex();
}
