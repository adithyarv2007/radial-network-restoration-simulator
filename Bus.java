/**
 * Immutable bus record after validation and dense internal indexing.
 */
public class Bus {
    public final int id;
    public final int internalIndex;
    public final double pKw;
    public final double qKvar;
    public final int customerCount;
    public final boolean hasSubstation;
    public final double vBaseKv;
    public final double sourceCapacityKva;
    public final double sourceVoltagePu;

    /**
     * Worst-case time: O(1) - assigns nine fields.
     * Auxiliary space: O(1).
     */
    public Bus(int id, int internalIndex, double pKw, double qKvar, int customerCount,
               boolean hasSubstation, double vBaseKv, double sourceCapacityKva, double sourceVoltagePu) {
        this.id = id;
        this.internalIndex = internalIndex;
        this.pKw = pKw;
        this.qKvar = qKvar;
        this.customerCount = customerCount;
        this.hasSubstation = hasSubstation;
        this.vBaseKv = vBaseKv;
        this.sourceCapacityKva = sourceCapacityKva;
        this.sourceVoltagePu = sourceVoltagePu;
    }
}
