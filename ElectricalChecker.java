import java.util.Arrays;

/**
 * Linearized (Baran-Wu LinDistFlow) screening of every energized radial component.
 * Separate from the ETT: it runs a full BFS/sweep and is not used for topology decisions.
 * This is a screen, not an AC power-flow study and not a safety certification.
 */
public class ElectricalChecker {
    public static final double S_BASE_MVA = 100.0;
    public static final double V_MIN_PU = 0.95;
    public static final double V_MAX_PU = 1.05;
    public static final double EPSILON = 1e-6;
    public static final double V_COLLAPSE_FLOOR_PU = 0.10;

    /**
     * Worst-case time: O(1). Auxiliary space: O(1). Stateless.
     */
    public ElectricalChecker() { }

    /**
     * Audits all components. componentSourceBusIndex[c] is the source bus of component c, or -1 if the
     * component is a de-energized island (its flows and voltages are reported as zero and not swept).
     * Worst-case time: O(V + E) - each energized component is BFS-swept once, and components are disjoint.
     * Auxiliary space: O(V) - scratch arrays allocated once and reused for all components.
     * Deterministic.
     */
    public ViolationReport[] auditNetwork(Bus[] buses, Line[] lines, int[] adjHead, int[] adjNext,
                                          int[] adjLineIdx, int numComponents, int[] componentSourceBusIndex) {
        int n = buses.length;
        ViolationReport[] reports = new ViolationReport[numComponents];
        int[] bfsOrder = new int[n];
        int[] parentBus = new int[n];
        int[] parentLine = new int[n];
        int[] stamp = new int[n];
        double[] downP = new double[n];
        double[] downQ = new double[n];
        double[] vPu = new double[n];
        for (int c = 0; c < numComponents; c++) {
            int s = componentSourceBusIndex[c];
            if (s < 0) {
                reports[c] = new ViolationReport(false, 0, new String[0], 0.0, 0.0, 0.0);
            } else {
                reports[c] = auditSingleComponent(c + 1, s, buses, lines, adjHead, adjNext, adjLineIdx,
                        bfsOrder, parentBus, parentLine, stamp, downP, downQ, vPu);
            }
        }
        return reports;
    }

    /**
     * BFS from the source over closed healthy lines, backward P/Q sum, forward voltage sweep, then limit checks.
     * Worst-case time: O(V_k + E_k) - each bus is dequeued once; each incident line is inspected at most twice.
     * Auxiliary space: O(V_k) - the message array and BFS arrays (shared scratch) of this component.
     * Justification: the voltage recurrence uses the parent voltage, which is already known in BFS order.
     */
    private ViolationReport auditSingleComponent(int stampValue, int s, Bus[] buses, Line[] lines,
            int[] adjHead, int[] adjNext, int[] adjLineIdx, int[] bfsOrder, int[] parentBus,
            int[] parentLine, int[] stamp, double[] downP, double[] downQ, double[] vPu) {
        int head = 0;
        int tail = 0;
        bfsOrder[tail++] = s;
        parentBus[s] = -1;
        parentLine[s] = -1;
        stamp[s] = stampValue;
        while (head < tail) {
            int u = bfsOrder[head++];
            for (int k = adjHead[u]; k != -1; k = adjNext[k]) {
                int li = adjLineIdx[k];
                Line ln = lines[li];
                if (!ln.isClosed() || ln.isFaulted()) continue;
                int w = (ln.busAIndex == u) ? ln.busBIndex : ln.busAIndex;
                if (stamp[w] == stampValue) continue;
                stamp[w] = stampValue;
                parentBus[w] = u;
                parentLine[w] = li;
                bfsOrder[tail++] = w;
            }
        }

        // Backward sweep: leaves to root. Children appear after parents in BFS order, so go in reverse.
        for (int i = 0; i < tail; i++) {
            int v = bfsOrder[i];
            downP[v] = buses[v].pKw;
            downQ[v] = buses[v].qKvar;
        }
        for (int i = tail - 1; i >= 1; i--) {
            int v = bfsOrder[i];
            int p = parentBus[v];
            downP[p] += downP[v];
            downQ[p] += downQ[v];
        }

        // Forward sweep: root to leaves, voltage drop per branch.
        String[] msgs = new String[4 * tail + 4];
        int nv = 0;
        double zBase = buses[s].vBaseKv * buses[s].vBaseKv / S_BASE_MVA;
        vPu[s] = buses[s].sourceVoltagePu;
        double maxLinePct = 0.0;
        for (int i = 1; i < tail; i++) {
            int v = bfsOrder[i];
            int p = parentBus[v];
            Line ln = lines[parentLine[v]];
            if (Math.abs(buses[p].vBaseKv - buses[v].vBaseKv) > EPSILON) {
                msgs[nv++] = String.format("BASE_VOLTAGE_MISMATCH: Line %d joins %.3f kV and %.3f kV buses",
                        ln.id, buses[p].vBaseKv, buses[v].vBaseKv);
            }
            double flow = Math.hypot(downP[v], downQ[v]);
            double pct = 100.0 * flow / ln.sMaxKva;
            if (pct > maxLinePct) maxLinePct = pct;
            if (flow > ln.sMaxKva + EPSILON) {
                msgs[nv++] = String.format("LINE_OVERLOAD: Line %d flow %.2f kVA > limit %.2f kVA (%.1f%%)",
                        ln.id, flow, ln.sMaxKva, pct);
            }
            double rPu = ln.rOhm / zBase;
            double xPu = ln.xOhm / zBase;
            double pPu = downP[v] / (1000.0 * S_BASE_MVA);
            double qPu = downQ[v] / (1000.0 * S_BASE_MVA);
            double vParent = vPu[p];
            double vNew;
            boolean collapse = false;
            if (vParent < V_COLLAPSE_FLOOR_PU) {
                vNew = 0.0;
                collapse = true;
            } else {
                vNew = vParent - (rPu * pPu + xPu * qPu) / vParent;
                if (vNew < 0.0) {
                    vNew = 0.0;
                    collapse = true;
                }
            }
            vPu[v] = vNew;
            if (collapse) {
                msgs[nv++] = String.format("UNDERVOLTAGE_COLLAPSE: Bus %d voltage collapse (parent %.4f pu); clamped to 0.0000 pu",
                        buses[v].id, vParent);
            }
        }

        // Voltage window check over all energized buses, including the source.
        double minV = Double.MAX_VALUE;
        for (int i = 0; i < tail; i++) {
            int v = bfsOrder[i];
            double vv = vPu[v];
            if (vv < minV) minV = vv;
            if (vv < V_MIN_PU - EPSILON) {
                msgs[nv++] = String.format("UNDERVOLTAGE: Bus %d voltage %.4f pu below %.2f pu",
                        buses[v].id, vv, V_MIN_PU);
            } else if (vv > V_MAX_PU + EPSILON) {
                msgs[nv++] = String.format("OVERVOLTAGE: Bus %d voltage %.4f pu above %.2f pu",
                        buses[v].id, vv, V_MAX_PU);
            }
        }

        // Substation source capacity.
        double srcKva = Math.hypot(downP[s], downQ[s]);
        double srcCap = buses[s].sourceCapacityKva;
        double srcPct = srcCap > 0.0 ? 100.0 * srcKva / srcCap : 0.0;
        if (srcKva > srcCap + EPSILON) {
            msgs[nv++] = String.format("SOURCE_OVERLOAD: Substation Bus %d apparent power %.2f kVA exceeds capacity %.2f kVA (%.1f%%)",
                    buses[s].id, srcKva, srcCap, srcPct);
        }
        return new ViolationReport(true, nv, Arrays.copyOf(msgs, nv), minV, maxLinePct, srcPct);
    }
}
