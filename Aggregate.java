/**
 * Immutable snapshot of a component's demand and source totals.
 */
public class Aggregate {
    public final double totalPKw;
    public final double totalQKvar;
    public final int totalCustomers;
    public final int sourceCount;

    /**
     * Worst-case time: O(1) - assigns four fields.
     * Auxiliary space: O(1).
     */
    public Aggregate(double totalPKw, double totalQKvar, int totalCustomers, int sourceCount) {
        this.totalPKw = totalPKw;
        this.totalQKvar = totalQKvar;
        this.totalCustomers = totalCustomers;
        this.sourceCount = sourceCount;
    }
}
