# Radial Network Restoration Simulator (ETT + AVL) with Local Dashboard

A Java 17+ project for a DSA case study on outage-restoration switching plans in radial distribution networks. The dashboard is a local web interface backed by the existing Java `SwitchingSimulator`; it does not reimplement the simulator's topology rules.

## Start the browser dashboard

**Windows:** double-click `RUN_DASHBOARD.bat`.

**PowerShell:** from this project folder, run:

```powershell
javac *.java
java DashboardServer 8088
```

Then open **http://127.0.0.1:8088/** in your browser. Keep the Java terminal open while the page is in use. See `DASHBOARD.md` for custom scenarios and troubleshooting.

## Run the existing tests and console app

```powershell
javac *.java
java RestorationTestRunner
java ConsoleApp sample_input.txt
```

All core classes are in the default package. There are no external Java dependencies and the browser assets are served locally without a CDN.


## Validate before committing

Run these from the project root after `javac *.java`:

```powershell
java RestorationTestRunner
java AdversarialAudit
java StaticAnalysisProbes
python tests/smoke-test.py
```

The first three commands cover scenario/regression tests, adversarial cases, and AVL/ETT edge-case probes. The Python smoke test starts a temporary local server and checks the browser API. For the presentation, also launch `RUN_DASHBOARD.bat` and manually click through the cycle-rejection and restoration scenarios in a browser.

## Data structure and complexity boundary

- `EulerTourTree` uses the augmented AVL sequence tree for dynamic-forest operations.
- Connectivity/link/cut and aggregate access are designed for worst-case logarithmic sequence-tree work, as implemented in the ETT/AVL classes.
- The electrical screen is a separate linearized network audit. A complete accepted switching operation includes a full screen and therefore is **not** just O(log V).
- The electrical screen is a simplified LinDistFlow planning model, not an AC power-flow calculation, protection study, SCADA controller, or safety certification.

## Project structure

- `DashboardServer.java` — loopback-only JDK HTTP server that forwards operations to `SwitchingSimulator` and serializes its results.
- `web/` — local HTML, CSS, and JavaScript UI.
- `scenarios/` — six network scenarios.
- `tests/smoke-test.py` — optional local API regression check using Python's standard library.
- Core model and test files — ETT, AVL sequence tree, simulator, electrical screening, console app, and reference-forest test suite.
