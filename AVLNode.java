/**
 * AVL sequence-tree node. The token reference is final: nodes are relinked, never payload-swapped,
 * so external lookup arrays stay valid.
 */
public class AVLNode {
    public final Token token;
    public AVLNode left;
    public AVLNode right;
    public AVLNode parent;
    public int height;
    public int subtreeSize;
    public double subtreeP;
    public double subtreeQ;
    public int subtreeCustomers;
    public int subtreeSources;
    public int id;

    /**
     * Worst-case time: O(1) - reads four token accessors.
     * Auxiliary space: O(1).
     */
    public AVLNode(Token token) {
        this.token = token;
        this.height = 1;
        this.subtreeSize = 1;
        this.subtreeP = token.getP();
        this.subtreeQ = token.getQ();
        this.subtreeCustomers = token.getCustomers();
        this.subtreeSources = token.getSourceCount();
    }

    /**
     * Recomputes height and all aggregates from the token and the two children.
     * Worst-case time: O(1) - fixed number of arithmetic operations on child fields.
     * Auxiliary space: O(1). Deterministic.
     */
    public void updateAggregates() {
        this.height = 1 + Math.max(heightOf(left), heightOf(right));
        this.subtreeSize = 1 + sizeOf(left) + sizeOf(right);
        this.subtreeP = token.getP() + pOf(left) + pOf(right);
        this.subtreeQ = token.getQ() + qOf(left) + qOf(right);
        this.subtreeCustomers = token.getCustomers() + custOf(left) + custOf(right);
        this.subtreeSources = token.getSourceCount() + srcOf(left) + srcOf(right);
    }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - height(left) - height(right). */
    public int getBalanceFactor() { return heightOf(left) - heightOf(right); }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe height. */
    public static int heightOf(AVLNode n) { return n == null ? 0 : n.height; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe subtree size. */
    public static int sizeOf(AVLNode n) { return n == null ? 0 : n.subtreeSize; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe subtree active demand. */
    public static double pOf(AVLNode n) { return n == null ? 0.0 : n.subtreeP; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe subtree reactive demand. */
    public static double qOf(AVLNode n) { return n == null ? 0.0 : n.subtreeQ; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe subtree customer count. */
    public static int custOf(AVLNode n) { return n == null ? 0 : n.subtreeCustomers; }

    /** Worst-case time: O(1) | Auxiliary space: O(1) - null-safe subtree source count. */
    public static int srcOf(AVLNode n) { return n == null ? 0 : n.subtreeSources; }

    /**
     * Attaches child as the left child of parent, setting both directions of the link.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    public static void setLeft(AVLNode parent, AVLNode child) {
        parent.left = child;
        if (child != null) child.parent = parent;
    }

    /**
     * Attaches child as the right child of parent, setting both directions of the link.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    public static void setRight(AVLNode parent, AVLNode child) {
        parent.right = child;
        if (child != null) child.parent = parent;
    }
}
