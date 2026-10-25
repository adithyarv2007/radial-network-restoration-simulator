import java.util.Random;

/**
 * Deterministic, non-generic test runner. Run with: javac *.java && java RestorationTestRunner
 * Each TC-xx case mirrors the verification plan in the architecture document.
 */
public class RestorationTestRunner {
    private static int passed = 0;
    private static int failed = 0;

    /**
     * Entry point. Runs scenario tests, ETT stress and the randomized reference comparison.
     * Worst-case time: dominated by the randomized test, O(steps * (V^2 + V log V)) with V = 12.
     * Auxiliary space: O(V^2) for the reference matrix.
     */
    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.US);
        System.out.println("== Scenario tests (topology, electrical screen, rejections) ==");
        tc01OpenCreatesIsland();
        tc02CloseRestoresService();
        tc03CycleRejected();
        tc04MultiSourceRejected();
        tc05FaultOnClosedAndOpenLines();
        tc06FaultInterlockAndClearFault();
        tc07LoadTransferOpenBeforeClose();
        tc08ReversedRankCut();
        tc09NoDoubleCountingAtHub();
        tc10LineOverloadFlagged();
        tc11UnderVoltageFlagged();
        tc12SourceOverloadFlagged();
        tc13InvalidIdsAndRedundantCommands();
        tc14RejectionsLeaveStateUnchanged();
        System.out.println("== Euler Tour Tree stress and reference comparison ==");
        tc15ChainStress();
        randomizedEttAgainstReference();
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Records one check. Worst-case time O(1). */
    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    /** Floating comparison with an absolute tolerance. */
    private static boolean approx(double a, double b) { return Math.abs(a - b) < 1e-9; }

    /** Builds a raw bus. internalIndex is reassigned by the simulator. */
    private static Bus bus(int id, double p, double q, int cust, boolean src, double cap) {
        return new Bus(id, 0, p, q, cust, src, 12.47, cap, 1.0);
    }

    /** Builds a raw line. Endpoint indices are reassigned by the simulator. */
    private static Line line(int id, int a, int b, double r, double x, double smax, boolean closed, boolean faulted) {
        return new Line(id, 0, a, b, 0, 0, r, x, smax, closed, faulted);
    }

    /** True if any accepted component report contains the given text. Worst-case time O(W). */
    private static boolean hasViolation(OperationResult r, String needle) {
        if (r.components == null) return false;
        for (ComponentStatus c : r.components) {
            for (String v : c.electricalReport.violations) {
                if (v.contains(needle)) return true;
            }
        }
        return false;
    }

    private static void tc01OpenCreatesIsland() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 200, 100, 50, false, 0), bus(3, 300, 150, 80, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 2, 3, 0.1, 0.1, 5000, true, false),
                     line(3, 1, 3, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        check("TC-01 initial state fully energized", approx(s.getInitialState().totalUnservedPKw, 0.0));
        OperationResult r = s.open(1);
        check("TC-01 OPEN 1 accepted", r.accepted);
        check("TC-01 unserved = 500 kW, 250 kVAr, 130 customers",
                approx(r.totalUnservedPKw, 500) && approx(r.totalUnservedQKvar, 250) && r.totalDisconnectedCustomers == 130);
        check("TC-01 two components", r.components.length == 2);
    }

    private static void tc02CloseRestoresService() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 200, 100, 50, false, 0), bus(3, 300, 150, 80, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 2, 3, 0.1, 0.1, 5000, true, false),
                     line(3, 1, 3, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.open(1);
        OperationResult r = s.close(3);
        check("TC-02 CLOSE 3 accepted", r.accepted);
        check("TC-02 service restored: unserved 0", approx(r.totalUnservedPKw, 0) && r.totalDisconnectedCustomers == 0);
        check("TC-02 one energized component", r.components.length == 1 && r.components[0].hasSource);
    }

    private static void tc03CycleRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 200, 100, 50, false, 0), bus(3, 300, 150, 80, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 2, 3, 0.1, 0.1, 5000, true, false),
                     line(3, 1, 3, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(3);
        check("TC-03 CLOSE 3 rejected as a cycle", !r.accepted && r.statusMessage.contains("cycle"));
        check("TC-03 topology unchanged after rejection", approx(s.getUnservedPKw(), 0.0) && s.getRejectedCommands() == 1);
    }

    private static void tc04MultiSourceRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0),
                    bus(3, 0, 0, 0, true, 5000), bus(4, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 3, 4, 0.1, 0.1, 5000, true, false),
                     line(3, 2, 4, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(3);
        check("TC-04 CLOSE 3 rejected for two sources", !r.accepted && r.statusMessage.contains("multi-source"));
        check("TC-04 both islands stay separate", s.getRejectedCommands() == 1 && approx(s.getUnservedPKw(), 0.0));
    }

    private static void tc05FaultOnClosedAndOpenLines() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 200, 100, 50, false, 0), bus(3, 300, 150, 80, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 2, 3, 0.1, 0.1, 5000, true, false),
                     line(3, 1, 3, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r2 = s.fault(2);
        check("TC-05 FAULT 2 (closed) accepted and auto-opened", r2.accepted && approx(r2.totalUnservedPKw, 300));
        OperationResult r3 = s.fault(3);
        check("TC-05 FAULT 3 (open) accepted, topology unchanged", r3.accepted && approx(r3.totalUnservedPKw, 300));
    }

    private static void tc06FaultInterlockAndClearFault() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 200, 100, 50, false, 0), bus(3, 300, 150, 80, false, 0) };
        Line[] l = { line(1, 1, 2, 0.1, 0.1, 5000, true, false), line(2, 2, 3, 0.1, 0.1, 5000, true, false),
                     line(3, 1, 3, 0.1, 0.1, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.fault(2);
        OperationResult rc = s.close(2);
        check("TC-06 CLOSE 2 rejected while faulted", !rc.accepted && rc.statusMessage.contains("faulted"));
        OperationResult cf = s.clearFault(2);
        check("TC-06 CLEAR_FAULT 2 accepted, line stays open, unserved still 300",
                cf.accepted && approx(cf.totalUnservedPKw, 300));
        OperationResult cl = s.close(2);
        check("TC-06 CLOSE 2 accepted after clearing; unserved 0", cl.accepted && approx(cl.totalUnservedPKw, 0));
    }

    private static void tc07LoadTransferOpenBeforeClose() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0), bus(3, 200, 100, 20, false, 0),
                    bus(4, 0, 0, 0, true, 5000), bus(5, 0, 0, 0, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false), line(2, 2, 3, 0.01, 0.01, 5000, true, false),
                     line(3, 3, 5, 0.01, 0.01, 5000, false, false), line(4, 4, 5, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult o = s.open(2);
        check("TC-07 OPEN 2 accepted; bus 3 de-energized (200 kW)", o.accepted && approx(o.totalUnservedPKw, 200));
        OperationResult c = s.close(3);
        check("TC-07 CLOSE 3 transfers bus 3 to substation 4; unserved 0",
                c.accepted && approx(c.totalUnservedPKw, 0) && c.components.length == 2);
    }

    private static void tc08ReversedRankCut() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0), bus(3, 100, 50, 10, false, 0),
                    bus(4, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 3, 4, 0.01, 0.01, 5000, true, false), line(2, 2, 4, 0.01, 0.01, 5000, false, false),
                     line(3, 1, 2, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.close(2);
        s.close(3);
        OperationResult r = s.open(1);
        check("TC-08 OPEN 1 accepted after two re-links", r.accepted);
        check("TC-08 island {3} isolated, unserved 100 kW", approx(r.totalUnservedPKw, 100) && r.totalDisconnectedCustomers == 10);
        check("TC-08 energized component {1,2,4} carries 200 kW",
                r.components.length == 2 && (approx(r.components[0].totalPKw, 200) || approx(r.components[1].totalPKw, 200)));
        check("TC-08 ETT invariants hold after rank-ordered cut", s.checkInvariants() == null);
    }

    private static void tc09NoDoubleCountingAtHub() {
        Bus[] b = new Bus[6];
        for (int i = 0; i < 6; i++) b[i] = bus(i + 1, 100, 50, 10, i == 0, i == 0 ? 5000 : 0);
        Line[] l = { line(1, 2, 3, 0.01, 0.01, 5000, true, false), line(2, 1, 2, 0.01, 0.01, 5000, true, false),
                     line(3, 2, 4, 0.01, 0.01, 5000, true, false), line(4, 2, 5, 0.01, 0.01, 5000, true, false),
                     line(5, 5, 6, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.open(2);
        OperationResult r = s.close(2);
        check("TC-09 hub bus 2 reconnected", r.accepted && r.components.length == 1);
        check("TC-09 aggregate exactly 600 kW, 300 kVAr, 60 customers",
                approx(r.components[0].totalPKw, 600) && approx(r.components[0].totalQKvar, 300)
                && r.components[0].totalCustomers == 60);
    }

    private static void tc10LineOverloadFlagged() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 600, 800, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 800, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(1);
        check("TC-10 CLOSE 1 accepted topologically", r.accepted);
        check("TC-10 LINE_OVERLOAD reported with 1000.00 kVA flow", hasViolation(r, "LINE_OVERLOAD: Line 1 flow 1000.00 kVA"));
        check("TC-10 violation count is 1", r.totalElectricalViolations == 1);
    }

    private static void tc11UnderVoltageFlagged() {
        Bus[] b = { bus(1, 0, 0, 0, true, 10000), bus(2, 1500, 1000, 10, false, 0), bus(3, 1500, 1000, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 3.5, 4.0, 10000, false, false), line(2, 2, 3, 3.5, 4.0, 10000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.close(1);
        OperationResult r = s.close(2);
        check("TC-11 both CLOSE commands accepted", r.accepted);
        check("TC-11 UNDERVOLTAGE flagged at bus 2", hasViolation(r, "UNDERVOLTAGE: Bus 2 voltage 0.88"));
        check("TC-11 UNDERVOLTAGE flagged at bus 3", hasViolation(r, "UNDERVOLTAGE: Bus 3 voltage 0.81"));
    }

    private static void tc12SourceOverloadFlagged() {
        Bus[] b = { bus(1, 0, 0, 0, true, 1500), bus(2, 1200, 900, 20, false, 0), bus(3, 400, 300, 5, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 3000, true, false), line(2, 2, 3, 0.01, 0.01, 3000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(2);
        check("TC-12 CLOSE 2 accepted topologically", r.accepted);
        check("TC-12 SOURCE_OVERLOAD reported at 2000.00 kVA", hasViolation(r, "SOURCE_OVERLOAD: Substation Bus 1 apparent power 2000.00 kVA"));
    }

    private static void tc13InvalidIdsAndRedundantCommands() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0), bus(3, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false), line(2, 2, 3, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        double before = s.getUnservedPKw();
        s.open(999);
        s.close(1);
        s.open(2);
        s.clearFault(2);
        check("TC-13 four invalid or redundant commands rejected", s.getRejectedCommands() == 4 && s.getAcceptedCommands() == 0);
        check("TC-13 zero state changes after rejections", approx(s.getUnservedPKw(), before) && s.checkInvariants() == null);
    }

    private static void tc14RejectionsLeaveStateUnchanged() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0), bus(3, 100, 50, 10, false, 0),
                    bus(4, 0, 0, 0, true, 5000) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false), line(2, 2, 3, 0.01, 0.01, 5000, true, false),
                     line(3, 1, 3, 0.01, 0.01, 5000, false, false), line(4, 3, 4, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r1 = s.close(3);
        OperationResult r2 = s.close(4);
        check("TC-14 illegal cycle close rejected", !r1.accepted);
        check("TC-14 illegal multi-source close rejected", !r2.accepted);
        check("TC-14 invariants hold after two rejections", s.checkInvariants() == null);
        s.open(1);
        OperationResult r3 = s.close(4);
        check("TC-14 CLOSE 4 succeeds after OPEN 1", r3.accepted);
        check("TC-14 final unserved 0 kW", approx(r3.totalUnservedPKw, 0) && r3.components.length == 2);
    }

    private static void tc15ChainStress() {
        int n = 64;
        int m = n - 1;
        EulerTourTree ett = new EulerTourTree(n, m);
        for (int i = 0; i < n; i++) ett.initBus(new Bus(i + 1, i, 1.0, 0.5, 1, i == 0, 12.47, 1000.0, 1.0));
        boolean ok = true;
        int maxH = 0;
        int ops = 0;
        for (int e = 0; e < m; e++) {
            ok &= ett.link(e, e + 1, e, e + 1);
            ok &= ett.checkInvariants() == null;
            maxH = Math.max(maxH, maxHeight(ett, n));
            ops++;
        }
        for (int e = 0; e < 30; e++) {
            ok &= ett.cut(e, e + 1, e);
            ok &= ett.checkInvariants() == null;
            maxH = Math.max(maxH, maxHeight(ett, n));
            ops++;
        }
        for (int e = 29; e >= 0; e--) {
            ok &= ett.link(e, e + 1, e, e + 1);
            ok &= ett.checkInvariants() == null;
            maxH = Math.max(maxH, maxHeight(ett, n));
            ops++;
        }
        for (int e = 62; e >= 30; e--) {
            ok &= ett.cut(e, e + 1, e);
            ok &= ett.checkInvariants() == null;
            maxH = Math.max(maxH, maxHeight(ett, n));
            ops++;
        }
        for (int e = 30; e <= 62; e++) {
            ok &= ett.link(e, e + 1, e, e + 1);
            ok &= ett.checkInvariants() == null;
            maxH = Math.max(maxH, maxHeight(ett, n));
            ops++;
        }
        Aggregate all = ett.getAggregate(0);
        check("TC-15 chain stress: " + ops + " link/cut ops, all invariants hold, max AVL height " + maxH + " <= 11",
                ok && maxH <= 11 && approx(all.totalPKw, 64) && all.sourceCount == 1 && all.totalCustomers == 64);
    }

    /** Largest AVL root height over all buses (a full scan, used only in tests). Worst-case O(V log V). */
    private static int maxHeight(EulerTourTree ett, int n) {
        int best = 0;
        for (int i = 0; i < n; i++) best = Math.max(best, ett.componentHeight(i));
        return best;
    }

    /**
     * 3000 random link/cut steps on 12 buses, compared against the independent reference matrix.
     * Worst-case time: O(steps * V^2). Auxiliary space: O(V^2).
     */
    private static void randomizedEttAgainstReference() {
        int nb = 12;
        int np = nb * (nb - 1) / 2;
        int[] pu = new int[np];
        int[] pv = new int[np];
        int k = 0;
        for (int i = 0; i < nb; i++) {
            for (int j = i + 1; j < nb; j++) {
                pu[k] = i;
                pv[k] = j;
                k++;
            }
        }
        Random rnd = new Random(20261020L);
        double[] p = new double[nb];
        double[] q = new double[nb];
        int[] cust = new int[nb];
        boolean[] src = new boolean[nb];
        for (int i = 0; i < nb; i++) {
            p[i] = rnd.nextInt(400);
            q[i] = rnd.nextInt(200);
            cust[i] = rnd.nextInt(30);
        }
        src[0] = true;
        src[7] = true;
        EulerTourTree ett = new EulerTourTree(nb, np);
        for (int i = 0; i < nb; i++) ett.initBus(new Bus(i + 1, i, p[i], q[i], cust[i], src[i], 12.47, 1000.0, 1.0));
        ReferenceForest ref = new ReferenceForest(nb, p, q, cust, src);
        boolean[] present = new boolean[np];
        int links = 0;
        int cuts = 0;
        int mismatches = 0;
        int invariantFailures = 0;
        for (int step = 0; step < 3000; step++) {
            int li = rnd.nextInt(np);
            int u = pu[li];
            int v = pv[li];
            if (present[li]) {
                ett.cut(u, v, li);
                ref.removeEdge(u, v);
                present[li] = false;
                cuts++;
            } else if (!ref.connected(u, v) && ref.aggregate(u)[3] + ref.aggregate(v)[3] <= 1) {
                ett.link(u, v, li, li + 1);
                ref.addEdge(u, v);
                present[li] = true;
                links++;
            }
            for (int x = 0; x < nb; x++) {
                for (int y = x + 1; y < nb; y++) {
                    if (ett.connected(x, y) != ref.connected(x, y)) mismatches++;
                }
                Aggregate ag = ett.getAggregate(x);
                double[] rg = ref.aggregate(x);
                if (!approx(ag.totalPKw, rg[0]) || !approx(ag.totalQKvar, rg[1])
                        || ag.totalCustomers != (int) rg[2] || ag.sourceCount != (int) rg[3]) {
                    mismatches++;
                }
            }
            if (ett.checkInvariants() != null) invariantFailures++;
        }
        check("RAND 3000 steps (" + links + " links, " + cuts + " cuts) match reference; 0 mismatches, 0 invariant failures",
                mismatches == 0 && invariantFailures == 0 && links > 0 && cuts > 0);
    }
}
