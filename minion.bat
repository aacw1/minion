@echo off
rem minion launcher (GUI requires JDK 8 with JavaFX - jfxrt.jar)
rem Detection order: MINION_JAVA explicit -> JAVA_HOME -> common JDK 8 install dirs
rem Falls back with a clear error instead of NoClassDefFoundError: javafx/application/Application
setlocal

rem 1. Explicit path wins (env var MINION_JAVA = full path to java.exe)
if defined MINION_JAVA (
    if exist "%MINION_JAVA%" goto :run
)

rem 2. JAVA_HOME probe (jre\lib\ext\jfxrt.jar present means JavaFX included)
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\jre\lib\ext\jfxrt.jar" (
        set "MINION_JAVA=%JAVA_HOME%\bin\java.exe"
        goto :run
    )
)

rem 3. Common JDK 8 install locations (Zulu 8 FX / Oracle JDK 8)
for %%D in (
    "D:\javame\jdk1.8"
    "%LOCALAPPDATA%\Programs\Zulu\zulu-8"
    "C:\Program Files\Zulu\zulu-8"
    "C:\Program Files\Java\jdk1.8"
    "C:\Program Files (x86)\Java\jdk1.8"
) do (
    if exist "%%~D\jre\lib\ext\jfxrt.jar" (
        set "MINION_JAVA=%%~D\bin\java.exe"
        goto :run
    )
)

echo [ERROR] No JDK 8 with JavaFX found (GUI requires it).
echo Install JDK 8 (Oracle JDK 8 or Zulu 8 FX), then either:
echo   - Point env var JAVA_HOME to that JDK and retry
echo   - Set MINION_JAVA to the full path of its java.exe (e.g. set MINION_JAVA=D:\javame\jdk1.8\bin\java.exe)
exit /b 1

:run
"%MINION_JAVA%" -jar "%~dp0target\minion-0.1.0.jar" %*
endlocal
