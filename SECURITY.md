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

## 🔒 5 Lớp Phòng Thủ Chiều Sâu (5 Defense-in-Depth Layers)

1. **Zero Shared Keys (Không khóa API dùng chung)**:
   - Tuyệt đối không nhúng bất kỳ khóa API đám mây dùng chung nào trong mã nguồn hay APK. Hệ thống nhận diện giọng nói ưu tiên Google Speech sẵn có trên Tivi hoặc gói **K2-FSA Sherpa-ONNX Offline** miễn phí chạy 100% trên CPU Tivi; người dùng nâng cao có thể tùy chọn nhập khóa cá nhân cục bộ của riêng mình.
2. **Signature-Protected `INTERNAL_IPC` Keystroke Receiver (Khóa chữ ký số IPC bàn phím)**:
   - Giao tiếp nội bộ giữa trình nhận diện giọng nói và bàn phím Tiếng Việt (`VnTvImeService`) được bảo vệ bằng quyền chữ ký riêng `vn.viettv.launcher.permission.INTERNAL_IPC` (`android:protectionLevel="signature"`) kết hợp cờ `Context.RECEIVER_NOT_EXPORTED`, chặn đứng mọi ứng dụng bên thứ ba giả mạo Intent bơm phím trái phép.
3. **`Binder.getCallingUid` Microphone Guard (Xác thực định danh tiến trình gọi Micro)**:
   - Dịch vụ nhận diện giọng nói (`VnRecognitionService`) đối chiếu trực tiếp `Binder.getCallingUid()` trước khi mở luồng thu âm (chỉ chấp nhận chính ứng dụng, `SYSTEM_UID`/`SHELL_UID` hoặc dịch vụ trợ lý/bàn phím mặc định đang được hệ thống ủy quyền), ngăn chặn ứng dụng lạ lạm dụng quyền thu âm ngầm.
4. **HTTPS + RFC1918-Only LAN Whisper Enforcement with Redirect Lock (Chống rò rỉ khóa & SSRF)**:
   - Khi người dùng cấu hình máy chủ STT cá nhân (`VoiceSttClient`), mọi điểm cuối Internet công cộng bắt buộc phải dùng `https://`. Giao thức `http://` chỉ được phép đối với địa chỉ IPv4 mạng nội bộ gia đình chuẩn RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) hoặc `localhost`, đồng thời khóa cứng `setInstanceFollowRedirects(false)` để ngăn tấn công chuyển hướng đánh cắp Authorization header.
5. **Strict Regex-Sanitized ADB Shell Commands (Khử độc tham số lệnh ADB Shell)**:
   - Bộ công cụ `tools/viettv.js` xác thực nghiêm ngặt mọi tên gói (`isSafePackageName`) và định danh dịch vụ/thành phần (`isSafeComponentId`) bằng biểu thức chính quy chặt chẽ trước khi thực thi `pm enable`, `pm disable-user`, `cmd package compile`, `ime enable`, `ime set`, `enabled_accessibility_services` và `voice_recognition_service` — loại bỏ hoàn toàn nguy cơ command injection ngay cả khi file `snapshot.json` bị chỉnh sửa độc hại.

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

VietTV enforces a strict **Zero-PII, Zero-Telemetry, Zero-Embedded-Secret** architecture backed by **5 defense-in-depth layers**:
1. **Zero Shared Keys**: No shared cloud API keys are ever bundled; voice search defaults to native TV speech services or 100% local CPU **K2-FSA Sherpa-ONNX Offline** Vietnamese ASR.
2. **Signature-Protected `INTERNAL_IPC` Keystroke Receiver**: Internal speech-to-IME broadcast intents require `android:protectionLevel="signature"` (`INTERNAL_IPC`) with `RECEIVER_NOT_EXPORTED`.
3. **`Binder.getCallingUid` Microphone Guard**: `VnRecognitionService` verifies caller UID provenance before opening microphone audio streams.
4. **HTTPS + RFC1918-Only LAN Whisper Enforcement with Redirect Lock**: `VoiceSttClient` enforces `https://` for all public endpoints, restricts `http://` strictly to RFC1918 private LAN / loopback hosts, and disables HTTP redirects (`setInstanceFollowRedirects(false)`).
5. **Strict Regex-Sanitized ADB Shell Commands**: `tools/viettv.js` enforces `isSafePackageName` and `isSafeComponentId` regex whitelists across all `adb shell` package/IME/accessibility/voice operations, backed by automated `npm test` repository-wide Zero-PII/Secret & JVM security checks.
- Please report security vulnerabilities privately via GitHub's **Security -> Report a vulnerability** tab.
