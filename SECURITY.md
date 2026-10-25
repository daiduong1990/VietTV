# Chính sách Bảo mật & Quyền riêng tư (Security Policy)

VietTV (`vn.viettv.launcher`) giao tiếp trực tiếp với Tivi trong phòng khách qua giao thức ADB nội bộ và cài đặt trình khởi chạy chính (Home Launcher) kèm Bàn phím & Nhận diện giọng nói. Vì vậy, an toàn hệ thống và quyền riêng tư của gia đình người dùng là ưu tiên số 1.

---

## 🛡️ Cam kết Kiến trúc Bảo mật "3 KHÔNG"

1. **KHÔNG Quảng cáo · KHÔNG Thu thập dữ liệu (Zero Telemetry)**:
   - Mã nguồn `vn.viettv.launcher` và bộ công cụ `tools/viettv.js` hoàn toàn minh bạch, không chứa bất kỳ SDK quảng cáo, theo dõi hành vi hay gửi nhật ký ngầm nào.
   - Các kết nối mạng duy nhất của Launcher (khi bật bảng thông tin) là đọc dữ liệu công khai: Thời tiết Open-Meteo, Giá vàng PNJ/Webgia, và Giá xăng Petrolimex.
2. **KHÔNG Nhúng khóa API dùng chung (Zero Embedded Shared Keys)**:
   - Không nhúng bất kỳ API key cá nhân hay khóa đám mây dùng chung nào trong mã nguồn hoặc file APK.
   - Chế độ nhận diện giọng nói ưu tiên dịch vụ nhận diện sẵn có trên Tivi hoặc gói **K2-FSA Sherpa-ONNX Tiếng Việt Offline** chạy 100% trực tiếp trên CPU của Tivi (âm thanh không rời khỏi Tivi). Người dùng nâng cao muốn dùng dịch vụ STT đám mây riêng có thể tự cấu hình khóa cá nhân cục bộ trên thiết bị của mình.
3. **KHÔNG Xóa vĩnh viễn ứng dụng hệ thống (100% Reversible Safe Optimization)**:
   - Trình tối ưu `tools/viettv.js --optimize` **tuyệt đối không** dùng `pm uninstall` lên gói hệ thống và **không bao giờ** đụng vào danh sách bảo vệ cốt lõi (`UNIVERSAL_PROTECTED_PACKAGES`: SystemUI, Cài đặt, TV Tuner, HDMI/CEC, Bluetooth Remote Wakeup, Google Voice/Katniss, YouTube, SmartTube, Netflix, VTVgo...).
   - Trước khi đóng băng bất kỳ gói quảng cáo nào (`pm disable-user --user 0`), công cụ tự động lưu bản chụp trạng thái gốc vào `.backups/<id-thiet-bi>/snapshot.json` để khôi phục 100% về nguyên bản chỉ với 1 lệnh (`npm run restore`).

---

## 📩 Báo cáo Lỗ hổng Bảo mật (Reporting a Vulnerability)

Vui lòng **không** mở Issue công khai nếu bạn phát hiện lỗi bảo mật tiềm ẩn. Thay vào đó:

1. Sử dụng tính năng **Private Vulnerability Reporting** trên GitHub: mở tab **Security** của kho mã nguồn -> chọn **Report a vulnerability**.
2. Đính kèm mô tả thành phần bị ảnh hưởng (`launcher/`, `tools/viettv.js`, `scripts/build_apk.js`, hoặc danh sách `BRAND_PROFILES`), các bước tái hiện và tác động thực tế.

### Phạm vi ưu tiên xử lý
- Lỗi thực thi lệnh ngoài ý muốn (command injection / path traversal) trong CLI khi đọc tham số ADB hoặc phản hồi mạng nội bộ.
- Bất kỳ gói hệ thống nào trong `BRAND_PROFILES` gây ảnh hưởng đến khả năng khởi động, điều khiển từ xa BLE, HDMI-CEC hoặc không thể khôi phục bằng `--restore`.
- Rò rỉ thông tin định danh cá nhân (PII) hoặc khóa bí mật trong mã nguồn đóng góp (được kiểm tra tự động trên mọi Pull Request qua `npm test`).

> **Lưu ý an toàn mạng gia đình**: Tính năng *Gỡ lỗi qua mạng Wi-Fi (Wireless ADB)* cho phép máy tính trong cùng mạng nội bộ yêu cầu kết nối tới Tivi (có hộp thoại xác nhận trên màn hình Tivi). Nếu sử dụng mạng Wi-Fi công cộng hoặc dùng chung không tin cậy, hãy tắt *Gỡ lỗi USB/Mạng* trong Cài đặt Nhà phát triển sau khi đã cài đặt và tối ưu xong.

---

## English Summary

VietTV enforces a strict **Zero-PII, Zero-Telemetry, Zero-Embedded-Secret** architecture:
- `npm test` runs a deterministic repository-wide scanner blocking any API keys, personal IPs, MAC addresses, or legacy package namespaces on every commit and PR.
- Multi-brand TV optimization (`tools/viettv.js`) strictly protects core system/tuner/CEC/BLE wakeup packages (`UNIVERSAL_PROTECTED_PACKAGES`) and creates an automatic per-device `.backups/<device>/snapshot.json` snapshot prior to disabling any vendor adware so `--restore` always returns the TV to its exact pre-optimization state.
- Please report security vulnerabilities privately via GitHub's **Security -> Report a vulnerability** tab.
