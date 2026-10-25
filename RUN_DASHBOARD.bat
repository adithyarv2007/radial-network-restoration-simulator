@echo off
setlocal
cd /d "%~dp0"
echo Compiling the Java simulator and dashboard...
javac *.java
if errorlevel 1 (
  echo.
  echo Compilation failed. Read the errors above, then press any key to close.
  pause >nul
  exit /b 1
)
echo Starting local dashboard server on 127.0.0.1:8088...
start "Radial Dashboard Server" /min java DashboardServer 8088
timeout /t 2 /nobreak >nul
start "" "http://127.0.0.1:8088/"
echo.
echo Browser opened. Keep the minimized "Radial Dashboard Server" console running.
echo To stop the server, restore that console and press Ctrl+C.
echo If the page fails to connect, check that port 8088 is available.
pause
