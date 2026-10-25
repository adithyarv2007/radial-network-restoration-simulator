# Launching the browser dashboard (Windows 10/11)

## Requirements

- A JDK, version 17 or newer (`java -version` and `javac -version` should both work).
- A browser such as Edge, Chrome, or Firefox.
- No Node.js, npm, Python, Docker, cloud service, or internet connection is needed to run the app.

## Option A — double-click

Double-click `RUN_DASHBOARD.bat`. A separate Java console starts the local server and your browser opens the dashboard. Keep the server console open while using the dashboard. Stop it with `Ctrl+C` in that console.

## Option B — PowerShell

1. Extract the whole project ZIP.
2. Open PowerShell in the folder containing `DashboardServer.java`, `SwitchingSimulator.java`, and `web/`.
3. Compile and run:

```powershell
javac *.java
java DashboardServer 8088
```

4. Keep that terminal open and open this exact URL in your browser:

`http://127.0.0.1:8088/`

Do not open the `web/index.html` file directly, and do not open an unrelated local AI/model UI. The dashboard JavaScript calls the Java server API, so it must be served by `DashboardServer`.

If port 8088 is already occupied, run `java DashboardServer 8089` and open `http://127.0.0.1:8089/`.

## Features

- Interactive SVG topology diagram; click a line to select it.
- Execute `OPEN`, `CLOSE`, `FAULT`, and `CLEAR_FAULT` operations against the existing `SwitchingSimulator`.
- Display disconnected demand, customer count, components, line state, warnings, and operation history.
- Load built-in scenarios or a local `.txt` network in the project's `BUSES` / `LINES` format.
- Reset the selected network, recompute ETT invariants and the electrical screen, and export history as CSV.
- All browser assets are local. The dashboard does not request external web resources.

## Input format for a custom network

```text
BUSES 3
1 0 0 0 1 12.47 5000 1.0
2 200 100 50 0 12.47 0 1.0
3 300 150 80 0 12.47 0 1.0
LINES 3
1 1 2 0.1 0.1 5000 1 0
2 2 3 0.1 0.1 5000 1 0
3 1 3 0.1 0.1 5000 0 0
```

A bus line has `id P_kW Q_kVAr customers hasSubstation V_base_kV sourceCapacity_kVA sourceVoltage_pu`. A line record has `id busA busB R_ohm X_ohm Smax_kVA initiallyClosed initiallyFaulted`. Blank lines and `# comments` are ignored. A custom file needs the `BUSES` and `LINES` sections; a `COMMANDS` section is not needed.

## If it still will not open

- Confirm the server terminal prints `Radial Network Restoration Dashboard is running.`
- Confirm `DashboardServer.java` is in the current folder and `javac *.java` finishes without errors.
- Visit `http://127.0.0.1:8088/` rather than a different app tab or the raw HTML file.
- If the page says the Java engine is unavailable, the server is not running at the selected port; check the terminal and browser address.
- The app binds only to loopback and is intended for one local user/session. Do not expose it to a network.
