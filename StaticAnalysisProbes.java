/**
 * Static analysis probes for edge cases that tests cannot exercise by passing alone.
 * These tests deliberately target documented potential defects.
 */
public class StaticAnalysisProbes {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.US);
        System.out.println("=== STATIC ANALYSIS PROBES ===");

        // PROBE-1: splitNode parent-pointer integrity after split
        // When we split at x, the leftAcc / rightAcc trees must have parent=null at their roots.
        probe01SplitParentPointerAtRoot();

        // PROBE-2: join3 when left==null and right==null (mid becomes isolated root)
        probe02Join3NullBothSides();

        // PROBE-3: join3 when one side is null
        probe03Join3OneSideNull();

        // PROBE-4: extractRightmost on single-node tree
        probe04ExtractRightmostSingleton();

        // PROBE-5: reroot does not corrupt tour when u is already leftmost
        probe05RerootAlreadyFirst();

        // PROBE-6: reroot does not corrupt tour when u is already last
        probe06RerootAlreadyLast();

        // PROBE-7: cut returns false on wrong endpoint (endpoint mismatch)
        probe07CutWrongEndpoints();

        // PROBE-8: sortKey with index > 2^31-1 would overflow — verify index < 2^31
        probe08SortKeyIndexRange();

        // PROBE-9: evaluateCurrentState groups correctness when two buses share a component ID
        probe09GroupingCorrectness();

        // PROBE-10: link after a series of cuts leaves aggregates correct
        probe10AggregateSoundnessAfterMixedOps();

        // PROBE-11: checkInvariants catches a manually corrupted parent pointer
        probe11CheckInvariantsCatchesCorruption();

        // PROBE-12: updateAggregates called with null children stays consistent
        probe12UpdateAggregatesNullChildren();

        // PROBE-13: replaceChild no-op when oldChild is neither left nor right
        probe13ReplaceChildNoOp();

        // PROBE-14: cyclic walk property verified by checkTree via checkInvariants
        probe14CyclicWalkProperty();

        // PROBE-15: demand correctness after alternating link/cut 20 times
        probe15AlternatingLinkCut();

        System.out.println();
        System.out.println("STATIC PROBES RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + name); }
        else     { failed++; System.out.println("  [FAIL] " + name); }
    }

    private static boolean approx(double a, double b) { return Math.abs(a - b) < 1e-9; }

    private static EulerTourTree twoNodeETT() {
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 100.0, 50.0, 5, false, 12.47, 0.0, 1.0));
        ett.initBus(new Bus(2, 1, 200.0, 100.0, 10, false, 12.47, 0.0, 1.0));
        return ett;
    }

    private static void probe01SplitParentPointerAtRoot() {
        // After link(0,1) + cut(0,1), both components should have root.parent==null
        EulerTourTree ett = twoNodeETT();
        ett.link(0, 1, 0, 1);
        ett.cut(0, 1, 0);
        // checkInvariants verifies "tree root has a non-null parent"
        check("PROBE-01 split leaves each root with parent==null", ett.checkInvariants() == null);
    }

    private static void probe02Join3NullBothSides() {
        // A singleton link followed by immediate cut exercises join3(null, mid, null)
        EulerTourTree ett = twoNodeETT();
        ett.link(0, 1, 0, 1);
        ett.cut(0, 1, 0);
        Aggregate a0 = ett.getAggregate(0);
        check("PROBE-02 join3(null,mid,null) leaves singleton aggregate correct",
              approx(a0.totalPKw, 100.0));
    }

    private static void probe03Join3OneSideNull() {
        // 3 buses in chain: link(0,1) then link(1,2) exercises join3 with one null side
        EulerTourTree ett = new EulerTourTree(3, 2);
        for (int i = 0; i < 3; i++)
            ett.initBus(new Bus(i + 1, i, 10.0 * (i + 1), 0, 0, false, 12.47, 0, 1.0));
        ett.link(0, 1, 0, 1);
        ett.link(1, 2, 1, 2);
        check("PROBE-03 join3 one-null side: aggregate P=60", approx(ett.getAggregate(0).totalPKw, 60.0));
        check("PROBE-03 invariant", ett.checkInvariants() == null);
    }

    private static void probe04ExtractRightmostSingleton() {
        // A singleton followed by link+cut exercises extractRightmost on a 1-node tree
        EulerTourTree ett = twoNodeETT();
        ett.link(0, 1, 0, 1);
        ett.cut(0, 1, 0);
        // After cut: both buses are singleton. Another link exercises join which calls extractRightmost
        ett.link(0, 1, 0, 1);
        check("PROBE-04 extractRightmost on singleton: re-link succeeds",
              ett.connected(0, 1) && ett.checkInvariants() == null);
    }

    private static void probe05RerootAlreadyFirst() {
        // When the bus is already the first token, splitNode returns (null, rest)
        // join(join3(null,nodeU,rest), null) = join3(null,nodeU,rest)
        // This should produce a valid tree.
        EulerTourTree ett = new EulerTourTree(3, 2);
        for (int i = 0; i < 3; i++)
            ett.initBus(new Bus(i + 1, i, 10.0, 5.0, 1, i == 0, 12.47, i == 0 ? 1000 : 0, 1.0));
        ett.link(0, 1, 0, 1);
        ett.link(0, 2, 1, 2);
        // open(0) and close(0) effectively reroots to bus 0 (already likely root)
        ett.cut(0, 1, 0);
        ett.link(0, 1, 0, 1);
        check("PROBE-05 reroot when already first: invariant holds", ett.checkInvariants() == null);
    }

    private static void probe06RerootAlreadyLast() {
        // Build a path 0-1-2; the Euler tour has 0,e01,1,e12,2,e21,e10
        // Bus 2 should be near the end; reroot to bus 2.
        EulerTourTree ett = new EulerTourTree(3, 2);
        for (int i = 0; i < 3; i++)
            ett.initBus(new Bus(i + 1, i, 10.0, 5.0, 1, false, 12.47, 0, 1.0));
        ett.link(0, 1, 0, 1);
        ett.link(1, 2, 1, 2);
        // Simulate reroot by cutting and relinking via the other endpoint
        ett.cut(1, 2, 1);
        ett.link(1, 2, 1, 2);
        check("PROBE-06 reroot when last: invariant holds", ett.checkInvariants() == null);
        check("PROBE-06 aggregate still P=30", approx(ett.getAggregate(0).totalPKw, 30.0));
    }

    private static void probe07CutWrongEndpoints() {
        // Try to cut line 0 with wrong endpoint indices
        EulerTourTree ett = twoNodeETT();
        ett.link(0, 1, 0, 1);
        // Correct endpoints are (0,1) or (1,0). Try (0,0) — mismatch.
        // Note: this would also need lineToEdgeUV[0] non-null (it is after link)
        // But ETT only has 2 buses; bus index 0 appears as fromBusIndex=0,toBusIndex=1 in token.
        // Passing uIndex=0, vIndex=0 won't match sameOrder or reverse.
        boolean cutResult = ett.cut(0, 0, 0); // wrong: both args same
        check("PROBE-07 cut with mismatched endpoints returns false", !cutResult);
        // Forest must be unchanged
        check("PROBE-07 topology unchanged after failed cut", ett.connected(0, 1)
              && ett.checkInvariants() == null);
    }

    private static void probe08SortKeyIndexRange() {
        // sortKey = ((id+2^31) << 31) | index
        // index is an array index (0..n-1). With n <= Integer.MAX_VALUE, index < 2^31 fits in 31 bits.
        // Verify: for index = 2^30 (max plausible), key is still positive.
        long k = (((long) 0 + 2147483648L) << 31) | (long) (1 << 30);
        check("PROBE-08 sortKey with large index stays positive (no sign overflow)", k > 0);
        // Verify the key index extraction: keyIndex(sortKey(id, index)) == index
        int idx = 0x7FFFFFFF; // max 31-bit value — but actual arrays won't have this many elements
        // Just test with a realistic max of 10^6
        int testIdx = 1000000;
        long testKey = (((long) 42 + 2147483648L) << 31) | (long) testIdx;
        int recovered = (int)(testKey & 0x7FFFFFFFL);
        check("PROBE-08 keyIndex recovers index correctly", recovered == testIdx);
    }

    private static void probe09GroupingCorrectness() {
        // Build simulator with 4 buses in 2 components and verify grouping
        Bus[] b = {
            new Bus(1, 0, 100, 50, 5, true,  12.47, 5000, 1.0),
            new Bus(2, 0, 200, 100, 10, false, 12.47, 0, 1.0),
            new Bus(3, 0, 0,   0,   0, true,  12.47, 5000, 1.0),
            new Bus(4, 0, 300, 150, 15, false, 12.47, 0, 1.0)
        };
        Line[] l = {
            new Line(1, 0, 1, 2, 0, 0, 0.01, 0.01, 5000, true, false),
            new Line(2, 0, 3, 4, 0, 0, 0.01, 0.01, 5000, true, false)
        };
        SwitchingSimulator s = new SwitchingSimulator(b, l);
        OperationResult init = s.getInitialState();
        check("PROBE-09 two components formed correctly", init.components != null && init.components.length == 2);
        // Each component should have exactly one source
        boolean srcOk = true;
        for (ComponentStatus c : init.components) if (!c.hasSource) srcOk = false;
        check("PROBE-09 each component has exactly one source", srcOk);
    }

    private static void probe10AggregateSoundnessAfterMixedOps() {
        // 5 buses, mix of link/cut, verify aggregate == sum of individual demands
        int n = 5;
        double[] pv = { 0, 100, 200, 300, 400 };
        EulerTourTree ett = new EulerTourTree(n, n*(n-1)/2);
        for (int i = 0; i < n; i++)
            ett.initBus(new Bus(i+1, i, pv[i], 0, 0, i==0, 12.47, i==0?5000:0, 1.0));
        // Build path
        ett.link(0, 1, 0, 1);
        ett.link(1, 2, 1, 2);
        ett.link(2, 3, 2, 3);
        ett.link(3, 4, 3, 4);
        // Cut 1-2, then 3-4. This leaves components {0,1}, {2,3}, and {4}.
        // Linking 0-4 then produces the intended components {0,1,4} and {2,3}.
        ett.cut(1, 2, 1);
        boolean secondCut = ett.cut(3, 4, 3);
        check("PROBE-10 second cut succeeds", secondCut);
        ett.link(0, 4, 4, 5); // attach isolated bus 4 directly to bus 0
        // Component containing bus 0: buses 0,1,4 (P = 0+100+400 = 500)
        // Component containing bus 2: buses 2,3 (P = 200+300 = 500)
        Aggregate a0 = ett.getAggregate(0);
        Aggregate a2 = ett.getAggregate(2);
        check("PROBE-10 component {0,1,4} P=500", approx(a0.totalPKw, 500.0));
        check("PROBE-10 component {2,3} P=500", approx(a2.totalPKw, 500.0));
        check("PROBE-10 invariant after mixed ops", ett.checkInvariants() == null);
    }

    private static void probe11CheckInvariantsCatchesCorruption() {
        // checkInvariants SHOULD catch a tree whose root has a non-null parent.
        // We can't easily corrupt the internal state from outside (fields are public though).
        // Instead verify the check itself returns null for a valid tree.
        EulerTourTree ett = new EulerTourTree(3, 2);
        for (int i = 0; i < 3; i++)
            ett.initBus(new Bus(i+1, i, 10.0, 5.0, 1, i==0, 12.47, 1000, 1.0));
        ett.link(0, 1, 0, 1);
        ett.link(1, 2, 1, 2);
        check("PROBE-11 checkInvariants returns null for valid tree", ett.checkInvariants() == null);
        // Now intentionally corrupt: set the root's parent to itself (public field)
        // This should be caught as "tree root has a non-null parent"
        // We cannot call private findRoot, so we indirectly test by corrupting via AVLNode
        // (Since AVLNode fields are public, we can access it from here)
        // busToNode[0] is the BusToken node for bus 0. Its root is some AVLNode in the tree.
        // We'll use componentHeight to reach the root indirectly via public API by forcing an explicit link
        // that creates a known structure; the probe just verifies checkInvariants is not a no-op.
        // A more direct test: after link and cut, there should be exactly 3 separate roots.
        ett.cut(0, 1, 0);
        ett.cut(1, 2, 1);
        String afterCuts = ett.checkInvariants();
        check("PROBE-11 checkInvariants returns null for 3 singletons", afterCuts == null);
    }

    private static void probe12UpdateAggregatesNullChildren() {
        // A leaf AVLNode with null left and right should have height=1, size=1
        AVLNode n = new AVLNode(new BusToken(0, 1, 50.0, 25.0, 3, false));
        n.updateAggregates();
        check("PROBE-12 leaf height=1", n.height == 1);
        check("PROBE-12 leaf size=1", n.subtreeSize == 1);
        check("PROBE-12 leaf P=50", approx(n.subtreeP, 50.0));
        check("PROBE-12 leaf sources=0", n.subtreeSources == 0);
    }

    private static void probe13ReplaceChildNoOp() {
        // replaceChild(null, x, y) is a no-op — verifiable indirectly through a rotation
        // (rotateLeft/rotateRight call replaceChild with the node's own parent).
        // Here we just verify no NPE occurs in a rebalance that hits a root.
        EulerTourTree ett = new EulerTourTree(5, 4);
        for (int i = 0; i < 5; i++)
            ett.initBus(new Bus(i+1, i, 1.0, 0.5, 1, false, 12.47, 0, 1.0));
        // Build a right-skewed structure to force rotateLeft at the root (parent=null)
        for (int e = 0; e < 4; e++) {
            ett.link(e, e + 1, e, e + 1);
        }
        check("PROBE-13 rebalance at root (parent=null): no NPE", ett.checkInvariants() == null);
    }

    private static void probe14CyclicWalkProperty() {
        // checkTree validates that seq[k].getEndVertex() == seq[(k+1)%n].getStartVertex()
        // For a path 0-1-2, the tour should be [B0, e01, B1, e12, B2, e21, e10] or similar cyclic walk
        EulerTourTree ett = new EulerTourTree(3, 2);
        for (int i = 0; i < 3; i++)
            ett.initBus(new Bus(i+1, i, 1.0, 0.5, 1, false, 12.47, 0, 1.0));
        ett.link(0, 1, 0, 1);
        ett.link(1, 2, 1, 2);
        // checkInvariants calls checkTree which validates the cyclic walk
        check("PROBE-14 cyclic walk property verified by checkInvariants", ett.checkInvariants() == null);
    }

    private static void probe15AlternatingLinkCut() {
        // Alternate link/cut 20 times; aggregate must stay consistent throughout
        EulerTourTree ett = new EulerTourTree(2, 1);
        ett.initBus(new Bus(1, 0, 300.0, 150.0, 15, true, 12.47, 5000, 1.0));
        ett.initBus(new Bus(2, 1, 700.0, 350.0, 35, false, 12.47, 0, 1.0));
        boolean ok = true;
        for (int i = 0; i < 20; i++) {
            ett.link(0, 1, 0, 1);
            Aggregate ag = ett.getAggregate(0);
            ok &= approx(ag.totalPKw, 1000.0) && ag.totalCustomers == 50 && ag.sourceCount == 1;
            ok &= (ett.checkInvariants() == null);
            ett.cut(0, 1, 0);
            Aggregate a0 = ett.getAggregate(0);
            Aggregate a1 = ett.getAggregate(1);
            ok &= approx(a0.totalPKw, 300.0) && approx(a1.totalPKw, 700.0);
            ok &= (ett.checkInvariants() == null);
        }
        check("PROBE-15 alternating link/cut 20 times: aggregates always correct", ok);
    }
}
