@echo off
chcp 65001 >null

echo 启动打印程序ing...
start "" "D:\自动打印程序byBaishaohu\启动自动打印.bat"

echo.
echo 启动 特检1158 程序ing...
start "" "C:\Users\Administrator\Desktop\checklog\特检.bat"

echo.
echo 启动完成
exit