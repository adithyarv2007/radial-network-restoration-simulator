/**
 * Snapshot of one connected component: membership, source status, demand and electrical report.
 */
public class ComponentStatus {
    public final int componentId;
    public final int[] busIds;
    public final boolean hasSource;
    public final int sourceBusId;
    public final double totalPKw;
    public final double totalQKvar;
    public final int totalCustomers;
    public final ViolationReport electricalReport;

    /**
     * Worst-case time: O(1) - stores references and scalars.
     * Auxiliary space: O(1).
     */
    public ComponentStatus(int componentId, int[] busIds, boolean hasSource, int sourceBusId,
                           double totalPKw, double totalQKvar, int totalCustomers,
                           ViolationReport electricalReport) {
        this.componentId = componentId;
        this.busIds = busIds;
        this.hasSource = hasSource;
        this.sourceBusId = sourceBusId;
        this.totalPKw = totalPKw;
        this.totalQKvar = totalQKvar;
        this.totalCustomers = totalCustomers;
        this.electricalReport = electricalReport;
    }
}
