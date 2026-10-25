/**
 * Euler Tour Tree: each tree component is stored as one AVL sequence tree of directed tokens.
 * Bus tokens carry demand; edge tokens carry zero demand. Root aggregates give component totals.
 *
 * Operation summary (all worst-case, deterministic, O(log V)):
 *   link, cut, connected, getAggregate, getComponentRepresentativeIndex, componentHeight.
 * Recursion is used only inside join3, join, extractRightmost and checkNode; each call depth is
 * bounded by the AVL height, which is at most about 1.4404 * log2(3V) and therefore O(log V).
 */
public class EulerTourTree {
    private static final double TOL = 1e-6;

    private final int numBuses;
    private final int numLines;
    private final AVLNode[] busToNode;
    private final AVLNode[] lineToEdgeUV;
    private final AVLNode[] lineToEdgeVU;
    private int nextNodeId;

    /**
     * Worst-case time: O(V + E) - allocates three reference arrays.
     * Auxiliary space: O(V + E) - those arrays. Deterministic.
     */
    public EulerTourTree(int numBuses, int numLines) {
        this.numBuses = numBuses;
        this.numLines = numLines;
        this.busToNode = new AVLNode[numBuses];
        this.lineToEdgeUV = new AVLNode[numLines];
        this.lineToEdgeVU = new AVLNode[numLines];
        this.nextNodeId = 1;
    }

    /**
     * Creates the singleton tour [B_u] for one bus.
     * Worst-case time: O(1) - one BusToken and one AVLNode allocated.
     * Auxiliary space: O(1). Deterministic.
     */
    public void initBus(Bus bus) {
        BusToken token = new BusToken(bus.internalIndex, bus.id, bus.pKw, bus.qKvar,
                bus.customerCount, bus.hasSubstation);
        AVLNode node = new AVLNode(token);
        node.id = nextNodeId++;
        busToNode[bus.internalIndex] = node;
    }

    /**
     * Joins the tours of two disconnected buses through an open line. Returns false and changes nothing if
     * the buses are already connected.
     * Worst-case time: O(log V) - two reroots, two node allocations, two join3 calls.
     * Auxiliary space: O(log V) - recursion depth of join3 bounded by AVL height.
     */
    public boolean link(int uIndex, int vIndex, int lineIndex, int lineId) {
        if (connected(uIndex, vIndex)) return false;
        AVLNode tourU = reroot(uIndex);
        AVLNode tourV = reroot(vIndex);
        AVLNode nodeUV = newEdgeNode(uIndex, vIndex, lineId);
        AVLNode nodeVU = newEdgeNode(vIndex, uIndex, lineId);
        lineToEdgeUV[lineIndex] = nodeUV;
        lineToEdgeVU[lineIndex] = nodeVU;
        AVLNode merged = join3(tourU, nodeUV, tourV);
        join3(merged, nodeVU, null);
        return true;
    }

    /**
     * Removes the active edge on lineIndex and splits its tour into two tours.
     * Returns false if the line is not active or the endpoints do not match the stored tokens.
     * Worst-case time: O(log V) - two getRank climbs, two splitNode calls, one join.
     * Auxiliary space: O(log V) - recursion depth bounded by AVL height.
     */
    public boolean cut(int uIndex, int vIndex, int lineIndex) {
        AVLNode e1 = lineToEdgeUV[lineIndex];
        AVLNode e2 = lineToEdgeVU[lineIndex];
        if (e1 == null || e2 == null) return false;
        EdgeToken t1 = (EdgeToken) e1.token;
        boolean sameOrder = t1.getFromBusIndex() == uIndex && t1.getToBusIndex() == vIndex;
        boolean reverse = t1.getFromBusIndex() == vIndex && t1.getToBusIndex() == uIndex;
        if (!sameOrder && !reverse) return false;
        if (getRank(e1) > getRank(e2)) {
            AVLNode tmp = e1;
            e1 = e2;
            e2 = tmp;
        }
        AVLNode[] first = splitNode(e1);      // first[0] = before e1, first[1] = after e1 (contains e2)
        AVLNode[] second = splitNode(e2);     // second[0] = between e1 and e2, second[1] = after e2
        join(first[0], second[1]);            // outer tour; second[0] is the detached inner tour
        lineToEdgeUV[lineIndex] = null;
        lineToEdgeVU[lineIndex] = null;
        return true;
    }

    /**
     * Two buses are connected iff their AVL roots are the same object.
     * Worst-case time: O(log V) - two root climbs of length at most the AVL height.
     * Auxiliary space: O(1). Deterministic.
     */
    public boolean connected(int uIndex, int vIndex) {
        return findRoot(busToNode[uIndex]) == findRoot(busToNode[vIndex]);
    }

    /**
     * Returns the demand and source totals of the component containing uIndex, read from the root.
     * Worst-case time: O(log V) - one root climb plus O(1) field reads.
     * Auxiliary space: O(1) for the climb, one Aggregate object returned.
     */
    public Aggregate getAggregate(int uIndex) {
        AVLNode root = findRoot(busToNode[uIndex]);
        return new Aggregate(root.subtreeP, root.subtreeQ, root.subtreeCustomers, root.subtreeSources);
    }

    /**
     * Returns the id of the AVL root of uIndex's component. Two buses share a value iff they are connected.
     * Worst-case time: O(log V) - one root climb.
     * Auxiliary space: O(1).
     */
    public int getComponentRepresentativeIndex(int uIndex) {
        return findRoot(busToNode[uIndex]).id;
    }

    /**
     * Height of the AVL tree holding uIndex's tour. Used by tests to check the balance bound.
     * Worst-case time: O(log V) - one root climb.
     * Auxiliary space: O(1).
     */
    public int componentHeight(int uIndex) {
        return findRoot(busToNode[uIndex]).height;
    }

    /**
     * Full structural audit: parent links, heights, aggregates, AVL balance, tour length and the cyclic
     * walk property, plus line-token consistency. Returns null if everything holds, else a message.
     * Worst-case time: O(V + E) - visits every node once (used only in tests).
     * Auxiliary space: O(V) - seen-root marks and one token sequence per tree.
     */
    public String checkInvariants() {
        boolean[] seen = new boolean[nextNodeId + 1];
        for (int i = 0; i < numBuses; i++) {
            if (busToNode[i] == null) return "bus " + i + " has no node";
            AVLNode root = findRoot(busToNode[i]);
            if (seen[root.id]) continue;
            seen[root.id] = true;
            String err = checkTree(root);
            if (err != null) return err;
        }
        for (int li = 0; li < numLines; li++) {
            AVLNode a = lineToEdgeUV[li];
            AVLNode b = lineToEdgeVU[li];
            if ((a == null) != (b == null)) return "line index " + li + " has only one directed token";
            if (a != null && findRoot(a) != findRoot(b)) return "line index " + li + " tokens in different trees";
        }
        return null;
    }

    /** Worst-case time: O(log V) - climbs parent links to the root. Auxiliary space: O(1). */
    private AVLNode findRoot(AVLNode node) {
        AVLNode cur = node;
        while (cur.parent != null) cur = cur.parent;
        return cur;
    }

    /**
     * 0-based in-order index of node inside its AVL tree.
     * Worst-case time: O(log V) - climbs to the root, adding left-subtree sizes when coming from a right child.
     * Auxiliary space: O(1).
     */
    private int getRank(AVLNode node) {
        int rank = AVLNode.sizeOf(node.left);
        AVLNode cur = node;
        while (cur.parent != null) {
            AVLNode p = cur.parent;
            if (p.right == cur) rank += 1 + AVLNode.sizeOf(p.left);
            cur = p;
        }
        return rank;
    }

    /**
     * Makes uIndex's bus token the first token of its tour by cyclic rotation.
     * Worst-case time: O(log V) - one splitNode, one join3, one join.
     * Auxiliary space: O(log V) - recursion depth.
     */
    private AVLNode reroot(int uIndex) {
        AVLNode nodeU = busToNode[uIndex];
        AVLNode[] parts = splitNode(nodeU);             // parts[0] = before nodeU, parts[1] = after nodeU
        AVLNode withU = join3(null, nodeU, parts[1]);   // [nodeU] + after
        return join(withU, parts[0]);                   // ([nodeU] + after) + before
    }

    /**
     * Splits a tree at node x: returns {tokens strictly before x, tokens strictly after x}; x is left isolated.
     * Worst-case time: O(log V). Each ancestor is folded in by one join3; height differences telescope.
     * Auxiliary space: O(1) extra beyond join3 recursion, which is O(log V).
     */
    private AVLNode[] splitNode(AVLNode x) {
        AVLNode leftAcc = x.left;
        AVLNode rightAcc = x.right;
        if (leftAcc != null) leftAcc.parent = null;
        if (rightAcc != null) rightAcc.parent = null;
        x.left = null;
        x.right = null;
        AVLNode curr = x;
        AVLNode p = x.parent;
        x.parent = null;
        x.updateAggregates();
        while (p != null) {
            AVLNode nextP = p.parent;
            boolean currIsRight = (p.right == curr);
            AVLNode pLeft = p.left;
            AVLNode pRight = p.right;
            p.left = null;
            p.right = null;
            p.parent = null;
            p.updateAggregates();
            // The path child 'curr' has already been folded into an accumulator, so its parent pointer
            // belongs to that accumulator and must NOT be cleared. Only the kept sibling is detached here.
            if (currIsRight) {
                if (pLeft != null) pLeft.parent = null;
                leftAcc = join3(pLeft, p, leftAcc);
            } else {
                if (pRight != null) pRight.parent = null;
                rightAcc = join3(rightAcc, p, pRight);
            }
            curr = p;
            p = nextP;
        }
        return new AVLNode[] { leftAcc, rightAcc };
    }

    /**
     * Concatenates two sequence trees: left + right.
     * Worst-case time: O(log V) - extractRightmost then join3.
     * Auxiliary space: O(log V) - recursion depth.
     */
    private AVLNode join(AVLNode left, AVLNode right) {
        if (left == null) {
            if (right != null) right.parent = null;
            return right;
        }
        if (right == null) {
            left.parent = null;
            return left;
        }
        AVLNode[] ex = extractRightmost(left);
        return join3(ex[0], ex[1], right);
    }

    /**
     * Joins left + mid + right, where mid is an isolated node. Height-balanced result.
     * Worst-case time: O(|h(left) - h(right)| + 1) <= O(log V) - walks down the taller spine only.
     * Auxiliary space: O(|h(left) - h(right)| + 1) <= O(log V) - recursion depth.
     */
    private AVLNode join3(AVLNode left, AVLNode mid, AVLNode right) {
        if (left != null) left.parent = null;
        if (right != null) right.parent = null;
        mid.parent = null;
        int hl = AVLNode.heightOf(left);
        int hr = AVLNode.heightOf(right);
        if (hl > hr + 1) {
            AVLNode t = join3(left.right, mid, right);
            AVLNode.setRight(left, t);
            return rebalance(left);
        }
        if (hr > hl + 1) {
            AVLNode t = join3(left, mid, right.left);
            AVLNode.setLeft(right, t);
            return rebalance(right);
        }
        AVLNode.setLeft(mid, left);
        AVLNode.setRight(mid, right);
        mid.updateAggregates();
        return mid;
    }

    /**
     * Removes the last node of a tree. Returns {remaining tree, removed node}; removed node is isolated.
     * Worst-case time: O(log V) - walks the right spine, rebalancing at each level.
     * Auxiliary space: O(log V) - recursion depth bounded by AVL height.
     */
    private AVLNode[] extractRightmost(AVLNode node) {
        node.parent = null;
        if (node.right == null) {
            AVLNode rest = node.left;
            if (rest != null) rest.parent = null;
            node.left = null;
            node.updateAggregates();
            return new AVLNode[] { rest, node };
        }
        AVLNode child = node.right;
        node.right = null;
        child.parent = null;
        AVLNode[] res = extractRightmost(child);
        AVLNode.setRight(node, res[0]);
        AVLNode newRoot = rebalance(node);
        return new AVLNode[] { newRoot, res[1] };
    }

    /**
     * Recomputes x and applies at most one single or double rotation. x must be a root (parent null).
     * Worst-case time: O(1) - at most two rotations and three updateAggregates calls.
     * Auxiliary space: O(1).
     */
    private AVLNode rebalance(AVLNode x) {
        x.updateAggregates();
        int bf = AVLNode.heightOf(x.left) - AVLNode.heightOf(x.right);
        if (bf > 1) {
            if (AVLNode.heightOf(x.left.left) < AVLNode.heightOf(x.left.right)) {
                AVLNode.setLeft(x, rotateLeft(x.left));
            }
            return rotateRight(x);
        }
        if (bf < -1) {
            if (AVLNode.heightOf(x.right.right) < AVLNode.heightOf(x.right.left)) {
                AVLNode.setRight(x, rotateRight(x.right));
            }
            return rotateLeft(x);
        }
        return x;
    }

    /**
     * Left rotation at x. Lower node x is updated before the new parent y.
     * Worst-case time: O(1) - constant pointer updates and two updateAggregates calls.
     * Auxiliary space: O(1).
     */
    private AVLNode rotateLeft(AVLNode x) {
        AVLNode y = x.right;
        AVLNode p = x.parent;
        AVLNode.setRight(x, y.left);
        AVLNode.setLeft(y, x);
        y.parent = p;
        replaceChild(p, x, y);
        x.updateAggregates();
        y.updateAggregates();
        return y;
    }

    /**
     * Right rotation at y. Lower node y is updated before the new parent x.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    private AVLNode rotateRight(AVLNode y) {
        AVLNode x = y.left;
        AVLNode p = y.parent;
        AVLNode.setLeft(y, x.right);
        AVLNode.setRight(x, y);
        x.parent = p;
        replaceChild(p, y, x);
        y.updateAggregates();
        x.updateAggregates();
        return x;
    }

    /**
     * Points p's child link from oldChild to newChild. No-op when p is null.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    private void replaceChild(AVLNode p, AVLNode oldChild, AVLNode newChild) {
        if (p == null) return;
        if (p.left == oldChild) p.left = newChild;
        else if (p.right == oldChild) p.right = newChild;
    }

    /**
     * Allocates a directed edge node with a unique id.
     * Worst-case time: O(1). Auxiliary space: O(1).
     */
    private AVLNode newEdgeNode(int from, int to, int lineId) {
        AVLNode n = new AVLNode(new EdgeToken(from, to, lineId));
        n.id = nextNodeId++;
        return n;
    }

    /**
     * Checks the AVL invariants of one tree and the cyclic walk property over its tour.
     * Worst-case time: O(V_c) for a tree of V_c buses (test only).
     * Auxiliary space: O(V_c) for the token sequence.
     */
    private String checkTree(AVLNode root) {
        if (root.parent != null) return "tree root has a non-null parent";
        int[] count = new int[1];
        String err = checkNode(root, count);
        if (err != null) return err;
        Token[] seq = new Token[count[0]];
        int[] pos = new int[1];
        collectInOrder(root, seq, pos);
        int busCount = 0;
        boolean[] seenBus = new boolean[numBuses];
        for (int k = 0; k < seq.length; k++) {
            Token t = seq[k];
            if (t.isBusToken()) {
                int bi = t.getBusIndex();
                if (bi < 0 || bi >= numBuses || seenBus[bi]) return "duplicate or invalid bus token " + bi;
                if (findRoot(busToNode[bi]) != root) return "bus token " + bi + " is in the wrong tree";
                seenBus[bi] = true;
                busCount++;
            }
        }
        if (seq.length != 3 * busCount - 2) {
            return "tour length " + seq.length + " is not 3*" + busCount + " - 2";
        }
        for (int k = 0; k < seq.length; k++) {
            int nxt = (k + 1) % seq.length;
            if (seq[k].getEndVertex() != seq[nxt].getStartVertex()) {
                return "cyclic walk broken between tour positions " + k + " and " + nxt;
            }
        }
        return null;
    }

    /**
     * Verifies parent links, height, balance and aggregates bottom-up. Counts nodes in count[0].
     * Worst-case time: O(size of tree). Auxiliary space: O(height) recursion.
     */
    private String checkNode(AVLNode n, int[] count) {
        if (n == null) return null;
        if (n.left != null && n.left.parent != n) return "left child has a wrong parent pointer";
        if (n.right != null && n.right.parent != n) return "right child has a wrong parent pointer";
        String err = checkNode(n.left, count);
        if (err != null) return err;
        err = checkNode(n.right, count);
        if (err != null) return err;
        count[0]++;
        int hl = AVLNode.heightOf(n.left);
        int hr = AVLNode.heightOf(n.right);
        if (n.height != 1 + Math.max(hl, hr)) return "stored height is wrong";
        if (Math.abs(hl - hr) > 1) return "AVL balance factor out of range";
        if (n.subtreeSize != 1 + AVLNode.sizeOf(n.left) + AVLNode.sizeOf(n.right)) return "subtree size is wrong";
        double expP = n.token.getP() + AVLNode.pOf(n.left) + AVLNode.pOf(n.right);
        double expQ = n.token.getQ() + AVLNode.qOf(n.left) + AVLNode.qOf(n.right);
        if (Math.abs(n.subtreeP - expP) > TOL || Math.abs(n.subtreeQ - expQ) > TOL) return "demand aggregate is wrong";
        if (n.subtreeCustomers != n.token.getCustomers() + AVLNode.custOf(n.left) + AVLNode.custOf(n.right)) {
            return "customer aggregate is wrong";
        }
        if (n.subtreeSources != n.token.getSourceCount() + AVLNode.srcOf(n.left) + AVLNode.srcOf(n.right)) {
            return "source aggregate is wrong";
        }
        return null;
    }

    /**
     * In-order traversal into an array.
     * Worst-case time: O(size). Auxiliary space: O(height) recursion.
     */
    private void collectInOrder(AVLNode n, Token[] out, int[] pos) {
        if (n == null) return;
        collectInOrder(n.left, out, pos);
        out[pos[0]++] = n.token;
        collectInOrder(n.right, out, pos);
    }
}
