@echo off
chcp 65001 >nul
title 自动打印监控程序
cd /d "%~dp0"

echo ============================================
echo   自动打印监控程序
echo   监控目录: D:\AL  和  D:\CEM
echo   按 Ctrl+C 可退出程序
echo ============================================
echo.

java -jar AutoPrintMonitor.jar

echo.
echo 程序已退出。
pause
