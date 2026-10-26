#!/usr/bin/env python3
"""Local integration smoke test for DashboardServer; uses only Python's standard library."""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def request(base, path, method="GET", payload=None, content_type="application/json"):
    data = None
    headers = {}
    if payload is not None:
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = content_type
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            raw = response.read()
            typ = response.headers.get("Content-Type", "")
            if "application/json" in typ:
                return json.loads(raw.decode("utf-8"))
            return raw.decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise AssertionError(f"{method} {path} returned HTTP {exc.code}: {body}") from exc


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("[PASS]", message)


def main():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    java_sources = sorted(p.name for p in ROOT.glob("*.java"))
    compile_run = subprocess.run(["javac", *java_sources], cwd=ROOT, capture_output=True, text=True)
    if compile_run.returncode != 0:
        raise RuntimeError("javac failed before smoke test:\n" + compile_run.stdout + compile_run.stderr)
    proc = subprocess.Popen(
        ["java", "DashboardServer", str(port)], cwd=ROOT,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        last_error = None
        for _ in range(60):
            if proc.poll() is not None:
                output = proc.stdout.read() if proc.stdout else ""
                raise RuntimeError(f"DashboardServer exited early:\n{output}")
            try:
                scenarios = request(base, "/api/scenarios")
                break
            except Exception as exc:  # server may still be starting
                last_error = exc
                time.sleep(0.2)
        else:
            raise RuntimeError(f"DashboardServer did not start: {last_error}")

        check(len(scenarios) == 6, "six built-in scenarios are discoverable")
        index = request(base, "/")
        css = request(base, "/web/styles.css")
        js = request(base, "/web/app.js")
        check("Radial Network Restoration" in index and '/web/app.js' in index, "dashboard HTML and local asset references are served")
        check(".network-panel" in css and "@media" in css, "responsive local stylesheet is served")
        check("/api/operation" in js and "fetch(" in js and "https://" not in js, "UI calls the local API without an external URL dependency")

        s = request(base, "/api/state")
        check(s["scenarioId"] == "sample-network" and len(s["buses"]) == 3, "sample radial network loads on startup")
        s = request(base, "/api/operation", "POST", {"operation": "OPEN", "lineId": "1"})
        check(s["lastAction"]["accepted"] and s["summary"]["unservedPKw"] == 500 and s["summary"]["unservedCustomers"] == 130,
              "OPEN 1 reports 500 kW and 130 customers disconnected")
        s = request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "3"})
        check(s["lastAction"]["accepted"] and s["summary"]["unservedPKw"] == 0, "CLOSE 3 restores service")
        s = request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "1"})
        check(not s["lastAction"]["accepted"] and "cycle" in s["lastAction"]["statusMessage"].lower(), "cycle-forming CLOSE is rejected without changing service")

        s = request(base, "/api/reset", "POST")
        check(s["summary"]["commands"] == 0 and s["summary"]["unservedPKw"] == 0, "Reset returns the scenario to initial state and clears history")

        request(base, "/api/load", "POST", {"scenarioId": "line-overload-screen"})
        s = request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "1"})
        violations = [v for c in s["snapshot"]["components"] for v in c["electricalReport"]["violations"]]
        check(any("LINE_OVERLOAD" in v for v in violations), "line-overload scenario reports the expected electrical warning")

        request(base, "/api/load", "POST", {"scenarioId": "undervoltage-screen"})
        request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "1"})
        s = request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "2"})
        violations = [v for c in s["snapshot"]["components"] for v in c["electricalReport"]["violations"]]
        check(any("UNDERVOLTAGE" in v for v in violations), "undervoltage scenario reports voltage warnings")

        request(base, "/api/load", "POST", {"scenarioId": "restoration-transfer"})
        s = request(base, "/api/operation", "POST", {"operation": "OPEN", "lineId": "2"})
        check(s["summary"]["unservedPKw"] == 200, "load-transfer scenario isolates 200 kW after OPEN 2")
        s = request(base, "/api/operation", "POST", {"operation": "CLOSE", "lineId": "3"})
        check(s["summary"]["unservedPKw"] == 0, "tie-line CLOSE restores the isolated load from the second source")

        request(base, "/api/load", "POST", {"scenarioId": "fault-isolation"})
        s = request(base, "/api/operation", "POST", {"operation": "FAULT", "lineId": "2"})
        check(s["lastAction"]["accepted"] and s["lines"][1]["faulted"] and s["summary"]["unservedPKw"] == 300,
              "FAULT on a closed line opens it and disconnects downstream demand")

        custom = ("BUSES 2\n1 0 0 0 1 12.47 5000 1.0\n2 100 50 10 0 12.47 0 1.0\n"
                  "LINES 1\n1 1 2 0.1 0.1 5000 0 0\n")
        s = request(base, "/api/load-raw", "POST", custom.encode(), "text/plain; charset=utf-8")
        check(s["custom"] and len(s["buses"]) == 2, "custom BUSES/LINES text file payload loads")
        s = request(base, "/api/verify", "POST")
        check(s["lastAction"]["accepted"] and "invariants hold" in s["lastAction"]["statusMessage"].lower(), "manual verification checks ETT invariants")
        csv = request(base, "/api/report.csv")
        check(csv.startswith("sequence,operation,accepted,message,") and "VERIFY" in csv, "CSV report includes operation history")
        print("All dashboard API smoke checks passed.")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=3)


if __name__ == "__main__":
    main()
