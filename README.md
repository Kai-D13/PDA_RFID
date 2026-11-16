# RFID Tem Check (Android / PDA)

Chọn kho Circa → quét mã QR Buymed → ra ngay loại tem RFID (**TEM THƯỜNG** / **TEM GẬP**) của sản
phẩm, tra trong dữ liệu local đóng gói sẵn trong app (72.394 sản phẩm từ
`loai_tem_rfid_global_san_pham.xlsx`, 15/11/2026), kèm tên, product ID, mã QR, lô/HSD, SKU,
**Tồn POS** (Circa POS, kho đang chọn) và **Tồn NW** (số thẻ EPC còn tồn trên Nextwaves). Chạm vào
kết quả hoặc một dòng lịch sử để xem **ảnh sản phẩm** và tồn theo vị trí / lô.

Khóa Circa (`ck_u_…`) không có trong APK: nạp vào PDA bằng QR cài đặt tạo từ `tools/config_qr.html`
(chạy offline) hoặc nhập ở màn Cài đặt. Khóa Nextwaves được nhúng lúc build từ file local
`BuiltinKeys.java` (không có trong repo); APK build từ repo này mà thiếu file đó thì nạp khóa NW bằng QR.

File cài: `dist/rfid-tem-check-<phiên bản>.apk` (1,9 MB, Android 5.0 trở lên, không cần Google Play).
Từ 1.2.0 APK **không còn trên GitHub** (có khóa Nextwaves): lấy file từ người build, chép qua USB.

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
4. **Hỏi Circa POS** (`/v1/pos/{kho}/qr/{mã}`, ~0,1–0,3 s, cần khóa API Circa + đã chọn kho) → ra
   product_id, SKU, lô, HSD → lưu cache → lần sau offline. Loại tem vẫn lấy từ dữ liệu local.
5. **Dự phòng: API check Buymed** (chỉ khi Circa không tra được và có token Buymed) → lưu cache.
   Khi dùng đường này, app hiển thị `rfidTagType` của API Buymed (vì cấu hình theo từng customer)
   và cảnh báo nếu dữ liệu local ghi khác.

**Tồn kho** luôn là số liệu sống nên được hỏi Circa sau khi đã hiện loại tem (không làm chậm việc
quét); quét liên tiếp nhiều thùng cùng sản phẩm trong 60 giây thì dùng lại kết quả vừa hỏi.

### Hướng xử lý triệt để (đề xuất cho backend)

* **Ưu tiên: in product_id vào QR**, ví dụ `https://thuocsi.vn/qr/1787548411337B731?p=1101363`.
  Tài liệu API ghi rõ *"Query strings and fragments are stripped"*, nên API check/generate vẫn nhận
  mã như cũ, còn app này đọc được `p=` và tra **100% offline**. App đã hỗ trợ sẵn `p=`, `pid=`,
  `productId=`, `product_id=`.
* Hoặc một endpoint nhẹ chỉ trả `code → productId` (không gọi sang Circa), cache được lâu dài.
* Hoặc xuất định kỳ bảng `mã QR → product_id` cho các PO đang nhập kho để nạp vào máy.

---

## 2. Cài lên PDA

**Cách 1 — copy file:** chép `rfid-tem-check-1.2.0.apk` vào PDA (USB / Drive / Zalo), mở file, cho
phép "Cài ứng dụng không rõ nguồn gốc" khi máy hỏi.

**Cách 2 — adb:**

```bash
adb install -r dist/rfid-tem-check-1.2.0.apk
```

Cập nhật bản mới: cài đè, giữ nguyên dữ liệu và cài đặt (vì cùng khóa ký `signing/rfidtag-release.jks`
— **giữ file này**, mất thì phải gỡ app cũ mới cài được bản mới).
File khóa ký **không có trong repo** (repo public): chép bản bạn đang giữ vào `signing/rfidtag-release.jks`
trước khi build, nếu không bản build mới sẽ không cài đè được lên bản cũ.

---

## 3. Cấu hình lần đầu

1. Nạp **khóa API Circa POS** (`ck_u_…`): quét QR cài đặt (dưới đây) hoặc mở ⚙ → *Khóa API Circa*.
2. App tự mở màn hình **Chọn kho** (danh sách kho trên Circa POS, tìm theo tên/mã POS, gõ không dấu
   được). Đổi kho bất cứ lúc nào: chạm dòng **Kho: …** ở đầu màn hình.
3. Quét.

| Mục (⚙ Cài đặt) | Mặc định | Ghi chú |
|---|---|---|
| Khóa API Circa | trống | chỉ gửi tới `api.v2.circa.vn` |
| Kho | chưa chọn | nút *Chọn kho* |
| Tra cứu online | bật | tắt = chỉ offline (không tra mã mới, không xem tồn kho) |
| API Buymed: base URL | `https://open-apis.buymed.com` | dự phòng; staging: `https://open-apis.stg.buymed.tech` |
| API Buymed: Authorization | trống | dán chuỗi `Basic ...`; để trống nếu chỉ dùng Circa |
| customerId | `32145` | dùng cho API Buymed |
| Suy ra theo lô in | tắt | xem mục 1 |

**Cấu hình nhiều máy bằng 1 mã QR:** mở `tools/config_qr.html` bằng trình duyệt trên máy tính (chạy
offline, không gửi khóa đi đâu), dán khóa Circa (và token Buymed nếu muốn dự phòng), bấm **Tạo mã
QR**, rồi quét mã trên màn hình bằng app trên từng PDA. Cũng có thể tự tạo QR có nội dung dạng:

```
RFIDCFG:{"circaKey":"ck_u_xxxx"}
RFIDCFG:{"circaKey":"ck_u_xxxx","baseUrl":"https://open-apis.buymed.com","token":"Basic xxxx","customerId":32145}
```

Mã này chứa khóa API, chỉ hiện/in ra khi cần và hủy sau khi dùng. Địa chỉ Circa cố định trong app,
QR chỉ đổi được khóa. QR đổi `baseUrl` Buymed mà không kèm `token` sẽ tự xóa token đang lưu, để một
mã QR lạ không lừa app gửi token sang server khác. Khóa `ck_u_…` là khóa cá nhân (gắn tài khoản
người tạo); nếu có tài khoản dịch vụ thì nên đổi sang.

### Màn hình quét

* Thẻ màu: **TEM THƯỜNG** (xanh lá) / **TEM GẬP** (cam) + *Dán tem trên: HỘP*.
* Khung trắng bên dưới: tên, product ID, lô, HSD, mã QR, **Tồn kho: 3.312 viên = 3 hộp** (đổi theo
  đơn vị dán tem), nguồn tra cứu, cảnh báo. Chạm vào để mở popup.
* Lịch sử (200 lần quét gần nhất, mất khi thoát app): chạm một dòng để mở lại popup của lần quét đó.
* Popup: ảnh sản phẩm (chạm để xem ảnh tiếp theo), quy cách, SKU, lô/HSD, đơn vị trên QR, đơn vị dán
  tem, thời điểm và kho lúc quét, tồn kho theo vị trí và lô (nút **LÀM MỚI**). Đang mở popup mà quét
  mã mới thì popup tự đóng và tra mã mới.

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
python3 tools/xlsx_to_tsv.py loai_tem_rfid_global_san_pham.xlsx products.tsv.gz
```

**Đổi dữ liệu đóng gói sẵn trong APK:** xuất ra `app/src/main/assets/products.dat`, sửa
`builtin_data_date` trong `app/src/main/res/values/strings.xml`, tăng `VERSION_CODE`/`versionCode`, build lại.

---

## 5. Build lại APK

**GitHub Actions (không cần cài gì):** mỗi lần push hoặc mở PR vào `main`, workflow
[`.github/workflows/build.yml`](.github/workflows/build.yml) chạy test và build APK. Muốn build tay
thì vào tab **Actions** → **Build APK** → **Run workflow**. Tải APK: mở lần chạy → mục **Artifacts**
→ `rfid-tem-check-<phiên bản>-<commit>-<release|debug>` (file zip chứa APK và `SHA256SUMS.txt`,
giữ 30 ngày).

* Đuôi `debug`: ký bằng khóa tạm của CI, **mỗi lần build một khóa khác**. Chỉ dùng để thử: phải gỡ
  bản đang cài trên máy rồi mới cài được (mất cache mã QR và cài đặt).
* Đuôi `release`: ký bằng khóa thật, cài đè được lên bản cũ. Muốn có bản này thì thêm secret ở
  *Settings → Secrets and variables → Actions*:
  * `RELEASE_KEYSTORE_BASE64`: nội dung file khóa dạng base64. Tạo trên Windows:
    `[Convert]::ToBase64String([IO.File]::ReadAllBytes("signing\rfidtag-release.jks")) | Set-Clipboard`
  * `RELEASE_KEYSTORE_PASSWORD`: chỉ cần nếu mật khẩu khác mặc định trong `app/build.gradle`.

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
  ScanDetailDialog.java   popup chi tiết một lần quét: ảnh, thông tin, tồn kho theo vị trí/lô
  StoreActivity.java      chọn kho Circa
  Resolver.java           luồng tra: product_id → cache mã → cache lô → Circa → Buymed; tồn kho
  CircaClient.java        Circa POS API: kho, mã QR → sản phẩm, tồn kho, chi tiết sản phẩm
  ImageLoader.java        tải ảnh sản phẩm (thu nhỏ, cache RAM)
  QrParser.java           tách mã QR / product_id từ nội dung quét
  ProductRepository.java  bảng product → tem trong RAM (asset hoặc file đã nhập)
  ProductStore.java       đọc/ghi định dạng TSV gzip
  TableImporter.java      nhập CSV/TSV/XLSX
  DataImporter.java       chọn định dạng file + nhập JSON
  ScanCache.java          SQLite: mã QR → product_id (+ SKU, lô, HSD), lô in → product_id
  ApiClient.java          POST /api/v1/circa/rfid-qr/check (Buymed, dự phòng)
  Settings*.java          cài đặt
  CameraScanActivity.java quét bằng camera (Camera1 + ZXing core)
app/src/main/assets/products.dat   dữ liệu đóng gói sẵn (gzip TSV)
tools/xlsx_to_tsv.py               chuyển Excel → dữ liệu app
tools/config_qr.html               tạo QR cài đặt (khóa Circa / token Buymed), chạy offline
build_apk.sh                       build APK không cần Android SDK
```

Viết bằng Java thuần, không dùng AndroidX, chỉ phụ thuộc `com.google.zxing:core`, nên chạy được
trên PDA đời cũ (Android 5+) và APK nhỏ.
