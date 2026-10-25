/**
 * Independent reference model for randomized testing. It uses a boolean adjacency matrix and plain DFS,
 * and shares no code or data structures with EulerTourTree.
 */
public class ReferenceForest {
    private final int n;
    private final boolean[][] adj;
    private final double[] p;
    private final double[] q;
    private final int[] cust;
    private final boolean[] src;

    /**
     * Worst-case time: O(V^2) - allocates the adjacency matrix.
     * Auxiliary space: O(V^2).
     */
    public ReferenceForest(int n, double[] p, double[] q, int[] cust, boolean[] src) {
        this.n = n;
        this.adj = new boolean[n][n];
        this.p = p;
        this.q = q;
        this.cust = cust;
        this.src = src;
    }

    /** Worst-case time: O(1). Auxiliary space: O(1). */
    public void addEdge(int u, int v) { adj[u][v] = true; adj[v][u] = true; }

    /** Worst-case time: O(1). Auxiliary space: O(1). */
    public void removeEdge(int u, int v) { adj[u][v] = false; adj[v][u] = false; }

    /**
     * Worst-case time: O(V^2) - one DFS over the matrix.
     * Auxiliary space: O(V).
     */
    public boolean connected(int u, int v) { return reach(u)[v]; }

    /**
     * Returns {P, Q, customers, sources} of u's component by summing over the DFS reach set.
     * Worst-case time: O(V^2). Auxiliary space: O(V).
     */
    public double[] aggregate(int u) {
        boolean[] comp = reach(u);
        double sp = 0.0;
        double sq = 0.0;
        double sc = 0.0;
        double ss = 0.0;
        for (int i = 0; i < n; i++) {
            if (comp[i]) {
                sp += p[i];
                sq += q[i];
                sc += cust[i];
                ss += src[i] ? 1 : 0;
            }
        }
        return new double[] { sp, sq, sc, ss };
    }

    /**
     * Iterative DFS with an explicit stack. Each vertex is pushed at most once.
     * Worst-case time: O(V^2) on the matrix. Auxiliary space: O(V).
     */
    private boolean[] reach(int s) {
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int top = 0;
        stack[top++] = s;
        seen[s] = true;
        while (top > 0) {
            int x = stack[--top];
            for (int y = 0; y < n; y++) {
                if (adj[x][y] && !seen[y]) {
                    seen[y] = true;
                    stack[top++] = y;
                }
            }
        }
        return seen;
    }
}
