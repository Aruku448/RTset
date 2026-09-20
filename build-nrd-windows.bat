@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat" -arch=x64
if errorlevel 1 exit /b %errorlevel%
set "CMAKE=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
set "NINJA=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
"%CMAKE%" -S "C:\Users\Administrator\Documents\RT\native\nrd" -B "C:\Users\Administrator\Documents\RT\build\native\nrd-windows" -G Ninja -DNRD_SOURCE_DIR="C:\Users\Administrator\AppData\Local\Temp\nrd4173" -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="%NINJA%"
if errorlevel 1 exit /b %errorlevel%
"%CMAKE%" --build "C:\Users\Administrator\Documents\RT\build\native\nrd-windows" --parallel
exit /b %errorlevel%
