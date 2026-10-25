#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "======================================================================"
  echo "[!] Máy tính chưa cài đặt Node.js (phiên bản 18 trở lên)."
  echo "    Vui lòng tải và cài đặt miễn phí tại: https://nodejs.org/"
  echo "======================================================================"
  exit 1
fi

while true; do
  echo ""
  echo "============================================================================"
  echo "   VIETTV LAUNCHER & TRÌNH TỐI ƯU TIVI ĐA HÃNG (AN TOÀN - KHÔNG QUẢNG CÁO)"
  echo "   Hỗ trợ: Xiaomi, Sony, TCL, Casper, Coocaa, Skyworth, Google TV & TV Box"
  echo "============================================================================"
  echo ""
  echo "  1. Tự tìm Tivi & Cài trọn bộ Giao diện + Bàn phím Telex + Tối ưu RAM"
  echo "  2. Chỉ cài Giao diện & Bàn phím Tiếng Việt"
  echo "  3. Cài thêm Gói Giọng Nói Tiếng Việt Offline (cho Tivi nội địa cắt Google)"
  echo "  4. Khôi phục Tivi về nguyên bản"
  echo "  5. Mở lại cập nhật hệ thống OTA"
  echo "  0. Thoát"
  echo ""
  read -r -p "Nhập lựa chọn của bạn (0-5): " CHOICE

  case "$CHOICE" in
    1)
      node tools/viettv.js --full-setup
      read -r -p "Nhấn Enter để quay lại menu..." _
      ;;
    2)
      node tools/viettv.js --install
      read -r -p "Nhấn Enter để quay lại menu..." _
      ;;
    3)
      node tools/viettv.js --install-offline-voice
      read -r -p "Nhấn Enter để quay lại menu..." _
      ;;
    4)
      node tools/viettv.js --restore
      read -r -p "Nhấn Enter để quay lại menu..." _
      ;;
    5)
      node tools/viettv.js --enable-ota
      read -r -p "Nhấn Enter để quay lại menu..." _
      ;;
    0)
      exit 0
      ;;
    *)
      echo "[!] Lựa chọn không hợp lệ. Vui lòng nhập số từ 0 đến 5."
      ;;
  esac
done
