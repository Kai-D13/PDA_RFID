# RFID Tem Check (Android / PDA)

Quét mã QR Buymed → ra ngay loại tem RFID (**TEM THƯỜNG** / **TEM GẬP**) của sản phẩm, tra trong
dữ liệu local đóng gói sẵn trong app (72.225 sản phẩm từ `rfid_client_san_pham.xlsx`).

File cài: `dist/rfid-tem-check-1.0.0.apk` (1,9 MB, Android 5.0 trở lên, không cần Google Play).

Tài liệu cho dev/agent: [`CLAUDE.md`](CLAUDE.md), [`docs/HANDOFF.md`](docs/HANDOFF.md), [`docs/API.md`](docs/API.md).

---

## 1. Lưu ý quan trọng: mã QR hiện tại KHÔNG chứa product_id

Mã `thuocsi.vn/qr/1787548411337B731` gồm:

| Phần | Giá trị | Ý nghĩa |
|---|---|---|
| `1787548411337` | 13 chữ số | thời điểm sinh mã (epoch ms) |
| `B7` | machineCode | máy in |
| `31` | số thứ tự | |

Không có product_id trong mã, nên **lần đầu gặp một mã thì phải hỏi server một lần** để biết đó là
sản phẩm nào. App giảm số lần gọi API theo thứ tự sau (cách nhanh nhất chạy trước):

1. **product_id có sẵn trong nội dung quét** (nhập tay `1101363`, hoặc QR dạng `...?p=1101363`) → tra local, ~1 ms.
2. **Mã đã quét trước đó** → lấy product_id từ cache SQLite trên máy → tra local.
3. **Suy ra theo lô in** (tùy chọn, mặc định TẮT): các mã sinh cùng một lần gọi có cùng 13 số đầu và
   cùng machineCode (ví dụ `1757563200000MC011/012/013` trong tài liệu API), nên biết một mã là suy ra
   được cả lô. Đây là giả định, cần xác nhận với đội QR trước khi bật.
4. **Gọi API check** (chỉ khi 3 cách trên đều không ra) → lưu cache → lần sau offline.

Khi phải gọi API, app hiển thị `rfidTagType` của API (vì Circa cấu hình theo từng customer) và cảnh
báo nếu dữ liệu local ghi khác, để bạn biết cần cập nhật file dữ liệu.

### Hướng xử lý triệt để (đề xuất cho backend)

* **Ưu tiên: in product_id vào QR**, ví dụ `https://thuocsi.vn/qr/1787548411337B731?p=1101363`.
  Tài liệu API ghi rõ *"Query strings and fragments are stripped"*, nên API check/generate vẫn nhận
  mã như cũ, còn app này đọc được `p=` và tra **100% offline**. App đã hỗ trợ sẵn `p=`, `pid=`,
  `productId=`, `product_id=`.
* Hoặc một endpoint nhẹ chỉ trả `code → productId` (không gọi sang Circa), cache được lâu dài.
* Hoặc xuất định kỳ bảng `mã QR → product_id` cho các PO đang nhập kho để nạp vào máy.

---

## 2. Cài lên PDA

**Cách 1 — copy file:** chép `rfid-tem-check-1.0.0.apk` vào PDA (USB / Drive / Zalo), mở file, cho
phép "Cài ứng dụng không rõ nguồn gốc" khi máy hỏi.

**Cách 2 — adb:**

```bash
adb install -r dist/rfid-tem-check-1.0.0.apk
```

Cập nhật bản mới: cài đè, giữ nguyên dữ liệu và cài đặt (vì cùng khóa ký `signing/rfidtag-release.jks`
— **giữ file này**, mất thì phải gỡ app cũ mới cài được bản mới).
File khóa ký **không có trong repo** (repo public): chép bản bạn đang giữ vào `signing/rfidtag-release.jks`
trước khi build, nếu không bản build mới sẽ không cài đè được lên bản cũ.

---

## 3. Cấu hình lần đầu

Mở ⚙ (Cài đặt):

| Mục | Mặc định | Ghi chú |
|---|---|---|
| Gọi API check khi không tra được offline | bật | tắt = chỉ offline |
| API base URL | `https://open-apis.buymed.com` | staging: `https://open-apis.stg.buymed.tech` |
| Authorization | trống | dán chuỗi `Basic ...` từ tài liệu API |
| customerId | `32145` | |
| Suy ra theo lô in | tắt | xem mục 1 |

**Cấu hình nhiều máy bằng 1 mã QR:** tạo QR có nội dung dạng sau (bằng bất kỳ công cụ tạo QR nào),
rồi quét bằng app trên từng PDA:

```
RFIDCFG:{"baseUrl":"https://open-apis.buymed.com","token":"Basic xxxx","customerId":32145}
```

Mã này chứa token, chỉ in ra khi cần và hủy sau khi dùng. QR đổi `baseUrl` mà không kèm `token`
sẽ tự xóa token đang lưu, để một mã QR lạ không lừa app gửi token sang server khác.

### Máy quét của PDA

* **Kiểu bàn phím (keyboard wedge):** mặc định trên đa số PDA. Cần cấu hình máy quét gửi
  **Enter** (hoặc Tab) sau mỗi mã.
* **Kiểu broadcast:** app tự nhận các action phổ biến của Urovo, iData, Newland, Chainway, Honeywell.
  Với Zebra DataWedge: tạo profile cho app, bật *Intent output*, action `com.buymed.rfidtag.SCAN`,
  delivery *Broadcast intent*. Máy khác: nhập action / tên extra vào Cài đặt.
* **Camera:** nút `CAM`, dùng cho máy không có đầu quét.
* Nút `ABC` bật bàn phím ảo để gõ product_id bằng tay.

Âm báo: tem thường = 1 bíp, tem gập = 2 bíp, lỗi/không có dữ liệu = âm dài + rung.

---

## 4. Cập nhật dữ liệu tem

**Không cần build lại app:** Cài đặt → *Nhập dữ liệu* → chọn file trên máy. Nhận được:

* `.xlsx` xuất từ hệ thống (cột `product_id`, `type_tag` hoặc `type_tag_label`, `rfid_unit_name`,
  `product_name`, không phân biệt thứ tự cột). File 19 MB có thể mất 1–2 phút trên PDA yếu.
* `.tsv.gz` tạo bằng script dưới đây (1,5 MB, nhập vài giây). Nên dùng cách này.
* `.csv` / `.tsv` / `.json` (mảng object, cùng tên trường).

```bash
pip install openpyxl
```

```bash
python3 tools/xlsx_to_tsv.py rfid_client_san_pham.xlsx products.tsv.gz
```

**Đổi dữ liệu đóng gói sẵn trong APK:** xuất ra `app/src/main/assets/products.dat`, sửa
`builtin_data_date` trong `app/src/main/res/values/strings.xml`, tăng `VERSION_CODE`/`versionCode`, build lại.

---

## 5. Build lại APK

**Android Studio / Gradle** (máy có mạng tới Google): mở thư mục này, hoặc

```bash
./gradlew assembleRelease
```

APK ra ở `app/build/outputs/apk/release/`.

**Không có Android SDK** (Ubuntu/Debian, chỉ cần mấy gói apt) — đây là cách đã dùng để build
`dist/rfid-tem-check-1.0.0.apk`:

```bash
sudo apt-get install -y openjdk-17-jdk aapt zipalign dalvik-exchange apksigner
```

```bash
./build_apk.sh
```

Test logic (parse QR, đọc Excel, đọc phản hồi API):

```bash
./gradlew test -Prfid.xlsx=/duong/dan/rfid_client_san_pham.xlsx
```

---

## 6. Cấu trúc mã

```
app/src/main/java/com/buymed/rfidtag/
  MainActivity.java       màn hình quét: nhận wedge / broadcast / camera, hiển thị, lịch sử
  Resolver.java           luồng tra: product_id → cache mã → cache lô → API
  QrParser.java           tách mã QR / product_id từ nội dung quét
  ProductRepository.java  bảng product → tem trong RAM (asset hoặc file đã nhập)
  ProductStore.java       đọc/ghi định dạng TSV gzip
  TableImporter.java      nhập CSV/TSV/XLSX
  DataImporter.java       chọn định dạng file + nhập JSON
  ScanCache.java          SQLite: mã QR → product_id, lô in → product_id
  ApiClient.java          POST /api/v1/circa/rfid-qr/check
  Settings*.java          cài đặt
  CameraScanActivity.java quét bằng camera (Camera1 + ZXing core)
app/src/main/assets/products.dat   dữ liệu đóng gói sẵn (gzip TSV)
tools/xlsx_to_tsv.py               chuyển Excel → dữ liệu app
build_apk.sh                       build APK không cần Android SDK
```

Viết bằng Java thuần, không dùng AndroidX, chỉ phụ thuộc `com.google.zxing:core`, nên chạy được
trên PDA đời cũ (Android 5+) và APK nhỏ.
