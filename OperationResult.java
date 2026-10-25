/**
 * Result of one simulator command. components is null for rejected commands, whose state is unchanged.
 */
public class OperationResult {
    public final String operationText;
    public final boolean accepted;
    public final String statusMessage;
    public final ComponentStatus[] components;
    public final double totalUnservedPKw;
    public final double totalUnservedQKvar;
    public final int totalDisconnectedCustomers;
    public final int totalElectricalViolations;

    /**
     * Worst-case time: O(1) - stores references and scalars.
     * Auxiliary space: O(1).
     */
    public OperationResult(String operationText, boolean accepted, String statusMessage,
                           ComponentStatus[] components, double totalUnservedPKw,
                           double totalUnservedQKvar, int totalDisconnectedCustomers,
                           int totalElectricalViolations) {
        this.operationText = operationText;
        this.accepted = accepted;
        this.statusMessage = statusMessage;
        this.components = components;
        this.totalUnservedPKw = totalUnservedPKw;
        this.totalUnservedQKvar = totalUnservedQKvar;
        this.totalDisconnectedCustomers = totalDisconnectedCustomers;
        this.totalElectricalViolations = totalElectricalViolations;
    }
}
