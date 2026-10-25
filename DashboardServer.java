import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, single-session browser dashboard for the existing ETT-backed simulator.
 * Uses only JDK APIs and binds to loopback (127.0.0.1). All simulation decisions
 * are delegated to SwitchingSimulator; this class only parses input and presents results.
 */
public final class DashboardServer {
    private static final Path WEB_DIR = Path.of("web");
    private static final Path SCENARIO_DIR = Path.of("scenarios");
    private final Map<String, ScenarioInfo> scenarios = new LinkedHashMap<>();
    private SimulationSession session;

    private DashboardServer() throws IOException {
        loadScenarioIndex();
        if (!scenarios.containsKey("sample-network")) {
            throw new IOException("Missing scenarios/sample-network.txt. Start from the project root.");
        }
        loadScenario("sample-network");
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        int port = 8088;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Port must be an integer", e); }
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be in 1..65535");

        DashboardServer app = new DashboardServer();
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        http.createContext("/", exchange -> app.handle(exchange));
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        System.out.println("Radial Network Restoration Dashboard is running.");
        System.out.println("Open http://127.0.0.1:" + port + "/ in your browser.");
        System.out.println("Keep this terminal open while using the dashboard. Press Ctrl+C to stop.");
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                sendFile(x, WEB_DIR.resolve("index.html"), "text/html; charset=utf-8");
            } else if (method.equals("GET") && path.startsWith("/web/")) {
                sendStatic(x, path.substring(5));
            } else if (method.equals("GET") && path.equals("/api/scenarios")) {
                synchronized (this) { sendJson(x, 200, scenariosJson()); }
            } else if (method.equals("GET") && path.equals("/api/state")) {
                synchronized (this) { sendJson(x, 200, stateJson()); }
            } else if (method.equals("POST") && path.equals("/api/load")) {
                String body = readBody(x);
                String id = jsonString(body, "scenarioId");
                synchronized (this) { loadScenario(id); sendJson(x, 200, stateJson()); }
            } else if (method.equals("POST") && path.equals("/api/load-raw")) {
                String body = readBody(x);
                synchronized (this) { loadRawScenario(body); sendJson(x, 200, stateJson()); }
            } else if (method.equals("POST") && path.equals("/api/operation")) {
                String body = readBody(x);
                String op = jsonString(body, "operation").toUpperCase(Locale.ROOT);
                int lineId = Integer.parseInt(jsonString(body, "lineId"));
                synchronized (this) {
                    OperationResult r = switch (op) {
                        case "OPEN" -> session.sim.open(lineId);
                        case "CLOSE" -> session.sim.close(lineId);
                        case "FAULT" -> session.sim.fault(lineId);
                        case "CLEAR_FAULT" -> session.sim.clearFault(lineId);
                        default -> throw new IllegalArgumentException("Unsupported operation: " + op);
                    };
                    session.lastAction = r;
                    if (r.accepted && r.components != null) session.snapshot = r;
                    addHistory(r);
                    sendJson(x, 200, stateJson());
                }
            } else if (method.equals("POST") && path.equals("/api/reset")) {
                synchronized (this) {
                    if (session.custom) loadRawScenario(session.rawInput);
                    else loadScenario(session.scenarioId);
                    sendJson(x, 200, stateJson());
                }
            } else if (method.equals("POST") && path.equals("/api/verify")) {
                synchronized (this) {
                    String invariantError = session.sim.checkInvariants();
                    OperationResult r = session.sim.evaluateCurrentState("VERIFY", invariantError == null,
                            invariantError == null ? "ETT invariants hold; electrical screen recomputed."
                                    : "ETT invariant check failed: " + invariantError);
                    session.snapshot = r;
                    session.lastAction = r;
                    addHistory(r);
                    sendJson(x, 200, stateJson());
                }
            } else if (method.equals("GET") && path.equals("/api/report.csv")) {
                synchronized (this) { sendCsv(x, csvReport()); }
            } else {
                sendJson(x, 404, "{\"error\":\"Route not found\"}");
            }
        } catch (IllegalArgumentException e) {
            sendJson(x, 400, "{\"error\":" + quote(e.getMessage() == null ? "Invalid request" : e.getMessage()) + "}");
        } catch (Exception e) {
            sendJson(x, 500, "{\"error\":" + quote(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "}");
        } finally {
            x.close();
        }
    }

    private void loadScenarioIndex() throws IOException {
        Files.createDirectories(SCENARIO_DIR);
        try (var stream = Files.list(SCENARIO_DIR)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String id = p.getFileName().toString().substring(0, p.getFileName().toString().length() - 4);
                            String raw = Files.readString(p, StandardCharsets.UTF_8);
                            String title = scenarioTitle(raw, id);
                            scenarios.put(id, new ScenarioInfo(id, title, p));
                        } catch (IOException e) { throw new RuntimeException(e); }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    private synchronized void loadScenario(String id) throws IOException {
        ScenarioInfo info = scenarios.get(id);
        if (info == null) throw new IllegalArgumentException("Unknown scenario: " + id);
        String raw = Files.readString(info.path, StandardCharsets.UTF_8);
        session = createSession(info.id, info.title, raw, false);
    }

    private synchronized void loadRawScenario(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Scenario file is empty");
        session = createSession("custom-network", scenarioTitle(raw, "Custom network"), raw, true);
    }

    private SimulationSession createSession(String id, String title, String raw, boolean custom) {
        ParsedNetwork network = parseNetwork(raw);
        SwitchingSimulator sim = new SwitchingSimulator(network.buses, network.lines);
        if (sim.getInitError() != null) throw new IllegalArgumentException("INITIALISATION ERROR: " + sim.getInitError());
        SimulationSession s = new SimulationSession(id, title, raw, custom, sim);
        s.snapshot = sim.getInitialState();
        s.lastAction = s.snapshot;
        addHistory(s, s.snapshot);
        return s;
    }

    private void addHistory(OperationResult r) { addHistory(session, r); }
    private void addHistory(SimulationSession s, OperationResult r) {
        s.history.add(new HistoryEntry(s.history.size() + 1, r.operationText, r.accepted,
                r.statusMessage, r.totalUnservedPKw, r.totalUnservedQKvar,
                r.totalDisconnectedCustomers, r.totalElectricalViolations));
    }

    private String scenariosJson() {
        StringBuilder b = new StringBuilder("[ ");
        boolean first = true;
        for (ScenarioInfo info : scenarios.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(quote(info.id)).append(",\"title\":").append(quote(info.title)).append('}');
        }
        return b.append(" ]").toString();
    }

    private String stateJson() {
        if (session == null) return "{\"error\":\"No scenario loaded\"}";
        SwitchingSimulator sim = session.sim;
        StringBuilder b = new StringBuilder(8192);
        b.append("{\"scenarioId\":").append(quote(session.scenarioId))
                .append(",\"title\":").append(quote(session.title))
                .append(",\"custom\":").append(session.custom)
                .append(",\"buses\":[");
        Bus[] buses = sim.getBuses();
        for (int i = 0; i < buses.length; i++) {
            if (i > 0) b.append(',');
            Bus bus = buses[i];
            b.append("{\"id\":").append(bus.id).append(",\"pKw\":").append(num(bus.pKw))
                    .append(",\"qKvar\":").append(num(bus.qKvar)).append(",\"customers\":").append(bus.customerCount)
                    .append(",\"hasSubstation\":").append(bus.hasSubstation)
                    .append(",\"vBaseKv\":").append(num(bus.vBaseKv))
                    .append(",\"sourceCapacityKva\":").append(num(bus.sourceCapacityKva))
                    .append(",\"sourceVoltagePu\":").append(num(bus.sourceVoltagePu)).append('}');
        }
        b.append("],\"lines\":[");
        Line[] lines = sim.getLines();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append(',');
            Line line = lines[i];
            b.append("{\"id\":").append(line.id).append(",\"busAId\":").append(line.busAId)
                    .append(",\"busBId\":").append(line.busBId).append(",\"rOhm\":").append(num(line.rOhm))
                    .append(",\"xOhm\":").append(num(line.xOhm)).append(",\"sMaxKva\":").append(num(line.sMaxKva))
                    .append(",\"closed\":").append(line.isClosed()).append(",\"faulted\":").append(line.isFaulted()).append('}');
        }
        b.append("],\"summary\":{\"unservedPKw\":").append(num(sim.getUnservedPKw()))
                .append(",\"unservedQKvar\":").append(num(sim.getUnservedQKvar()))
                .append(",\"unservedCustomers\":").append(sim.getUnservedCustomers())
                .append(",\"violations\":").append(sim.getLastViolationCount())
                .append(",\"commands\":").append(sim.getTotalCommands())
                .append(",\"accepted\":").append(sim.getAcceptedCommands())
                .append(",\"rejected\":").append(sim.getRejectedCommands()).append('}')
                .append(",\"lastAction\":").append(resultJson(session.lastAction))
                .append(",\"snapshot\":").append(resultJson(session.snapshot))
                .append(",\"history\":[");
        for (int i = 0; i < session.history.size(); i++) {
            if (i > 0) b.append(',');
            HistoryEntry h = session.history.get(i);
            b.append("{\"sequence\":").append(h.sequence).append(",\"operation\":").append(quote(h.operation))
                    .append(",\"accepted\":").append(h.accepted).append(",\"message\":").append(quote(h.message))
                    .append(",\"unservedPKw\":").append(num(h.unservedPKw))
                    .append(",\"unservedQKvar\":").append(num(h.unservedQKvar))
                    .append(",\"customers\":").append(h.customers).append(",\"violations\":").append(h.violations).append('}');
        }
        return b.append("]}").toString();
    }

    private static String resultJson(OperationResult r) {
        if (r == null) return "null";
        StringBuilder b = new StringBuilder(2048);
        b.append("{\"operationText\":").append(quote(r.operationText)).append(",\"accepted\":").append(r.accepted)
                .append(",\"statusMessage\":").append(quote(r.statusMessage))
                .append(",\"totalUnservedPKw\":").append(num(r.totalUnservedPKw))
                .append(",\"totalUnservedQKvar\":").append(num(r.totalUnservedQKvar))
                .append(",\"totalDisconnectedCustomers\":").append(r.totalDisconnectedCustomers)
                .append(",\"totalElectricalViolations\":").append(r.totalElectricalViolations)
                .append(",\"components\":[");
        if (r.components != null) {
            for (int i = 0; i < r.components.length; i++) {
                if (i > 0) b.append(',');
                ComponentStatus c = r.components[i];
                ViolationReport v = c.electricalReport;
                b.append("{\"componentId\":").append(c.componentId).append(",\"busIds\":[");
                for (int j = 0; j < c.busIds.length; j++) { if (j > 0) b.append(','); b.append(c.busIds[j]); }
                b.append("],\"hasSource\":").append(c.hasSource).append(",\"sourceBusId\":").append(c.sourceBusId)
                        .append(",\"totalPKw\":").append(num(c.totalPKw)).append(",\"totalQKvar\":").append(num(c.totalQKvar))
                        .append(",\"totalCustomers\":").append(c.totalCustomers)
                        .append(",\"electricalReport\":{\"isEnergized\":").append(v.isEnergized)
                        .append(",\"violationCount\":").append(v.violationCount)
                        .append(",\"minVoltagePu\":").append(num(v.minVoltagePu))
                        .append(",\"maxLineLoadingPercent\":").append(num(v.maxLineLoadingPercent))
                        .append(",\"sourceLoadingPercent\":").append(num(v.sourceLoadingPercent)).append(",\"violations\":[");
                for (int k = 0; k < v.violations.length; k++) { if (k > 0) b.append(','); b.append(quote(v.violations[k])); }
                b.append("]}}");
            }
        }
        return b.append("]}").toString();
    }

    private String csvReport() {
        StringBuilder b = new StringBuilder("sequence,operation,accepted,message,unserved_kw,unserved_kvar,disconnected_customers,electrical_violations\r\n");
        for (HistoryEntry h : session.history) {
            b.append(h.sequence).append(',').append(csv(h.operation)).append(',').append(h.accepted).append(',')
                    .append(csv(h.message)).append(',').append(num(h.unservedPKw)).append(',').append(num(h.unservedQKvar))
                    .append(',').append(h.customers).append(',').append(h.violations).append("\r\n");
        }
        return b.toString();
    }

    private static String csv(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
    private static String num(double d) { return Double.isFinite(d) ? String.format(Locale.ROOT, "%.6f", d) : "0"; }
    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String jsonString(String json, String key) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"|(-?\\d+))");
        Matcher m = p.matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + key);
        String v = m.group(1) != null ? m.group(1) : m.group(2);
        return v.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String readBody(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void sendFile(HttpExchange x, Path path, String contentType) throws IOException {
        if (!Files.isRegularFile(path)) { sendJson(x, 404, "{\"error\":\"Missing local asset: " + path.getFileName() + "\"}"); return; }
        byte[] bytes = Files.readAllBytes(path);
        x.getResponseHeaders().set("Content-Type", contentType);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(200, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static void sendStatic(HttpExchange x, String relative) throws IOException {
        Path base = WEB_DIR.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) { sendJson(x, 403, "{\"error\":\"Forbidden\"}"); return; }
        sendFile(x, target, contentTypeFor(target.getFileName().toString()));
    }

    private static String contentTypeFor(String name) {
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange x, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static void sendCsv(HttpExchange x, String csv) throws IOException {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=restoration-report.csv");
        x.sendResponseHeaders(200, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static String scenarioTitle(String raw, String fallback) {
        for (String line : raw.split("\\R")) {
            String s = line.trim();
            if (s.toLowerCase(Locale.ROOT).startsWith("# title:")) return s.substring(8).trim();
        }
        return fallback;
    }

    private static ParsedNetwork parseNetwork(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            lines.add(s);
        }
        int pos = 0;
        if (lines.size() < 2 || !lines.get(pos).startsWith("BUSES ")) throw new IllegalArgumentException("Scenario must start with BUSES <count>");
        int n = parseCount(lines.get(pos++), "BUSES");
        if (n < 1 || n > 2000) throw new IllegalArgumentException("Bus count must be in 1..2000");
        if (pos + n >= lines.size()) throw new IllegalArgumentException("Scenario has too few bus records");
        Bus[] buses = new Bus[n];
        Set<Integer> busIds = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String[] t = tokens(lines.get(pos++));
            if (t.length != 8) throw new IllegalArgumentException("Bus record " + (i + 1) + " must contain 8 values");
            int id = integer(t[0], "bus ID");
            double p = decimal(t[1], "P_kW"), q = decimal(t[2], "Q_kVAr");
            int customers = integer(t[3], "customers"), source = integer(t[4], "hasSubstation");
            double vb = decimal(t[5], "V_base_kV"), cap = decimal(t[6], "sourceCapacity_kVA"), vs = decimal(t[7], "sourceVoltage_pu");
            if (id <= 0 || !busIds.add(id)) throw new IllegalArgumentException("Bus IDs must be positive and unique: " + id);
            if (p < 0 || customers < 0 || (source != 0 && source != 1) || vb <= 0 || cap < 0)
                throw new IllegalArgumentException("Invalid values in bus " + id);
            if (source == 1 && (cap <= 0 || vs < 0.95 || vs > 1.05))
                throw new IllegalArgumentException("Substation bus " + id + " needs positive capacity and source voltage in [0.95, 1.05]");
            buses[i] = new Bus(id, i, p, q, customers, source == 1, vb, cap, vs);
        }
        if (pos >= lines.size() || !lines.get(pos).startsWith("LINES ")) throw new IllegalArgumentException("Expected LINES <count> after bus records");
        int m = parseCount(lines.get(pos++), "LINES");
        if (m < 0 || m > 5000 || pos + m > lines.size()) throw new IllegalArgumentException("Invalid line count or too few line records");
        Line[] lineRecords = new Line[m];
        Set<Integer> lineIds = new HashSet<>();
        for (int i = 0; i < m; i++) {
            String[] t = tokens(lines.get(pos++));
            if (t.length != 8) throw new IllegalArgumentException("Line record " + (i + 1) + " must contain 8 values");
            int id = integer(t[0], "line ID"), a = integer(t[1], "busA"), b = integer(t[2], "busB");
            double r = decimal(t[3], "R_ohm"), x = decimal(t[4], "X_ohm"), smax = decimal(t[5], "Smax_kVA");
            int closed = integer(t[6], "initiallyClosed"), faulted = integer(t[7], "initiallyFaulted");
            if (id <= 0 || !lineIds.add(id)) throw new IllegalArgumentException("Line IDs must be positive and unique: " + id);
            if (!busIds.contains(a) || !busIds.contains(b) || a == b) throw new IllegalArgumentException("Line " + id + " has an invalid endpoint");
            if (r < 0 || x < 0 || smax <= 0 || (closed != 0 && closed != 1) || (faulted != 0 && faulted != 1))
                throw new IllegalArgumentException("Invalid values in line " + id);
            lineRecords[i] = new Line(id, i, a, b, 0, 0, r, x, smax, closed == 1, faulted == 1);
        }
        return new ParsedNetwork(buses, lineRecords);
    }

    private static int parseCount(String row, String name) {
        String[] t = tokens(row);
        if (t.length != 2 || !t[0].equals(name)) throw new IllegalArgumentException("Expected '" + name + " <count>'");
        return integer(t[1], name + " count");
    }
    private static String[] tokens(String s) { return s.trim().split("\\s+"); }
    private static int integer(String s, String label) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be an integer: " + s); }
    }
    private static double decimal(String s, String label) {
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be a finite number: " + s); }
    }

    private record ParsedNetwork(Bus[] buses, Line[] lines) {}
    private record ScenarioInfo(String id, String title, Path path) {}
    private static final class SimulationSession {
        final String scenarioId, title, rawInput;
        final boolean custom;
        final SwitchingSimulator sim;
        final List<HistoryEntry> history = new ArrayList<>();
        OperationResult snapshot, lastAction;
        SimulationSession(String scenarioId, String title, String rawInput, boolean custom, SwitchingSimulator sim) {
            this.scenarioId = scenarioId; this.title = title; this.rawInput = rawInput; this.custom = custom; this.sim = sim;
        }
    }
    private record HistoryEntry(int sequence, String operation, boolean accepted, String message,
                                double unservedPKw, double unservedQKvar, int customers, int violations) {}
}
