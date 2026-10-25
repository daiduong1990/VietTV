@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title VietTV - Cài đặt & Tối ưu Tivi Thông Minh Đa Hãng

cd /d "%~dp0"

where node >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ======================================================================
    echo [!] Máy tính chưa cài đặt Node.js ^(phiên bản 18 trở lên^).
    echo     Vui lòng tải và cài đặt miễn phí tại: https://nodejs.org/
    echo     Sau khi cài xong, hãy mở lại file CAI_DAT_NHANH.bat này!
    echo ======================================================================
    pause
    exit /b 1
)

:MENU
cls
echo ============================================================================
echo    VIETTV LAUNCHER ^& TRÌNH TỐI ƯU TIVI ĐA HÃNG ^(AN TOÀN - KHÔNG QUẢNG CÁO^)
echo    Hỗ trợ: Xiaomi, Sony, TCL, Casper, Coocaa, Skyworth, Google TV ^& TV Box
echo ============================================================================
echo.
echo   1. Tự tìm Tivi ^& Cài trọn bộ Giao diện + Bàn phím Telex + Tối ưu RAM
echo   2. Chỉ cài Giao diện ^& Bàn phím Tiếng Việt
echo   3. Cài thêm Gói Giọng Nói Tiếng Việt Offline ^(cho Tivi nội địa cắt Google^)
echo   4. Khôi phục Tivi về nguyên bản
echo   5. Mở lại cập nhật hệ thống OTA
echo   0. Thoát
echo.
set /p CHOICE="Nhập lựa chọn của bạn (0-5): "

if "%CHOICE%"=="1" (
    echo.
    node tools\viettv.js --full-setup
    echo.
    pause
    goto MENU
)
if "%CHOICE%"=="2" (
    echo.
    node tools\viettv.js --install
    echo.
    pause
    goto MENU
)
if "%CHOICE%"=="3" (
    echo.
    node tools\viettv.js --install-offline-voice
    echo.
    pause
    goto MENU
)
if "%CHOICE%"=="4" (
    echo.
    node tools\viettv.js --restore
    echo.
    pause
    goto MENU
)
if "%CHOICE%"=="5" (
    echo.
    node tools\viettv.js --enable-ota
    echo.
    pause
    goto MENU
)
if "%CHOICE%"=="0" (
    exit /b 0
)

echo.
echo [!] Lựa chọn không hợp lệ. Vui lòng nhập số từ 0 đến 5.
timeout /t 2 >nul
goto MENU
