@echo off
call "E:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /utf-8 /Isoftware\phone_ble_nav\tests\renderer_preview /Isoftware\phone_ble_nav\src software\phone_ble_nav\tests\renderer_preview\render.cpp software\phone_ble_nav\src\ble_protocol.cpp /Fe:build\pc_debug\render.exe /Fo:build\pc_debug\
exit /b %errorlevel%
