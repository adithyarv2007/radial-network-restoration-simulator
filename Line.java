/**
 * Physical line/switch. The open/closed and faulted flags are the only mutable state.
 */
public class Line {
    public final int id;
    public final int internalIndex;
    public final int busAId;
    public final int busBId;
    public final int busAIndex;
    public final int busBIndex;
    public final double rOhm;
    public final double xOhm;
    public final double sMaxKva;
    private boolean isClosed;
    private boolean isFaulted;

    /**
     * Worst-case time: O(1) - assigns eleven fields.
     * Auxiliary space: O(1).
     */
    public Line(int id, int internalIndex, int busAId, int busBId, int busAIndex, int busBIndex,
                double rOhm, double xOhm, double sMaxKva, boolean isClosed, boolean isFaulted) {
        this.id = id;
        this.internalIndex = internalIndex;
        this.busAId = busAId;
        this.busBId = busBId;
        this.busAIndex = busAIndex;
        this.busBIndex = busBIndex;
        this.rOhm = rOhm;
        this.xOhm = xOhm;
        this.sMaxKva = sMaxKva;
        this.isClosed = isClosed;
        this.isFaulted = isFaulted;
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - reads a field. */
    public boolean isClosed() { return this.isClosed; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - writes a field. */
    public void setClosed(boolean closed) { this.isClosed = closed; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - reads a field. */
    public boolean isFaulted() { return this.isFaulted; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - writes a field. */
    public void setFaulted(boolean faulted) { this.isFaulted = faulted; }
}
