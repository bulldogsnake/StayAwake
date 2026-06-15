@echo off
REM Builds StayAwake.exe using the .NET Framework C# compiler (no installs needed).
set CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
"%CSC%" /target:winexe /optimize+ /out:"%~dp0StayAwake.exe" ^
  /reference:System.Windows.Forms.dll ^
  /reference:System.Drawing.dll ^
  "%~dp0StayAwake.cs"
if %ERRORLEVEL%==0 (
  echo.
  echo Build succeeded: %~dp0StayAwake.exe
) else (
  echo.
  echo Build FAILED with error %ERRORLEVEL%.
)
