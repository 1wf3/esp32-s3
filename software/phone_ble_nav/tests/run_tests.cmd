@echo off
call "E:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64
if errorlevel 1 exit /b 1
cd /d "%~dp0"
cl /nologo /std:c++17 /EHsc /utf-8 /W4 /I. /I..\src /FIgolden_config.h upstream_ble_protocol_tests.cpp ..\src\ble_protocol.cpp /Fe:upstream_protocol_tests.exe
if errorlevel 1 exit /b 1
upstream_protocol_tests.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /utf-8 /W4 /I..\src session_tests.cpp ..\src\ble_protocol.cpp /Fe:session_tests.exe
if errorlevel 1 exit /b 1
session_tests.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /utf-8 /W4 /Irenderer_preview renderer_preview\startup.cpp ..\src\ble_protocol.cpp /Fe:startup_tests.exe
if errorlevel 1 exit /b 1
startup_tests.exe --test
exit /b %errorlevel%
