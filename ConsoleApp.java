import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Locale;

/**
 * Console front end. Parses the three-block text format, drives SwitchingSimulator, and prints reports.
 * Contains no graph or tree algorithms.
 * Usage: java ConsoleApp [inputFile]   (reads standard input when no file is given)
 *
 * Input format (blank lines and lines starting with # are ignored):
 *   BUSES <V>            then V lines: busId P_kW Q_kVAr customers hasSubstation(0|1) V_base_kV sourceCap_kVA sourceV_pu
 *   LINES <E>            then E lines: lineId busA busB R_ohm X_ohm Smax_kVA initiallyClosed(0|1) initiallyFaulted(0|1)
 *   COMMANDS <M>         then M lines: OPEN|CLOSE|FAULT|CLEAR_FAULT <lineId>
 */
public class ConsoleApp {
    private static String[] content;
    private static int[] lineNumbers;
    private static int cursor;

    /**
     * Entry point. Input errors exit with status 1 before any simulation starts.
     * Worst-case time: O((V + E) log(V + E) + M * (V log V + E)) for M commands.
     * Auxiliary space: O(V + E) for parsed input and simulator tables.
     */
    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        try {
            readInput(args);
            Bus[] buses = parseBuses();
            Line[] lines = parseLines(buses);
            int m = parseHeader("COMMANDS");
            String[] commands = new String[m];
            for (int i = 0; i < m; i++) {
                if (cursor >= content.length) throw new IllegalArgumentException("COMMANDS block has fewer than " + m + " lines");
                commands[i] = content[cursor++];
            }
            if (cursor < content.length) {
                throw new IllegalArgumentException("line " + lineNumbers[cursor] + ": unexpected content after COMMANDS block");
            }
            SwitchingSimulator sim = new SwitchingSimulator(buses, lines);
            if (sim.getInitError() != null) {
                System.out.println("INITIALISATION ERROR: " + sim.getInitError());
                System.exit(2);
            }
            printOperationResult(sim.getInitialState());
            for (String c : commands) printOperationResult(processCommand(sim, c));
            printPlanSummary(sim);
        } catch (IllegalArgumentException e) {
            System.out.println("INPUT ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Reads all input and keeps only meaningful lines together with their original line numbers.
     * Worst-case time: O(N) for N input characters. Auxiliary space: O(N).
     */
    private static void readInput(String[] args) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = (args.length > 0)
                    ? new BufferedReader(new FileReader(args[0]))
                    : new BufferedReader(new InputStreamReader(System.in));
            String s;
            while ((s = reader.readLine()) != null) sb.append(s).append('\n');
            reader.close();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read input: " + e.getMessage());
        }
        String[] raw = sb.toString().split("\n", -1);
        content = new String[raw.length];
        lineNumbers = new int[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i].trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            content[count] = t;
            lineNumbers[count] = i + 1;
            count++;
        }
        content = Arrays.copyOf(content, count);
        lineNumbers = Arrays.copyOf(lineNumbers, count);
        cursor = 0;
    }

    /**
     * Parses "BUSES n" and the n bus records, validating every field.
     * Worst-case time: O(V log V) for the duplicate-ID check. Auxiliary space: O(V).
     */
    private static Bus[] parseBuses() {
        int n = parseHeader("BUSES");
        Bus[] out = new Bus[n];
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            if (cursor >= content.length) throw new IllegalArgumentException("BUSES block has fewer than " + n + " records");
            int no = lineNumbers[cursor];
            String[] t = tokens(content[cursor++]);
            if (t.length != 8) throw new IllegalArgumentException("line " + no + ": bus record needs 8 fields");
            int id = parseInt(t[0], no, "busId");
            if (id <= 0) throw new IllegalArgumentException("line " + no + ": busId must be positive");
            double p = parseDouble(t[1], no, "P_kW");
            double q = parseDouble(t[2], no, "Q_kVAr");
            int cust = parseInt(t[3], no, "customers");
            int sub = parseInt(t[4], no, "hasSubstation");
            double vb = parseDouble(t[5], no, "V_base_kV");
            double cap = parseDouble(t[6], no, "sourceCapacity_kVA");
            double vs = parseDouble(t[7], no, "sourceVoltage_pu");
            if (p < 0.0) throw new IllegalArgumentException("line " + no + ": P_kW must be non-negative");
            if (cust < 0) throw new IllegalArgumentException("line " + no + ": customers must be non-negative");
            if (sub != 0 && sub != 1) throw new IllegalArgumentException("line " + no + ": hasSubstation must be 0 or 1");
            if (vb <= 0.0) throw new IllegalArgumentException("line " + no + ": V_base_kV must be positive");
            if (cap < 0.0) throw new IllegalArgumentException("line " + no + ": sourceCapacity_kVA must be non-negative");
            if (sub == 1) {
                if (cap <= 0.0) throw new IllegalArgumentException("line " + no + ": a substation needs positive capacity");
                if (vs < 0.95 - 1e-9 || vs > 1.05 + 1e-9) {
                    throw new IllegalArgumentException("line " + no + ": source voltage must lie in [0.95, 1.05] pu");
                }
            }
            out[i] = new Bus(id, i, p, q, cust, sub == 1, vb, cap, vs);
            ids[i] = id;
        }
        checkDuplicates(ids, "bus");
        return out;
    }

    /**
     * Parses "LINES m" and the m line records; endpoints must name existing buses.
     * Worst-case time: O(E log V + E log E). Auxiliary space: O(V + E).
     */
    private static Line[] parseLines(Bus[] buses) {
        int m = parseHeader("LINES");
        int[] busIds = new int[buses.length];
        for (int i = 0; i < buses.length; i++) busIds[i] = buses[i].id;
        Arrays.sort(busIds);
        Line[] out = new Line[m];
        int[] ids = new int[m];
        for (int i = 0; i < m; i++) {
            if (cursor >= content.length) throw new IllegalArgumentException("LINES block has fewer than " + m + " records");
            int no = lineNumbers[cursor];
            String[] t = tokens(content[cursor++]);
            if (t.length != 8) throw new IllegalArgumentException("line " + no + ": line record needs 8 fields");
            int id = parseInt(t[0], no, "lineId");
            int a = parseInt(t[1], no, "busA");
            int b = parseInt(t[2], no, "busB");
            double r = parseDouble(t[3], no, "R_ohm");
            double x = parseDouble(t[4], no, "X_ohm");
            double smax = parseDouble(t[5], no, "Smax_kVA");
            int closed = parseInt(t[6], no, "initiallyClosed");
            int faulted = parseInt(t[7], no, "initiallyFaulted");
            if (id <= 0) throw new IllegalArgumentException("line " + no + ": lineId must be positive");
            if (a == b) throw new IllegalArgumentException("line " + no + ": a line cannot join a bus to itself");
            if (Arrays.binarySearch(busIds, a) < 0 || Arrays.binarySearch(busIds, b) < 0) {
                throw new IllegalArgumentException("line " + no + ": endpoint bus does not exist");
            }
            if (r < 0.0 || x < 0.0) throw new IllegalArgumentException("line " + no + ": R and X must be non-negative");
            if (smax <= 0.0) throw new IllegalArgumentException("line " + no + ": Smax_kVA must be positive");
            if ((closed != 0 && closed != 1) || (faulted != 0 && faulted != 1)) {
                throw new IllegalArgumentException("line " + no + ": flags must be 0 or 1");
            }
            out[i] = new Line(id, i, a, b, 0, 0, r, x, smax, closed == 1, faulted == 1);
            ids[i] = id;
        }
        checkDuplicates(ids, "line");
        return out;
    }

    /**
     * Routes one command line to the simulator. Malformed commands are counted and rejected, never thrown.
     * Worst-case time: O(log E) for lookup plus the simulator cost. Auxiliary space: O(1) beyond the result.
     */
    private static OperationResult processCommand(SwitchingSimulator sim, String text) {
        String[] t = tokens(text);
        if (t.length != 2) return sim.rejectMalformed(text, "expected '<OP_NAME> <lineId>'");
        int id;
        try {
            id = Integer.parseInt(t[1]);
        } catch (NumberFormatException e) {
            return sim.rejectMalformed(text, "lineId is not an integer");
        }
        switch (t[0].toUpperCase(Locale.ROOT)) {
            case "OPEN": return sim.open(id);
            case "CLOSE": return sim.close(id);
            case "FAULT": return sim.fault(id);
            case "CLEAR_FAULT": return sim.clearFault(id);
            default: return sim.rejectMalformed(text, "unknown operation '" + t[0] + "'");
        }
    }

    /** Prints one result block. Rejections print a single line. Worst-case time: O(V + W). */
    private static void printOperationResult(OperationResult r) {
        if (!r.accepted) {
            System.out.println("REJECTED: " + r.operationText + " - " + r.statusMessage);
            return;
        }
        String header = r.operationText.equals("INITIAL STATE") ? "INITIAL STATE" : "ACCEPTED: " + r.operationText;
        System.out.println(header + " - " + r.statusMessage);
        for (ComponentStatus c : r.components) {
            String state = c.hasSource ? "ENERGIZED (source bus " + c.sourceBusId + ")" : "DE-ENERGIZED ISLAND";
            System.out.println(String.format("  Component %d: buses %s | %s | P=%.2f kW, Q=%.2f kVAr, customers=%d",
                    c.componentId, joinIds(c.busIds), state, c.totalPKw, c.totalQKvar, c.totalCustomers));
            if (c.electricalReport.violationCount == 0) {
                System.out.println("      violations: none");
            } else {
                for (String v : c.electricalReport.violations) System.out.println("      " + v);
            }
        }
        System.out.println(String.format("  Network unserved: P=%.2f kW, Q=%.2f kVAr, customers=%d | electrical violations=%d",
                r.totalUnservedPKw, r.totalUnservedQKvar, r.totalDisconnectedCustomers, r.totalElectricalViolations));
    }

    /** Prints the cumulative plan summary. Worst-case time: O(1). */
    private static void printPlanSummary(SwitchingSimulator sim) {
        System.out.println("==== SWITCHING PLAN SUMMARY ====");
        System.out.println("Commands: total=" + sim.getTotalCommands() + ", accepted=" + sim.getAcceptedCommands()
                + ", rejected=" + sim.getRejectedCommands());
        System.out.println(String.format("Initial unserved: P=%.2f kW, Q=%.2f kVAr, customers=%d",
                sim.getInitialUnservedPKw(), sim.getInitialUnservedQKvar(), sim.getInitialDisconnectedCustomers()));
        System.out.println(String.format("Final unserved:   P=%.2f kW, Q=%.2f kVAr, customers=%d",
                sim.getUnservedPKw(), sim.getUnservedQKvar(), sim.getUnservedCustomers()));
        int v = sim.getLastViolationCount();
        if (v == 0) System.out.println("Final electrical screen: PASSED (no violations)");
        else System.out.println("Final electrical screen: " + v + " violation(s) - see the last accepted report");
        System.out.println("This is a linearized planning screen, not a safety certification or an SCADA command.");
    }

    /** Worst-case time: O(k) for k ids. Auxiliary space: O(k). */
    private static String joinIds(int[] ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids[i]);
        }
        return sb.append(']').toString();
    }

    /** Worst-case time: O(n log n). Auxiliary space: O(n). Rejects repeated IDs. */
    private static void checkDuplicates(int[] ids, String what) {
        int[] copy = Arrays.copyOf(ids, ids.length);
        Arrays.sort(copy);
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] == copy[i - 1]) throw new IllegalArgumentException("duplicate " + what + " ID " + copy[i]);
        }
    }

    /** Worst-case time: O(1) per call. Consumes a 'KEYWORD count' header line. */
    private static int parseHeader(String keyword) {
        if (cursor >= content.length) throw new IllegalArgumentException("missing " + keyword + " header");
        int no = lineNumbers[cursor];
        String[] t = tokens(content[cursor]);
        if (t.length != 2 || !t[0].equals(keyword)) {
            throw new IllegalArgumentException("line " + no + ": expected '" + keyword + " <count>'");
        }
        int n = parseInt(t[1], no, keyword + " count");
        if (n < 0) throw new IllegalArgumentException("line " + no + ": count must be non-negative");
        cursor++;
        return n;
    }

    /** Worst-case time: O(length of token). Auxiliary space: O(tokens). */
    private static String[] tokens(String s) {
        return s.trim().split("\\s+");
    }

    /** Worst-case time: O(length). Auxiliary space: O(1). Converts with a line-numbered error message. */
    private static int parseInt(String s, int no, String field) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + no + ": " + field + " must be an integer");
        }
    }

    /** Worst-case time: O(length). Auxiliary space: O(1). Rejects NaN and infinities. */
    private static double parseDouble(String s, int no, String field) {
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + no + ": " + field + " must be a number");
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("line " + no + ": " + field + " must be finite");
        }
        return v;
    }
}
