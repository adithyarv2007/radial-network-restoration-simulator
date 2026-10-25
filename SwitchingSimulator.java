import java.util.Arrays;

/**
 * Topology-first switching simulator. Hard topological preconditions gate every command.
 * Accepted commands update the ETT and then run the electrical screen, which only adds warnings.
 * Rejected commands leave every line flag and every ETT pointer unchanged.
 */
public class SwitchingSimulator {
    private final Bus[] buses;
    private final Line[] lines;
    private final int[] adjHead;
    private final int[] adjNext;
    private final int[] adjLineIdx;
    private final EulerTourTree ett;
    private final ElectricalChecker checker;
    private int totalCommands;
    private int acceptedCommands;
    private int rejectedCommands;
    private double unservedPKw;
    private double unservedQKvar;
    private int unservedCustomers;
    private int lastViolationCount;
    private double initialUnservedPKw;
    private double initialUnservedQKvar;
    private int initialDisconnectedCustomers;
    private OperationResult initialState;
    private String initError;

    /**
     * Sorts buses and lines by ID, builds the CSR-style adjacency, initializes the ETT, links the initially
     * closed healthy lines (checking cycles and source counts), and evaluates the initial state.
     * Worst-case time: O(V log V + E log E + E log V + V + E) - sorting, linking, and one full evaluation.
     * Auxiliary space: O(V + E) - buses, lines, adjacency and ETT tables.
     */
    public SwitchingSimulator(Bus[] rawBuses, Line[] rawLines) {
        int numBuses = rawBuses.length;
        int numLines = rawLines.length;

        long[] busKeys = new long[numBuses];
        for (int i = 0; i < numBuses; i++) busKeys[i] = sortKey(rawBuses[i].id, i);
        Arrays.sort(busKeys);
        this.buses = new Bus[numBuses];
        for (int k = 0; k < numBuses; k++) {
            Bus b = rawBuses[keyIndex(busKeys[k])];
            this.buses[k] = new Bus(b.id, k, b.pKw, b.qKvar, b.customerCount, b.hasSubstation,
                    b.vBaseKv, b.sourceCapacityKva, b.sourceVoltagePu);
        }

        long[] lineKeys = new long[numLines];
        for (int i = 0; i < numLines; i++) lineKeys[i] = sortKey(rawLines[i].id, i);
        Arrays.sort(lineKeys);
        this.lines = new Line[numLines];
        String err = null;
        for (int k = 0; k < numLines; k++) {
            Line r = rawLines[keyIndex(lineKeys[k])];
            int a = findBusIndexById(r.busAId);
            int b = findBusIndexById(r.busBId);
            if (a < 0 || b < 0 || a == b) {
                if (err == null) err = "line " + r.id + " references an unknown bus or a self-loop";
                a = 0;
                b = 0;
            }
            // Faulted lines are forced open; closed flag is only trusted for healthy lines.
            boolean closed = r.isClosed() && !r.isFaulted();
            this.lines[k] = new Line(r.id, k, r.busAId, r.busBId, a, b, r.rOhm, r.xOhm, r.sMaxKva,
                    closed, r.isFaulted());
        }

        this.adjHead = new int[numBuses];
        Arrays.fill(adjHead, -1);
        this.adjNext = new int[2 * numLines];
        this.adjLineIdx = new int[2 * numLines];
        for (int e = 0; e < numLines; e++) {
            int a = lines[e].busAIndex;
            int b = lines[e].busBIndex;
            adjLineIdx[2 * e] = e;
            adjNext[2 * e] = adjHead[a];
            adjHead[a] = 2 * e;
            adjLineIdx[2 * e + 1] = e;
            adjNext[2 * e + 1] = adjHead[b];
            adjHead[b] = 2 * e + 1;
        }

        this.ett = new EulerTourTree(numBuses, numLines);
        for (int i = 0; i < numBuses; i++) ett.initBus(buses[i]);
        this.checker = new ElectricalChecker();
        this.initError = err;

        for (int e = 0; e < numLines && initError == null; e++) {
            Line ln = lines[e];
            if (!ln.isClosed()) continue;
            int a = ln.busAIndex;
            int b = ln.busBIndex;
            if (ett.connected(a, b)) {
                initError = "initially closed lines form a cycle at line " + ln.id;
            } else {
                Aggregate ga = ett.getAggregate(a);
                Aggregate gb = ett.getAggregate(b);
                if (ga.sourceCount + gb.sourceCount > 1) {
                    initError = "initially closed lines join two substations at line " + ln.id;
                } else {
                    ett.link(a, b, e, ln.id);
                }
            }
        }

        if (initError == null) {
            initialState = evaluateCurrentState("INITIAL STATE", true, "initial configuration accepted");
            initialUnservedPKw = unservedPKw;
            initialUnservedQKvar = unservedQKvar;
            initialDisconnectedCustomers = unservedCustomers;
        } else {
            initialState = new OperationResult("INITIAL STATE", false, initError, null, 0.0, 0.0, 0, 0);
        }
    }

    /**
     * Opens a closed, healthy line and cuts it from the ETT.
     * Worst-case time: O(log E) if rejected; O(log E + log V + V log V + E) if accepted (cut + full evaluation).
     * Auxiliary space: O(V) - evaluation snapshot.
     */
    public OperationResult open(int lineId) {
        totalCommands++;
        String op = "OPEN(" + lineId + ")";
        int li = findLineIndexById(lineId);
        if (li < 0) return reject(op, "lineId not found");
        Line ln = lines[li];
        if (!ln.isClosed()) {
            return reject(op, ln.isFaulted() ? "line is faulted and already open" : "line is already open");
        }
        ett.cut(ln.busAIndex, ln.busBIndex, li);
        ln.setClosed(false);
        acceptedCommands++;
        return evaluateCurrentState(op, true, "line opened; topology updated");
    }

    /**
     * Closes an open, healthy line after the cycle and single-source checks.
     * Worst-case time: O(log E + log V) if rejected; O(log E + log V + V log V + E) if accepted.
     * Auxiliary space: O(V) - evaluation snapshot.
     */
    public OperationResult close(int lineId) {
        totalCommands++;
        String op = "CLOSE(" + lineId + ")";
        int li = findLineIndexById(lineId);
        if (li < 0) return reject(op, "lineId not found");
        Line ln = lines[li];
        if (ln.isClosed()) return reject(op, "line is already closed");
        if (ln.isFaulted()) return reject(op, "line is faulted; CLEAR_FAULT is required before closing");
        int a = ln.busAIndex;
        int b = ln.busBIndex;
        if (ett.connected(a, b)) {
            return reject(op, "cycle detected: both endpoints already belong to the same radial tree");
        }
        Aggregate ga = ett.getAggregate(a);
        Aggregate gb = ett.getAggregate(b);
        if (ga.sourceCount + gb.sourceCount > 1) {
            return reject(op, "multi-source conflict: both endpoint components contain a substation source");
        }
        ett.link(a, b, li, ln.id);
        ln.setClosed(true);
        acceptedCommands++;
        return evaluateCurrentState(op, true, "line closed; topology updated");
    }

    /**
     * Marks a line faulted. If it was closed, it is opened first so the fault interlock holds.
     * Worst-case time: O(log E) if rejected; O(log E + log V + V log V + E) if accepted.
     * Auxiliary space: O(V) - evaluation snapshot.
     */
    public OperationResult fault(int lineId) {
        totalCommands++;
        String op = "FAULT(" + lineId + ")";
        int li = findLineIndexById(lineId);
        if (li < 0) return reject(op, "lineId not found");
        Line ln = lines[li];
        if (ln.isFaulted()) return reject(op, "line is already faulted");
        boolean wasClosed = ln.isClosed();
        if (wasClosed) {
            ett.cut(ln.busAIndex, ln.busBIndex, li);
            ln.setClosed(false);
        }
        ln.setFaulted(true);
        acceptedCommands++;
        String msg = wasClosed ? "line faulted and auto-opened; locked out" : "open line faulted; locked out";
        return evaluateCurrentState(op, true, msg);
    }

    /**
     * Clears the fault flag. The line stays open; no ETT change occurs.
     * Worst-case time: O(log E) if rejected; O(V log V + E) if accepted (status refresh).
     * Auxiliary space: O(V) - evaluation snapshot.
     */
    public OperationResult clearFault(int lineId) {
        totalCommands++;
        String op = "CLEAR_FAULT(" + lineId + ")";
        int li = findLineIndexById(lineId);
        if (li < 0) return reject(op, "lineId not found");
        Line ln = lines[li];
        if (!ln.isFaulted()) return reject(op, "line is not faulted");
        ln.setFaulted(false);
        acceptedCommands++;
        return evaluateCurrentState(op, true, "fault cleared; line remains open");
    }

    /**
     * Records a command that could not be parsed. Counts toward totals and changes nothing else.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    public OperationResult rejectMalformed(String text, String reason) {
        totalCommands++;
        return reject("MALFORMED('" + text + "')", reason);
    }

    /**
     * Groups buses by ETT root, reads root aggregates, runs the electrical screen, and builds the report.
     * Worst-case time: O(V log V + E) - V root climbs of O(log V), a sort of V keys, and an O(V + E) audit.
     * Auxiliary space: O(V + E) - grouping arrays and the audit scratch space.
     */
    public OperationResult evaluateCurrentState(String opName, boolean success, String message) {
        int n = buses.length;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = (long) ett.getComponentRepresentativeIndex(i) * n + i;
        Arrays.sort(keys);
        int[] groupOf = new int[n];
        int groups = -1;
        long prevRoot = -1L;
        for (int k = 0; k < n; k++) {
            long root = keys[k] / n;
            int i = (int) (keys[k] % n);
            if (root != prevRoot) {
                groups++;
                prevRoot = root;
            }
            groupOf[i] = groups;
        }
        int[] groupToComp = new int[groups + 1];
        Arrays.fill(groupToComp, -1);
        int[] busComp = new int[n];
        int numComps = 0;
        for (int i = 0; i < n; i++) {
            int g = groupOf[i];
            if (groupToComp[g] < 0) groupToComp[g] = numComps++;
            busComp[i] = groupToComp[g];
        }
        // Components are numbered by their smallest bus index, which is the smallest bus ID.
        int[] compSize = new int[numComps];
        for (int i = 0; i < n; i++) compSize[busComp[i]]++;
        int[][] members = new int[numComps][];
        for (int c = 0; c < numComps; c++) members[c] = new int[compSize[c]];
        int[] fillPos = new int[numComps];
        for (int i = 0; i < n; i++) {
            int c = busComp[i];
            members[c][fillPos[c]++] = i;
        }
        int[] compSource = new int[numComps];
        Arrays.fill(compSource, -1);
        for (int i = 0; i < n; i++) {
            if (buses[i].hasSubstation) compSource[busComp[i]] = i;
        }

        ViolationReport[] reports = checker.auditNetwork(buses, lines, adjHead, adjNext, adjLineIdx,
                numComps, compSource);
        ComponentStatus[] comps = new ComponentStatus[numComps];
        double uP = 0.0;
        double uQ = 0.0;
        int uC = 0;
        int violations = 0;
        for (int c = 0; c < numComps; c++) {
            Aggregate agg = ett.getAggregate(members[c][0]);
            int[] ids = new int[compSize[c]];
            for (int j = 0; j < compSize[c]; j++) ids[j] = buses[members[c][j]].id;
            boolean hasSrc = compSource[c] >= 0;
            int srcId = hasSrc ? buses[compSource[c]].id : -1;
            comps[c] = new ComponentStatus(c + 1, ids, hasSrc, srcId, agg.totalPKw, agg.totalQKvar,
                    agg.totalCustomers, reports[c]);
            if (!hasSrc) {
                uP += agg.totalPKw;
                uQ += agg.totalQKvar;
                uC += agg.totalCustomers;
            }
            violations += reports[c].violationCount;
        }
        unservedPKw = uP;
        unservedQKvar = uQ;
        unservedCustomers = uC;
        lastViolationCount = violations;
        return new OperationResult(opName, success, message, comps, uP, uQ, uC, violations);
    }

    /**
     * Binary search for a bus by external ID.
     * Worst-case time: O(log V). Auxiliary space: O(1).
     */
    public int findBusIndexById(int busId) {
        int lo = 0;
        int hi = buses.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = buses[mid].id;
            if (v == busId) return mid;
            if (v < busId) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /**
     * Binary search for a line by external ID.
     * Worst-case time: O(log E). Auxiliary space: O(1).
     */
    public int findLineIndexById(int lineId) {
        int lo = 0;
        int hi = lines.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = lines[mid].id;
            if (v == lineId) return mid;
            if (v < lineId) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - builds a rejected result, touches no topology. */
    private OperationResult reject(String op, String reason) {
        rejectedCommands++;
        return new OperationResult(op, false, reason, null, unservedPKw, unservedQKvar,
                unservedCustomers, lastViolationCount);
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - packs a sort key that orders by ID, then by index. */
    private static long sortKey(int id, int index) {
        return (((long) id + 2147483648L) << 31) | (long) index;
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - recovers the index from a sort key. */
    private static int keyIndex(long key) {
        return (int) (key & 0x7FFFFFFFL);
    }

    /** Returns a shallow copy of the bus records for read-only UI/reporting consumers. Worst-case time: O(V), auxiliary space: O(V). */
    public Bus[] getBuses() { return Arrays.copyOf(buses, buses.length); }

    /** Returns a shallow copy of the line records for read-only UI/reporting consumers. Line state is read through isClosed/isFaulted. Worst-case time: O(E), auxiliary space: O(E). */
    public Line[] getLines() { return Arrays.copyOf(lines, lines.length); }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public OperationResult getInitialState() { return initialState; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null when initialization succeeded. */
    public String getInitError() { return initError; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public String checkInvariants() { return ett.checkInvariants(); }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getTotalCommands() { return totalCommands; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getAcceptedCommands() { return acceptedCommands; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getRejectedCommands() { return rejectedCommands; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public double getInitialUnservedPKw() { return initialUnservedPKw; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public double getInitialUnservedQKvar() { return initialUnservedQKvar; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getInitialDisconnectedCustomers() { return initialDisconnectedCustomers; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - unserved totals of the last evaluated state. */
    public double getUnservedPKw() { return unservedPKw; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public double getUnservedQKvar() { return unservedQKvar; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) */
    public int getUnservedCustomers() { return unservedCustomers; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - violations in the last evaluated state. */
    public int getLastViolationCount() { return lastViolationCount; }
}
