/**
 * Electrical screening result for one energized component.
 */
public class ViolationReport {
    public final boolean isEnergized;
    public final int violationCount;
    public final String[] violations;
    public final double minVoltagePu;
    public final double maxLineLoadingPercent;
    public final double sourceLoadingPercent;

    /**
     * Worst-case time: O(1) - stores references and scalars.
     * Auxiliary space: O(1).
     */
    public ViolationReport(boolean isEnergized, int violationCount, String[] violations,
                           double minVoltagePu, double maxLineLoadingPercent, double sourceLoadingPercent) {
        this.isEnergized = isEnergized;
        this.violationCount = violationCount;
        this.violations = violations;
        this.minVoltagePu = minVoltagePu;
        this.maxLineLoadingPercent = maxLineLoadingPercent;
        this.sourceLoadingPercent = sourceLoadingPercent;
    }
}
