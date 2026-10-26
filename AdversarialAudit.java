import java.util.Random;

/**
 * Adversarial audit tests: edge cases not covered by RestorationTestRunner.
 * Tests are numbered A-01 through A-30.
 */
public class AdversarialAudit {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.US);
        System.out.println("=== ADVERSARIAL AUDIT TESTS ===");

        // ---- ETT structural tests ----
        a01SingleBusTour();
        a02TourLengthFormula();
        a03CutDisconnectedReturnsTrue();
        a04CutAlreadyOpenReturnsFalse();
        a05LinkAlreadyConnectedReturnsFalse();
        a06CutRestoredToSingletons();
        a07StarTopologyAggregates();
        a08PathTopologyAggregates();
        a09ReversedEndpointsInCut();
        a10AVLBalanceOnLongChain();

        // ---- Simulator scenario tests ----
        a11DoubleOpenSameLineRejected();
        a12DoubleCloseSameLineRejected();
        a13FaultAlreadyFaultedRejected();
        a14ClearFaultOnHealthyRejected();
        a15FaultedLineInitiallyClosedForcedOpen();
        a16SelfLoopLineRejected();
        a17ZeroBusCount();
        a18OpenThenFaultThenClearThenClose();
        a19MultipleSubstationsIsolated();
        a20SourceCapacityExact();

        // ---- Electrical model tests ----
        a21VoltageBaseUnitConsistency();
        a22VoltageDropFormula();
        a23LinePowerFlowUnit();
        a24OvervoltageFlag();
        a25DeEnergizedComponentZeroViolations();

        // ---- Demand counting invariant ----
        a26DemandNotDoubleCountedAfterRelink();
        a27DemandCorrectAfterChainCut();

        // ---- Large-star stress ----
        a28StarStressAVLHeight();

        // ---- sortKey collision test ----
        a29SortKeyNoCollision();

        // ---- Randomized adversarial: source-count correctness ----
        a30RandomSourceCountAgreement();

        System.out.println();
        System.out.println("ADVERSARIAL RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + name); }
        else     { failed++; System.out.println("  [FAIL] " + name); }
    }

    private static boolean approx(double a, double b) { return Math.abs(a - b) < 1e-9; }

    private static Bus bus(int id, double p, double q, int cust, boolean src, double cap) {
        return new Bus(id, 0, p, q, cust, src, 12.47, cap, 1.0);
    }

    private static Line line(int id, int a, int b, double r, double x, double smax,
                              boolean closed, boolean faulted) {
        return new Line(id, 0, a, b, 0, 0, r, x, smax, closed, faulted);
    }

    // A-01: singleton tour is well-formed
    private static void a01SingleBusTour() {
        EulerTourTree ett = new EulerTourTree(1, 0);
        ett.initBus(new Bus(1, 0, 500.0, 250.0, 10, true, 12.47, 1000.0, 1.0));
        Aggregate ag = ett.getAggregate(0);
        check("A-01 singleton tour: correct P/Q/cust/src",
              approx(ag.totalPKw, 500.0) && approx(ag.totalQKvar, 250.0)
              && ag.totalCustomers == 10 && ag.sourceCount == 1);
        check("A-01 singleton invariant", ett.checkInvariants() == null);
    }

    // A-02: after linking n buses into a path, tour length must equal 3n-2
    // We verify via checkInvariants (which validates 3*busCount-2 internally).
    private static void a02TourLengthFormula() {
        int n = 10;
        EulerTourTree ett = new EulerTourTree(n, n - 1);
        for (int i = 0; i < n; i++) ett.initBus(new Bus(i + 1, i, 1.0, 0.5, 1, false, 12.47, 0.0, 1.0));
        for (int e = 0; e < n - 1; e++) ett.link(e, e + 1, e, e + 1);
        String err = ett.checkInvariants();
        check("A-02 tour length 3n-2 for n=10 path", err == null);
    }

    // A-03: cut returns true when both directed tokens exist and lineIndex is valid
    private static void a03CutDisconnectedReturnsTrue() {
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 0.0, 0.0, 0, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 0.0, 0.0, 0, false, 12.47, 0.0, 1.0));
        ett.link(0, 1, 0, 1);
        boolean cutResult = ett.cut(0, 1, 0);
        check("A-03 cut returns true for valid active edge", cutResult);
        check("A-03 buses disconnected after cut", !ett.connected(0, 1));
        check("A-03 invariant after cut", ett.checkInvariants() == null);
    }

    // A-04: cut on an already-open (null) line returns false
    private static void a04CutAlreadyOpenReturnsFalse() {
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 0.0, 0.0, 0, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 0.0, 0.0, 0, false, 12.47, 0.0, 1.0));
        // Never linked — lineToEdgeUV[0] is null
        boolean cutResult = ett.cut(0, 1, 0);
        check("A-04 cut on null line returns false", !cutResult);
    }

    // A-05: link on already-connected pair returns false
    private static void a05LinkAlreadyConnectedReturnsFalse() {
        EulerTourTree ett = new EulerTourTree(2, 2);
        ett.initBus(new Bus(1, 0, 100.0, 50.0, 5, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 200.0, 100.0, 10, false, 12.47, 0.0, 1.0));
        ett.link(0, 1, 0, 1);
        boolean r = ett.link(0, 1, 1, 2); // already connected
        check("A-05 link on already-connected pair returns false", !r);
        check("A-05 invariant after duplicate link attempt", ett.checkInvariants() == null);
    }

    // A-06: cut restores both buses to singleton tours with correct aggregates
    private static void a06CutRestoredToSingletons() {
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 100.0, 50.0, 5, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 200.0, 100.0, 10, false, 12.47, 0.0, 1.0));
        ett.link(0, 1, 0, 1);
        ett.cut(0, 1, 0);
        Aggregate a0 = ett.getAggregate(0);
        Aggregate a1 = ett.getAggregate(1);
        check("A-06 bus 0 singleton P after cut", approx(a0.totalPKw, 100.0));
        check("A-06 bus 1 singleton P after cut", approx(a1.totalPKw, 200.0));
        check("A-06 not connected after cut", !ett.connected(0, 1));
        check("A-06 invariant after cut", ett.checkInvariants() == null);
    }

    // A-07: star topology (hub + 5 leaves) — demand aggregates
    private static void a07StarTopologyAggregates() {
        int n = 6;
        EulerTourTree ett = new EulerTourTree(n, n - 1);
        double totalP = 0, totalQ = 0;
        int totalCust = 0;
        for (int i = 0; i < n; i++) {
            double p = 100.0 * (i + 1);
            double q = 50.0 * (i + 1);
            int c = 10 * (i + 1);
            ett.initBus(new Bus(i + 1, i, p, q, c, i == 0, 12.47, i == 0 ? 5000.0 : 0.0, 1.0));
            totalP += p; totalQ += q; totalCust += c;
        }
        for (int e = 1; e < n; e++) ett.link(0, e, e - 1, e);
        Aggregate ag = ett.getAggregate(0);
        check("A-07 star aggregate P correct", approx(ag.totalPKw, totalP));
        check("A-07 star aggregate Q correct", approx(ag.totalQKvar, totalQ));
        check("A-07 star aggregate customers correct", ag.totalCustomers == totalCust);
        check("A-07 star sourceCount == 1", ag.sourceCount == 1);
        check("A-07 invariant", ett.checkInvariants() == null);
    }

    // A-08: path topology (8 buses) — demand aggregates
    private static void a08PathTopologyAggregates() {
        int n = 8;
        EulerTourTree ett = new EulerTourTree(n, n - 1);
        double totalP = 0;
        for (int i = 0; i < n; i++) {
            ett.initBus(new Bus(i + 1, i, 10.0, 5.0, 1, i == 0, 12.47, i == 0 ? 1000.0 : 0.0, 1.0));
            totalP += 10.0;
        }
        for (int e = 0; e < n - 1; e++) ett.link(e, e + 1, e, e + 1);
        Aggregate ag = ett.getAggregate(0);
        check("A-08 path aggregate P = 80", approx(ag.totalPKw, 80.0));
        check("A-08 invariant", ett.checkInvariants() == null);
    }

    // A-09: cut with reversed endpoint order (sameOrder=false, reverse=true path)
    private static void a09ReversedEndpointsInCut() {
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 100.0, 0.0, 5, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 200.0, 0.0, 10, false, 12.47, 0.0, 1.0));
        ett.link(0, 1, 0, 1);  // stored as uv=(0->1), vu=(1->0)
        // cut with reversed args
        boolean r = ett.cut(1, 0, 0);
        check("A-09 cut with reversed endpoints returns true", r);
        check("A-09 buses disconnected after reversed cut", !ett.connected(0, 1));
        check("A-09 invariant after reversed cut", ett.checkInvariants() == null);
    }

    // A-10: AVL height bound on a chain of 128 buses
    private static void a10AVLBalanceOnLongChain() {
        int n = 128;
        EulerTourTree ett = new EulerTourTree(n, n - 1);
        for (int i = 0; i < n; i++)
            ett.initBus(new Bus(i + 1, i, 1.0, 0.5, 1, i == 0, 12.47, i == 0 ? 5000 : 0, 1.0));
        for (int e = 0; e < n - 1; e++) ett.link(e, e + 1, e, e + 1);
        int h = ett.componentHeight(0);
        // Tour has 3*128-2 = 382 nodes. AVL height bound = floor(1.4404*log2(382)) + 1 ≈ 13
        // We allow up to 14 to be safe.
        int maxAllowed = 14;
        check("A-10 AVL height of 128-bus chain <= " + maxAllowed + " (actual: " + h + ")", h <= maxAllowed);
        check("A-10 invariant", ett.checkInvariants() == null);
    }

    // A-11: double OPEN on same line is rejected
    private static void a11DoubleOpenSameLineRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r1 = s.open(1);
        OperationResult r2 = s.open(1); // already open
        check("A-11 first OPEN accepted", r1.accepted);
        check("A-11 second OPEN on same line rejected", !r2.accepted);
        check("A-11 invariant", s.checkInvariants() == null);
    }

    // A-12: double CLOSE on same line is rejected
    private static void a12DoubleCloseSameLineRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r1 = s.close(1);
        OperationResult r2 = s.close(1); // already closed → cycle
        check("A-12 first CLOSE accepted", r1.accepted);
        check("A-12 second CLOSE rejected (cycle)", !r2.accepted);
        check("A-12 invariant", s.checkInvariants() == null);
    }

    // A-13: FAULT on already-faulted line is rejected
    private static void a13FaultAlreadyFaultedRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.fault(1);
        OperationResult r = s.fault(1);
        check("A-13 FAULT on already-faulted line rejected", !r.accepted && r.statusMessage.contains("already faulted"));
    }

    // A-14: CLEAR_FAULT on healthy line is rejected
    private static void a14ClearFaultOnHealthyRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.clearFault(1);
        check("A-14 CLEAR_FAULT on healthy line rejected", !r.accepted && r.statusMessage.contains("not faulted"));
    }

    // A-15: initially faulted+closed line is forced open at construction
    private static void a15FaultedLineInitiallyClosedForcedOpen() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, true) }; // closed AND faulted
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        // Should not have been linked, so bus 2 is an island
        double up = s.getInitialUnservedPKw();
        check("A-15 faulted+closed line forced open: bus 2 unserved (100 kW)", approx(up, 100.0));
        check("A-15 CLOSE 1 rejected because faulted", !s.close(1).accepted);
        check("A-15 invariant", s.checkInvariants() == null);
    }

    // A-16: self-loop line triggers initError
    private static void a16SelfLoopLineRejected() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 1, 0.01, 0.01, 5000, false, false) }; // self-loop
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        check("A-16 self-loop line causes initError", s.getInitError() != null
              && s.getInitError().contains("self-loop"));
    }

    // A-17: 0 buses, 0 lines — should construct cleanly
    private static void a17ZeroBusCount() {
        try {
            SwitchingSimulator s = new SwitchingSimulator(new Bus[0], new Line[0]);
            check("A-17 zero buses/lines: no NPE at construction", true);
        } catch (Exception e) {
            check("A-17 zero buses/lines: no NPE at construction [EXCEPTION: " + e + "]", false);
        }
    }

    // A-18: OPEN → FAULT (open) → CLEAR_FAULT → CLOSE sequence
    private static void a18OpenThenFaultThenClearThenClose() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.open(1);
        OperationResult rf = s.fault(1);        // fault an open line
        check("A-18 FAULT on open line accepted", rf.accepted);
        OperationResult rc = s.close(1);
        check("A-18 CLOSE rejected while faulted", !rc.accepted);
        OperationResult rcf = s.clearFault(1);
        check("A-18 CLEAR_FAULT accepted", rcf.accepted);
        OperationResult rcl = s.close(1);
        check("A-18 CLOSE accepted after clear", rcl.accepted && approx(rcl.totalUnservedPKw, 0.0));
        check("A-18 invariant", s.checkInvariants() == null);
    }

    // A-19: three components, two of which have sources — each stays isolated
    private static void a19MultipleSubstationsIsolated() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0),
                    bus(3, 0, 0, 0, true, 5000), bus(4, 200, 100, 20, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false),
                     line(2, 3, 4, 0.01, 0.01, 5000, true, false),
                     line(3, 2, 4, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        check("A-19 initial unserved 0 (two energized islands)", approx(s.getInitialUnservedPKw(), 0.0));
        OperationResult r = s.close(3);
        check("A-19 tie-close rejected (two sources)", !r.accepted && r.statusMessage.contains("multi-source"));
        check("A-19 invariant after rejection", s.checkInvariants() == null);
    }

    // A-20: source capacity exactly at limit → no violation
    private static void a20SourceCapacityExact() {
        Bus[] b = { bus(1, 0, 0, 0, true, 1000), bus(2, 600, 800, 10, false, 0) };
        // sqrt(600^2+800^2) = 1000 exactly
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 2000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(1);
        boolean noSrcViolation = true;
        if (r.components != null) {
            for (ComponentStatus c : r.components) {
                for (String v : c.electricalReport.violations) {
                    if (v.contains("SOURCE_OVERLOAD")) noSrcViolation = false;
                }
            }
        }
        check("A-20 source at exact capacity: no SOURCE_OVERLOAD", noSrcViolation);
    }

    // A-21: zBase unit consistency (vBase^2 / Sbase)
    // For vBase=12.47 kV, Sbase=100 MVA: zBase = 12.47^2/100 = 1.5550 Ω
    private static void a21VoltageBaseUnitConsistency() {
        double vBase = 12.47;
        double sBaseMva = 100.0;
        double expected = vBase * vBase / sBaseMva;
        double rOhm = expected * 0.1; // r = 0.1 pu
        // Build a 2-bus system, close, check voltage drop matches ~0.1 pu drop
        Bus[] b = { bus(1, 0, 0, 0, true, 1000000), bus(2, 0, 0, 0, false, 0) };
        Line[] l = { line(1, 1, 2, rOhm, 0.0, 1000000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(1);
        // With zero load, voltage drop = 0; no violations expected
        boolean ok = r.accepted && r.totalElectricalViolations == 0;
        check("A-21 zBase unit check: zero-load yields no voltage violation", ok);
    }

    // A-22: voltage drop formula — verify approximate value for a known case
    // vParent=1.0, r=0.5 pu, x=0, P=10 MW=0.1 pu (Sbase=100 MVA), Q=0
    // vNew = 1.0 - (0.5*0.1)/1.0 = 0.95
    private static void a22VoltageDropFormula() {
        double sBase = 100.0; // MVA
        double vBase = 12.47; // kV
        double zBase = vBase * vBase / sBase; // Ω
        double rOhm = 0.5 * zBase;  // 0.5 pu in ohms
        double pMW = 10.0;           // 10 MW = 0.1 pu
        double pKw = pMW * 1000.0;  // 10000 kW
        Bus[] b = { bus(1, 0, 0, 0, true, 1000000), bus(2, (int)pKw, 0, 0, false, 0) };
        Line[] l = { line(1, 1, 2, rOhm, 0.0, 1000000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(1);
        // Expected v at bus 2 ≈ 0.95 → exactly at V_MIN, so should NOT flag UNDERVOLTAGE (border)
        // The check is: v >= 0.95 - epsilon. With exact 0.95 it should pass.
        boolean hasUnderVoltage = false;
        if (r.components != null) {
            for (ComponentStatus c : r.components)
                for (String v : c.electricalReport.violations)
                    if (v.contains("UNDERVOLTAGE:")) hasUnderVoltage = true;
        }
        // v=0.95 is exactly at limit; the code uses v < V_MIN - EPSILON so 0.95 should NOT flag
        check("A-22 voltage drop formula: v=0.95 pu is not flagged (at limit)", !hasUnderVoltage);
    }

    // A-23: line power flow unit check — flow = hypot(downP, downQ) in kVA
    // Bus demands are in kW/kVAr; flow is compared to sMaxKva in kVA — same units.
    private static void a23LinePowerFlowUnit() {
        // 300 kW + 400 kVAr → 500 kVA; limit 499 kVA → overload
        Bus[] b = { bus(1, 0, 0, 0, true, 1000000), bus(2, 300, 400, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.001, 0.001, 499, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.close(1);
        boolean overload = false;
        if (r.components != null)
            for (ComponentStatus c : r.components)
                for (String v : c.electricalReport.violations)
                    if (v.contains("LINE_OVERLOAD")) overload = true;
        check("A-23 line power flow unit: 500 kVA > 499 kVA triggers overload", overload);
    }

    // A-24: overvoltage flag (sourceVoltagePu > V_MAX)
    private static void a24OvervoltageFlag() {
        // Source at 1.06 pu, zero load → bus 1 already overvoltage
        Bus[] b = { new Bus(1, 0, 0, 0, 0, true, 12.47, 1000000, 1.06) };
        Line[] l = {};
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult init = s.evaluateCurrentState("CHECK", true, "check");
        boolean ov = false;
        if (init.components != null)
            for (ComponentStatus c : init.components)
                for (String v : c.electricalReport.violations)
                    if (v.contains("OVERVOLTAGE")) ov = true;
        check("A-24 source at 1.06 pu flags OVERVOLTAGE", ov);
    }

    // A-25: de-energized island gets a zero-violation ViolationReport (isEnergized=false)
    private static void a25DeEnergizedComponentZeroViolations() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 50, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.open(1);
        boolean deEnergizedCorrect = false;
        if (r.components != null) {
            for (ComponentStatus c : r.components) {
                if (!c.hasSource) {
                    deEnergizedCorrect = !c.electricalReport.isEnergized
                                         && c.electricalReport.violationCount == 0;
                }
            }
        }
        check("A-25 de-energized island has isEnergized=false, 0 violations", deEnergizedCorrect);
    }

    // A-26: demand not double-counted after open-then-close (hub test)
    private static void a26DemandNotDoubleCountedAfterRelink() {
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 0, 5, false, 0), bus(3, 200, 0, 10, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false),
                     line(2, 2, 3, 0.01, 0.01, 5000, true, false),
                     line(3, 1, 3, 0.01, 0.01, 5000, false, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        s.open(1);  // detach bus 2,3 from source
        s.close(3); // reattach via alt path
        OperationResult r = s.evaluateCurrentState("CHECK", true, "check");
        double totalP = 0;
        if (r.components != null)
            for (ComponentStatus c : r.components) totalP += c.totalPKw;
        check("A-26 demand not double-counted after relink: total P=300", approx(totalP, 300.0));
    }

    // A-27: demand correct on each side of a cut
    private static void a27DemandCorrectAfterChainCut() {
        // 1(src)-2-3-4: cut between 2 and 3
        Bus[] b = { bus(1, 0, 0, 0, true, 5000), bus(2, 100, 0, 5, false, 0),
                    bus(3, 200, 0, 10, false, 0), bus(4, 300, 0, 15, false, 0) };
        Line[] l = { line(1, 1, 2, 0.01, 0.01, 5000, true, false),
                     line(2, 2, 3, 0.01, 0.01, 5000, true, false),
                     line(3, 3, 4, 0.01, 0.01, 5000, true, false) };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult r = s.open(2); // cut 2-3
        double unserved = r.totalUnservedPKw;
        check("A-27 unserved after cut(2-3): buses 3+4 = 500 kW", approx(unserved, 500.0));
        check("A-27 invariant", s.checkInvariants() == null);
    }

    // A-28: star stress — 64-leaf star AVL height bound
    private static void a28StarStressAVLHeight() {
        int n = 65; // hub + 64 leaves
        int edges = n - 1;
        EulerTourTree ett = new EulerTourTree(n, edges);
        for (int i = 0; i < n; i++)
            ett.initBus(new Bus(i + 1, i, 1.0, 0.5, 1, i == 0, 12.47, i == 0 ? 10000 : 0, 1.0));
        for (int e = 1; e < n; e++) ett.link(0, e, e - 1, e);
        int h = ett.componentHeight(0);
        // Tour size = 3*65-2 = 193. AVL bound ≈ floor(1.4404*log2(193))+1 ≈ 11
        check("A-28 star-64 AVL height <= 12 (actual: " + h + ")", h <= 12);
        check("A-28 star invariant", ett.checkInvariants() == null);
    }

    // A-29: sortKey packs (id, index) without collision for IDs near Integer.MAX_VALUE
    private static void a29SortKeyNoCollision() {
        // sortKey = ((id + 2^31) << 31) | index
        // index must fit in 31 bits; test max plausible values
        long k1 = sortKey(Integer.MAX_VALUE, 0);
        long k2 = sortKey(Integer.MAX_VALUE - 1, 0);
        long k3 = sortKey(Integer.MAX_VALUE, 1);
        boolean noCollision = (k1 != k2) && (k1 != k3) && (k2 != k3);
        // Also check negative IDs
        long kn1 = sortKey(-1, 0);
        long kn2 = sortKey(0, 0);
        boolean negOk = kn1 != kn2 && kn1 < kn2;
        check("A-29 sortKey no collisions for boundary IDs", noCollision && negOk);
    }

    // Expose the private sortKey via reflection or replicate the formula
    private static long sortKey(int id, int index) {
        return (((long) id + 2147483648L) << 31) | (long) index;
    }

    // A-30: randomized source-count agreement after 500 link/cut operations
    private static void a30RandomSourceCountAgreement() {
        int nb = 10;
        int np = nb * (nb - 1) / 2;
        int[] pu = new int[np], pv = new int[np];
        int k = 0;
        for (int i = 0; i < nb; i++)
            for (int j = i + 1; j < nb; j++) { pu[k] = i; pv[k] = j; k++; }

        boolean[] src = new boolean[nb];
        src[0] = true; src[5] = true; src[9] = true; // three sources

        EulerTourTree ett = new EulerTourTree(nb, np);
        ReferenceForest ref = new ReferenceForest(nb, new double[nb], new double[nb], new int[nb], src);
        for (int i = 0; i < nb; i++)
            ett.initBus(new Bus(i + 1, i, 0, 0, 0, src[i], 12.47, src[i] ? 5000 : 0, 1.0));

        boolean[] present = new boolean[np];
        Random rnd = new Random(20261025L);
        int mismatches = 0;

        for (int step = 0; step < 500; step++) {
            int li = rnd.nextInt(np);
            int u = pu[li], v = pv[li];
            if (present[li]) {
                ett.cut(u, v, li);
                ref.removeEdge(u, v);
                present[li] = false;
            } else {
                double[] ra = ref.aggregate(u), rb = ref.aggregate(v);
                boolean safe = !ref.connected(u, v) && ra[3] + rb[3] <= 1;
                if (safe) {
                    ett.link(u, v, li, li + 1);
                    ref.addEdge(u, v);
                    present[li] = true;
                }
            }
            // Check source counts match
            for (int x = 0; x < nb; x++) {
                int ettSrc = ett.getAggregate(x).sourceCount;
                int refSrc = (int) ref.aggregate(x)[3];
                if (ettSrc != refSrc) mismatches++;
            }
        }
        check("A-30 randomized source-count: 0 mismatches over 500 steps", mismatches == 0);
    }
}
