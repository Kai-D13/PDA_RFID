# CLAUDE.md — RFID Tem Check (PDA_RFID)

App Android cho PDA kho Buymed: quét mã QR trên thùng/hộp → hiển thị loại tem RFID cần dán
(**tem thường** / **tem gập**) bằng cách tra dữ liệu local, hạn chế gọi API.

**Đọc `docs/HANDOFF.md` trước khi làm bất cứ việc gì** — đầy đủ bối cảnh, quyết định thiết kế,
những gì đã/chưa kiểm chứng và backlog. Tóm tắt API ở `docs/API.md`.

## Quy tắc bắt buộc

- **Repo PUBLIC.** Không commit: file khóa ký `signing/*.jks`, token API (`Basic ...`),
  file tài liệu API nội bộ dạng HTML (có chứa token production), email/thông tin cá nhân.
- **Java thuần, không AndroidX, không lambda / method reference / stream / `String.join`
  kiểu Java 8 API mới.** `build_apk.sh` dex bằng `dx` với `--min-sdk-version=21`, không hỗ trợ
  `invokedynamic`. Dùng anonymous class. Chỉ dùng API Android ≤ 21, hoặc bọc `Build.VERSION.SDK_INT`.
- Giao diện, thông báo cho người dùng: **tiếng Việt**. Comment trong code: tiếng Anh.
- Đổi phiên bản: tăng **cả hai** `versionCode/versionName` trong `app/build.gradle` và
  `VERSION_CODE/VERSION_NAME` trong `build_apk.sh` (đang bị lặp, xem backlog).
- Đổi dữ liệu đóng gói: `python3 tools/xlsx_to_tsv.py <file.xlsx> app/src/main/assets/products.dat`
  rồi sửa `builtin_data_date` trong `app/src/main/res/values/strings.xml`.
  Asset phải có đuôi `.dat`, **không phải `.gz`** (aapt tự giải nén và đổi tên file `.gz`).
- **Gradle wrapper giữ 8.14.3** (bản đã chạy được trên CI với AGP 8.7.3; AGP 8.7 chưa thử với
  Gradle 9, muốn lên Gradle 9 thì nâng AGP cùng lúc và chạy lại CI). Extension Java/Gradle
  của VS Code có thể tự nâng wrapper lên 9.x (đã xảy ra ngày 08/11/2026) — đừng commit thay đổi đó.
- Sau khi sửa logic: chạy test (lệnh ở dưới) và build APK lại; ghi rõ cái gì đã chạy thật,
  cái gì chưa (chưa có thiết bị/emulator nào chạy app này).

## Lệnh

```bash
# Build APK không cần Android SDK (Linux/WSL; apt: openjdk-17-jdk aapt zipalign dalvik-exchange apksigner)
./build_apk.sh                       # -> dist/rfid-tem-check-<version>.apk

# Build bằng Gradle (cần Android SDK 34 + JDK 17). Đã chạy được trên GitHub Actions
# (.github/workflows/build.yml, mỗi push/PR vào main); chưa thử trên Windows / Android Studio
./gradlew assembleRelease            # Windows: .\gradlew.bat assembleRelease

# Test logic (JUnit4): QrParser, ProductStore, TableImporter (CSV/XLSX), ApiClient.parse
./gradlew test -Prfid.xlsx=/path/rfid_client_san_pham.xlsx
```

Không có Gradle/SDK thì chạy test thủ công như mục "Test" trong `docs/HANDOFF.md`.
