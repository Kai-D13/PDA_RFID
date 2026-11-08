# HANDOFF — RFID Tem Check (PDA_RFID)

Tài liệu bàn giao cho agent/dev tiếp nhận. Viết ngày 08/11/2026, ở commit đầu tiên của repo
(`39ee828`, v1.0.0) cộng các file tài liệu này. Đọc hết trước khi sửa code.

---

## 0. TL;DR

- **Mục tiêu:** trên PDA kho, quét QR Buymed trên thùng/hộp → biết ngay dán **tem thường** hay
  **tem gập**, không phải chờ API chậm.
- **Trạng thái:** v1.0.0 đã build thành APK đã ký (`dist/rfid-tem-check-1.0.0.apk`), 11 test logic
  đều đạt. **Chưa chạy trên thiết bị thật hay emulator nào**, **chưa build bằng Gradle**,
  **chưa gọi API thật** (máy build cũ không ra được mạng tới host API).
- **Ràng buộc lớn nhất:** mã QR hiện tại **không chứa product_id**, nên lần đầu gặp một mã vẫn phải
  hỏi API một lần (xem mục 3). Cách triệt để cần backend in `?p=<productId>` vào QR.
- **Việc tiếp theo ưu tiên:** người dùng cài APK và thử trên PDA thật → sửa lỗi phát sinh; dựng đường
  build trên máy người dùng (Windows) hoặc CI; chốt với đội QR về `?p=` và giả định "lô in".

---

## 1. Bối cảnh nghiệp vụ

- Buymed (thuocsi.vn) dán tem RFID cho sản phẩm theo cấu hình của **Circa** (đối tác RFID). Mỗi sản
  phẩm có loại tem `temthuong` (Tem thường) hoặc `temgap` (Tem gập), và đơn vị được dán tem
  (`rfid_unit_name`, ví dụ `hộp`, `chai`).
- Mã QR trên thùng/hộp là mã QR Buymed, ví dụ `thuocsi.vn/qr/1787548411337B731` hoặc
  `https://qr.v2.circa.vn/item/1757390400000MC011`.
- Hiện có API `POST /api/v1/circa/rfid-qr/check` trả đủ thông tin (gồm `rfidTagType`), nhưng người
  dùng thấy **chậm và ảnh hưởng tiến độ + hạ tầng** → muốn tra **local**. Chi tiết API: `docs/API.md`.
- Người dùng nhận một file Excel cấu hình tem (`rfid_client_san_pham_1.xlsx`, sheet
  `client_rfid_unit`) làm "database local".
- Thiết bị: **PDA Android** (chưa rõ hãng/model — nên hỏi người dùng), cài bằng file APK, không qua
  Google Play.
- Người dùng: Windows, VS Code, repo clone ở `C:\tags_RFID`, giao tiếp tiếng Việt.

## 2. Hiện trạng (v1.0.0)

| Hạng mục | Trạng thái |
|---|---|
| APK đã ký, `dist/rfid-tem-check-1.0.0.apk` (1,9 MB, SHA-256 `5e9b474b…a5e4`) | ✅ build bằng `build_apk.sh` |
| `aapt dump badging`: package `com.buymed.rfidtag`, minSdk 21, targetSdk 34, quyền INTERNET/CAMERA/VIBRATE | ✅ |
| Test JUnit (11 test), gồm đọc file Excel thật 72.225 dòng | ✅ chạy tay bằng `javac`/JUnitCore |
| Chạy app trên PDA / emulator | ❌ chưa |
| `./gradlew assembleRelease` / `./gradlew test` | ❌ chưa chạy (môi trường cũ chặn Google Maven) |
| Gọi API thật từ app | ❌ chưa (host API bị chặn ở máy build) |
| Broadcast của từng hãng PDA | ❌ chưa thử; vài action là phỏng đoán (mục 6) |
| Camera scan | ❌ chưa thử trên phần cứng |
| Nhập `.xlsx` 19 MB trên PDA (tốc độ, RAM) | ❌ chưa đo |

Khóa ký `signing/rfidtag-release.jks` **không có trong repo** (repo public). Người dùng giữ bản
duy nhất; phải chép vào `signing/` trước khi build release, nếu không APK mới sẽ không cài đè được
lên bản đã cài. Alias `rfidtag`, mật khẩu đang để mặc định trong `app/build.gradle` và `build_apk.sh`
(xem backlog B5).

## 3. Vấn đề cốt lõi: QR không chứa product_id

`1787548411337B731` = `1787548411337` (epoch ms lúc sinh mã, 13 chữ số) + `B7` (machineCode) +
`31` (số thứ tự). Không có product_id → không thể "parse QR rồi tra local" cho mã chưa gặp.

Luồng tra trong `Resolver.resolve()`, cách rẻ chạy trước:

1. **product_id trong nội dung quét** → tra local. Nhận: số 1–10 chữ số (gõ tay), tham số
   `p=`, `pid=`, `productId=`, `product_id=` trong URL, hoặc JSON `{"productId": …}`.
2. **Cache mã QR** (SQLite `qr_cache`): mã đã từng tra API → product_id → tra local.
3. **Suy ra theo lô in** (SQLite `batch_cache`, **mặc định TẮT**): khóa = 13 số đầu + machineCode
   (lấy từ response API). Giả định: mã sinh trong cùng một lần gọi generate thuộc cùng một sản phẩm.
   Chỉ dựa trên ví dụ trong tài liệu API — **chưa được đội QR xác nhận**. Nếu một khóa khớp nhiều
   product khác nhau thì bỏ qua (coi như không biết).
4. **Gọi API check** (nếu bật online và có token) → lưu `qr_cache` + `batch_cache`.

Hiển thị:
- Đường 1–3: loại tem lấy từ **dữ liệu local**.
- Đường 4: hiển thị `rfidTagType` **của API** (vì Circa cấu hình theo từng customer), và cảnh báo nếu
  local khác hoặc local không có sản phẩm. Lý do: API là nguồn đúng tại thời điểm đó; cảnh báo giúp
  phát hiện file dữ liệu cũ.

Hướng triệt để (đã đề xuất cho người dùng, chưa ai làm):
- **In `?p=<productId>` vào QR** khi sinh mã. Tài liệu API nói query string bị bỏ qua, nên API cũ vẫn
  chạy; app đã hỗ trợ sẵn `p=` → 100% offline.
- Hoặc endpoint nhẹ `code → productId` không gọi sang Circa.
- Hoặc xuất định kỳ bảng `mã QR → productId` theo PO đang nhập để nạp vào máy.

## 4. Dữ liệu

- Nguồn: `rfid_client_san_pham_1.xlsx` (người dùng upload ngày 05/11/2026; **không có trong repo**),
  sheet `client_rfid_unit`, cột: `product_id, product_name, type_tag, type_tag_label, rfid_unit_name`.
  File Excel không có `sharedStrings.xml` (dùng chuỗi inline).
- 72.225 sản phẩm, không trùng ID, ID lớn nhất 999903777:
  `temthuong` 42.721 · `temgap` 18.888 · chưa cấu hình (`type_tag` rỗng, nhãn "Chưa cấu hình") 10.616.
- `rfid_unit_name` phổ biến: hộp 43.113, (trống) 10.618, chai 8.872, tuýp 2.301, gói 2.178, cái 1.775,
  hũ 789, lốc 467, túi 412, vỉ 333…
- Kiểm tra chéo với API: product 1101363 → local `temthuong`/`hộp`, API cũng `temthuong`/`hộp`.
- Định dạng nội bộ của app: TSV nén gzip, mỗi dòng
  `product_id \t type_tag \t rfid_unit_name \t product_name` (type rỗng = chưa cấu hình).
  - Đóng gói trong APK: `app/src/main/assets/products.dat` (gzip; **phải** đuôi `.dat`, vì aapt tự
    giải nén và bỏ đuôi file `.gz` → app không mở được; `build_apk.sh` dùng `-0 dat`, Gradle dùng
    `noCompress 'dat'`).
  - Dữ liệu nhập trên máy: `filesDir/products.tsv.gz`, ưu tiên hơn asset. "Dữ liệu gốc" trong Cài đặt
    xóa file này.
- Tạo/cập nhật: `tools/xlsx_to_tsv.py` (openpyxl). Nhập trên máy: Cài đặt → Nhập dữ liệu, nhận
  `.xlsx`, `.tsv.gz`, `.csv`/`.tsv` (`,` `;` tab, có hoặc không header), `.json` (mảng object hoặc
  object chứa mảng). Định dạng nhận theo nội dung (magic bytes), không theo tên file. Cột nhận theo tên
  (không phân biệt hoa thường, bỏ ký tự không phải chữ/số), có alias: `productId`, `rfidTagType`,
  `rfidUnit`, `productName`…; thiếu `type_tag` thì suy từ `type_tag_label`.

## 5. Kiến trúc mã

Java thuần, framework Android, không AndroidX. Phụ thuộc duy nhất: `com.google.zxing:core:3.3.3`.
~2.400 dòng.

| File | Vai trò |
|---|---|
| `RfidApp` | Application; giữ singleton `ProductRepository`, `ScanCache`, `Settings`, `Resolver`; nạp dữ liệu nền khi khởi động |
| `MainActivity` | Màn hình quét. Nhận input qua keyboard wedge (Enter/Tab/`\n`), broadcast, camera. Bỏ trùng cùng chuỗi trong 800 ms. Thẻ kết quả lớn có màu, lịch sử 200 dòng gần nhất (chỉ trong RAM), bộ đếm |
| `Resolver` | Luồng tra (mục 3). Chạy trên executor đơn luồng |
| `QrParser` | Làm sạch input (ký tự điều khiển, BOM, zero-width, tiền tố AIM `]Q1`), phân loại EMPTY / PRODUCT_ID / QR_CODE / CONFIG; tách timestamp/machine |
| `ProductRepository` | `HashMap<Long, Product>` trong RAM (ước tính ~15 MB, chưa đo), `CountDownLatch` báo đã nạp xong; thay dữ liệu bằng ghi file tạm rồi rename |
| `ProductStore` | Đọc/ghi TSV gzip |
| `TableImporter` | CSV/TSV (RFC 4180, field nhiều dòng) và XLSX (ZipFile + XmlPullParser, sharedStrings/inline/rich text, tìm sheet đầu qua workbook rels). Không phụ thuộc Android ngoài interface XmlPullParser → test được trên JVM |
| `DataImporter` | Copy URI vào cache, nhận dạng định dạng, JSON qua `android.util.JsonReader` |
| `ScanCache` | SQLite `scan_cache.db`: `qr_cache(code PK, product_id, created)`, `batch_cache(ts, machine, product_id, created, PK(ts,machine))` |
| `ApiClient` | `HttpURLConnection` POST check, timeout kết nối 6 s / đọc 12 s, parse bằng `org.json` |
| `Settings` | SharedPreferences `settings`; áp dụng QR cấu hình `RFIDCFG:` |
| `SettingsActivity` | Form cài đặt; nhập dữ liệu (SAF `ACTION_OPEN_DOCUMENT`), khôi phục dữ liệu gốc, xóa cache; việc nặng chạy nền sau dialog chặn |
| `CameraScanActivity` | Camera1 + ZXing core (QR, DataMatrix, Code128, EAN-13), cắt giữa khung rồi thử lại cả khung, đèn flash, tự xin quyền camera |
| `Feedback` | Âm: thường 1 bíp, gập 2 bíp, lỗi âm dài + rung |
| `LookupResult`, `HistoryAdapter`, `Product`, `DateFmt` | Model/hiển thị |

Màu: tem thường `#1B7F3B` xanh lá, tem gập `#D9730D` cam, loại khác/cài đặt `#1A5E7A`, chưa cấu hình
`#5F6B73` xám, lỗi/không có dữ liệu `#B3261E` đỏ.

### Cài đặt (SharedPreferences `settings`)

| Khóa | Mặc định | |
|---|---|---|
| `base_url` | `https://open-apis.buymed.com` | |
| `token` | rỗng | thêm `Basic ` nếu không có dấu cách |
| `customer_id` | 32145 | |
| `online` | true | gọi API khi tra offline không ra |
| `batch_inference` | false | mục 3 bước 3 |
| `scan_action`, `scan_extra` | rỗng | broadcast tùy chỉnh |
| `sound`, `vibrate` | true | |

QR cấu hình: `RFIDCFG:{"baseUrl":…,"token":…,"customerId":…,"online":…,"batchInference":…,"scanAction":…,"scanExtra":…}`.
An toàn: `baseUrl` phải `https://`; đổi `baseUrl` mà QR không kèm `token` → xóa token đang lưu (để QR
lạ không làm app gửi token sang server khác).

## 6. Máy quét PDA

- Keyboard wedge: `EditText` tắt bàn phím ảo (`setShowSoftInputOnFocus(false)`), xử lý Enter/Tab qua
  `OnKeyListener` + `OnEditorActionListener`, và ký tự `\n`/`\r` qua `TextWatcher`. Nút `ABC` bật bàn
  phím để gõ tay.
- Broadcast — luôn đăng ký các action sau (`RECEIVER_EXPORTED` từ API 33):
  `com.buymed.rfidtag.SCAN` (của app, dùng cho Zebra DataWedge…), `android.intent.ACTION_DECODE_DATA`
  (Urovo), `com.android.server.scannerservice.broadcast` (iData), `nlscan.action.SCANNER_RESULT`
  (Newland), `scan.rcv.message` (Urovo cũ/Seuic), `com.scanner.broadcast` (**phỏng đoán**),
  `com.honeywell.decode.intent.action.EDIT_DATA` (**phỏng đoán**, Honeywell thường phải tự cấu hình
  action). Extra thử lần lượt: tùy chỉnh, `barcode_string`, `scannerdata`, `SCAN_BARCODE1`, `data`,
  `value`, `barcode`, `com.symbol.datawedge.data_string`, `barcodeData`, `decode_rslt`, `scanResult`,
  `barocode` (byte[]).
- Khi biết model PDA, nên kiểm lại action/extra đúng của hãng và thêm vào `SCAN_ACTIONS`.

## 7. Build

### a) `build_apk.sh` (đã dùng cho v1.0.0)

Linux/WSL, không cần Android SDK. Gói apt: `openjdk-17-jdk aapt zipalign dalvik-exchange apksigner`
(aapt bản Android 14, dx 1.16). `android.jar` API 34 lấy từ `$ANDROID_JAR`, `$ANDROID_HOME`, hoặc tải
từ `raw.githubusercontent.com/Sable/android-platforms`; zxing tải từ Maven Central. Cache ở
`.build-cache/`. Các bước: chèn `package=` vào manifest (aapt1 cần) → `aapt package` (`-0 dat`) →
`javac -source 8 -target 8 -bootclasspath android.jar` → `dalvik-exchange --dex --min-sdk-version=21` →
`aapt add classes.dex` → `zipalign -p 4` → `apksigner` (v1+v2). Nếu thiếu keystore, script **tự tạo
khóa mới** — APK đó sẽ không cài đè được bản cũ.

Hệ quả của toolchain này: **không dùng lambda, method reference, stream**, chỉ Java 8 cú pháp cũ;
không AndroidX/AAR (aapt1 không merge được resource/manifest của AAR).

### b) Gradle / Android Studio (chưa kiểm chứng)

AGP 8.7.3, Gradle wrapper 8.14.3, compileSdk/targetSdk 34, minSdk 21, Java 1.8. Cần JDK 17+ và
Android SDK 34. Ký release bằng `signing/rfidtag-release.jks` nếu có, không có thì dùng khóa debug.
Trên Windows: cài Android Studio → mở `C:\tags_RFID` → để Studio tạo `local.properties`/tải SDK →
Build → Generate Signed/Build APK, hoặc `.\gradlew.bat assembleRelease`. Việc đầu tiên nên làm là
chạy thử và sửa lỗi build nếu có.

### c) Ghi chú môi trường cloud cũ (chỉ khi agent chạy trên container Claude Code cloud)

`dl.google.com` bị chặn (`maven.google.com` redirect về đó) → không tải được AGP/SDK. Maven Central
đôi khi trả 429, đợi vài giây thử lại, hoặc dùng `repo1.maven.org` thay `repo.maven.apache.org`.
`raw.githubusercontent.com` dùng được. Host API Buymed bị chặn.

## 8. Test

`app/src/test/java/com/buymed/rfidtag/LogicTest.java` — 11 test JUnit4: các dạng QR trong tài liệu,
làm sạch input, product id trực tiếp/`p=`, QR cấu hình, tách lô, round-trip TSV, CSV có ngoặc kép/alias/
trùng/ID lỗi, CSV `;` và TSV không header, chỉ số cột Excel, parse response API OK/INVALID, và đọc
file Excel thật (chỉ chạy khi có `-Prfid.xlsx=…`, kiểm cả XmlPullParser bật và tắt namespace).

Chạy tay không cần Gradle (đã dùng):

```bash
C=.build-cache  # cần: junit-4.13.2, hamcrest-core-1.3, kxml2-2.3.0, json-20240303, android-34.jar
CP="$C/junit-4.13.2.jar:$C/hamcrest-core-1.3.jar:$C/kxml2-2.3.0.jar:$C/json-20240303.jar"
mkdir -p build/test
javac -encoding UTF-8 -d build/test -cp "$CP:$C/android-34.jar" \
  app/src/main/java/com/buymed/rfidtag/{Product,QrParser,ProductStore,TableImporter,ApiClient,Settings}.java \
  app/src/test/java/com/buymed/rfidtag/LogicTest.java
java -Drfid.xlsx=/path/file.xlsx -cp "build/test:$CP" org.junit.runner.JUnitCore com.buymed.rfidtag.LogicTest
```

Chưa có test cho: `Resolver` (phụ thuộc `ScanCache`/`Settings` của Android), `DataImporter.readJson`,
toàn bộ UI.

## 9. Quyết định thiết kế và lý do

| Quyết định | Lý do |
|---|---|
| Java thuần, không AndroidX, không lambda | Build được bằng toolchain apt khi không có SDK; APK nhỏ; chạy trên PDA Android 5+ |
| minSdk 21, targetSdk 34 | PDA đời cũ; Android 14 chặn cài app targetSdk < 23 |
| Camera1 + ZXing core thay vì zxing-android-embedded/ML Kit | Không cần AAR/Google Play Services |
| Dữ liệu trong RAM (HashMap) thay vì SQLite | 72k dòng, tra O(1), code đơn giản; thời gian nạp trên PDA chưa đo |
| Asset `.dat` | aapt giải nén và đổi tên `.gz` |
| Khi gọi API thì hiển thị tem của API | API tính theo customer; local có thể cũ → có cảnh báo lệch |
| Suy theo lô mặc định tắt | Giả định chưa xác nhận; dán sai tem là lỗi vận hành |
| Token không đóng gói trong APK | APK chia sẻ giữa nhiều máy; repo public |
| Khóa ký ngoài repo | Repo public |
| Người dùng chọn đưa `products.dat` (dữ liệu sản phẩm nội bộ) lên repo public | Người dùng xác nhận chấp nhận ngày 05/11/2026 |

## 10. Rủi ro / lỗi đã biết

- Toàn bộ phần chạy trên Android chưa thử (mục 2). Rủi ro cao nhất: hành vi keyboard wedge của từng
  model (gửi Enter hai lần, gửi theo ký tự…), broadcast sai action, camera xoay/khung không khớp.
- `MainActivity` khóa executor đơn luồng trong lúc chờ nạp dữ liệu (`waitForData`): lần quét đầu tiên
  đợi đến khi nạp xong. Có thể chấp nhận được, nhưng cần đo trên PDA.
- Lịch sử và bộ đếm mất khi thoát app.
- Phiên bản ghi lặp ở `app/build.gradle` và `build_apk.sh`.
- Mật khẩu keystore mặc định ghi rõ trong code (chỉ an toàn chừng nào file `.jks` không lộ).
- API timeout cố định 6 s/12 s; chưa retry; chưa phân biệt lỗi 4xx/5xx trên UI ngoài `errorCode`.
- Không có cơ chế cập nhật dữ liệu tự động; phải nhập tay hoặc build lại APK.
- `HistoryAdapter` hiển thị "CHƯA CH" cho chưa cấu hình (viết tắt hơi khó hiểu).

## 11. Backlog đề xuất (theo thứ tự)

**P0 — cần người dùng**
- A1. Người dùng cài APK lên PDA, cấu hình token, thử: quét wedge/broadcast/camera, tra
  `1101363`, tra mã QR thật online rồi offline lần 2, nhập file dữ liệu. Thu thập: model PDA, phiên
  bản Android, ảnh màn hình lỗi.
- A2. Hỏi đội QR/backend: (a) in được `?p=<productId>` vào QR không; (b) giả định "cùng 13 số đầu +
  machineCode = cùng sản phẩm" đúng không; (c) có endpoint nhẹ `code → productId` không.

**P1 — dev**
- B1. Dựng đường build được kiểm chứng: Android Studio trên Windows, hoặc **GitHub Actions**
  (runner `ubuntu-latest` có sẵn Android SDK) chạy `./gradlew test assembleDebug` và đính kèm APK
  làm artifact. Repo public: không đưa keystore/token vào workflow; nếu cần ký release trên CI thì dùng
  GitHub Secrets (base64 keystore) và hỏi người dùng trước.
- B2. Sửa lỗi phát sinh từ A1.
- B3. Lưu lịch sử quét (SQLite) và xuất CSV (mã, product, tem, thời gian, nguồn) để đối soát.
- B4. Tìm sản phẩm theo tên (ô tìm kiếm) cho trường hợp tem/QR hỏng.
- B5. Gom phiên bản về một chỗ (ví dụ `version.properties` dùng chung cho Gradle và script); chuyển
  mật khẩu keystore sang biến môi trường/`keystore.properties` (gitignored).
- B6. Unit test cho `Resolver` (tách interface cho cache/settings/api).

**P2**
- C1. Tự cập nhật dữ liệu từ URL (cần nơi host file nội bộ; hỏi người dùng).
- C2. Hiển thị thêm `countQr`/số tem khi đã gọi API (API có sẵn).
- C3. Chế độ "chỉ offline" rõ ràng, thống kê tỷ lệ cache hit / số lần gọi API.
- C4. Nếu A2(a) được làm: bỏ hẳn gọi API mặc định, chỉ dùng khi QR không có `p=`.

## 12. Quy ước làm việc

- Đọc `CLAUDE.md` (quy tắc bắt buộc). Không commit secret; repo public.
- Mỗi thay đổi hành vi: thêm/sửa test nếu là logic thuần; build APK lại; ghi rõ đã kiểm chứng gì.
- Tăng `versionCode` mỗi lần phát hành APK mới; đặt tên APK theo `versionName`.
- Commit/push chỉ khi người dùng yêu cầu. Nhánh mặc định `main`.
- Người dùng dùng tiếng Việt; trả lời và viết UI bằng tiếng Việt.
