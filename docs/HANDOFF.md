# HANDOFF — RFID Tem Check (PDA_RFID)

Tài liệu bàn giao cho agent/dev tiếp nhận. Viết ngày 08/11/2026, ở commit đầu tiên của repo
(`39ee828`, v1.0.0) cộng các file tài liệu này. Đọc hết trước khi sửa code.

---

## 0. TL;DR

- **v1.2.0 (17/11/2026, chưa thử trên PDA):** theo yêu cầu UI của người dùng: hàng quét chỉ còn ô
  nhập + ABC (bỏ TRA, CAM), thẻ loại tem nhỏ lại, khung sản phẩm xếp *ID·QR → Lô·HSD → SKU → Tồn POS
  → Tồn NW*, bỏ dòng "Nguồn" ở màn chính và popup. Thêm **"Tồn NW"** = số thẻ EPC còn tồn trên
  **Nextwaves** (gọi thẳng từ PDA; khóa `nwc_pk_…:nwc_sk_…` **nhúng lúc build** từ file gitignored
  `BuiltinKeys.java`, nên **APK từ 1.2.0 không đưa lên GitHub**). Đã đối chiếu NW thật: 8/8 SP trong
  bảng test của brief khớp. Chi tiết mục 2c, API ở `docs/API.md` mục C.
- **v1.1.1 (16/11/2026):** v1.1.0 **đã chạy trên PDA thật** (Urovo CT48, màn 480×800): chọn kho, tra
  QR qua Circa, tồn kho, popup + ảnh đều chạy. Sửa lỗi giao diện thấy trên máy: **lịch sử không hiện**
  (không còn chỗ trên màn nhỏ) → cả vùng dưới ô quét giờ cuộn được; popup tự làm mới tồn kho đã cũ.
  Bản 1.1.1 **chưa thử trên PDA**. Chi tiết mục 2b.
- **v1.1.0 (15/11/2026):** luồng mới *mở app → chọn kho Circa → quét*. Tra mã QR
  mới qua **Circa POS API** (`api.v2.circa.vn`, khóa `ck_u_…`), Buymed thành dự phòng; hiện **tồn
  kho** của kho đang chọn; chạm kết quả / dòng lịch sử → popup có **ảnh sản phẩm**, tồn theo vị trí
  và lô; chữ loại tem nhỏ lại (30sp), lịch sử chữ to hơn; dữ liệu mới 72.394 SP. Chi tiết mục 2a, API
  ở `docs/API.md` mục A.
- **Mục tiêu:** trên PDA kho, quét QR Buymed trên thùng/hộp → biết ngay dán **tem thường** hay
  **tem gập**, không phải chờ API chậm.
- **Trạng thái:** v1.0.0 đã build thành APK đã ký (`dist/rfid-tem-check-1.0.0.apk`), 11 test logic
  đều đạt. Từ 08/11/2026 có **GitHub Actions** chạy `./gradlew test assembleRelease` mỗi lần push
  (mục 7d). **Chưa chạy trên thiết bị thật hay emulator nào**, **chưa gọi API thật** (máy build cũ
  không ra được mạng tới host API).
- **Ràng buộc lớn nhất:** mã QR hiện tại **không chứa product_id**, nên lần đầu gặp một mã vẫn phải
  hỏi API một lần (xem mục 3). Cách triệt để cần backend in `?p=<productId>` vào QR.
- **Việc tiếp theo ưu tiên:** người dùng cài APK và thử trên PDA thật → sửa lỗi phát sinh; chốt với
  đội QR về `?p=` và giả định "lô in". CI đã ký bằng khóa release (mục 7d).

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
- Thiết bị: **PDA Urovo CT48** (Windows nhận tên `CT48` qua USB/MTP; màn 480×800 px, ~320×533 dp;
  phiên bản Android chưa ghi lại), cài bằng file APK, không qua Google Play. Máy không có `adb` trên
  laptop; cài bằng cách chép APK vào thư mục `Download` của PDA rồi mở bằng ứng dụng Tệp.
- Người dùng: Windows, VS Code, repo clone ở `C:\tags_RFID`, giao tiếp tiếng Việt.

## 2. Hiện trạng (v1.0.0)

| Hạng mục | Trạng thái |
|---|---|
| APK đã ký, `dist/rfid-tem-check-1.0.0.apk` (1,9 MB, SHA-256 `5e9b474b…a5e4`) | ✅ build bằng `build_apk.sh` |
| `aapt dump badging`: package `com.buymed.rfidtag`, minSdk 21, targetSdk 34, quyền INTERNET/CAMERA/VIBRATE | ✅ |
| Test JUnit (11 test), gồm đọc file Excel thật 72.225 dòng | ✅ chạy tay bằng `javac`/JUnitCore |
| Chạy app trên PDA thật (08/11/2026, v1.0.0, model chưa rõ) | ⚠️ cài được, mở được, nạp 72.225 SP, nhận được mã QR thật `1792815001086C1`. Gọi API lỗi `Unable to resolve host "open-apis.buymed.com"` vì **PDA không có mạng** (từ PC host vẫn phân giải bình thường, API trả 401 khi không có token). Phát hiện: nhãn ONLINE chỉ phản ánh cài đặt; dòng "Nguồn" ghi sai bước local khi API lỗi; máy đang bật "Suy ra theo lô in" → sửa ở v1.0.1 |
| `./gradlew test assembleRelease` | ✅ trên GitHub Actions (run `42494930921`, commit `61a8594`: Gradle 8.14.3, JDK 17, ubuntu-26.04, 55 s). Chưa thử trên Windows / Android Studio |
| APK từ CI ký khóa release | ✅ run `42498277883`, artifact `rfid-tem-check-1.0.0-26e825f-release` (APK SHA-256 `1f935943…6312`): apksigner v1+v2 đạt, chứng chỉ `2441b374…5fad3` trùng `dist/rfid-tem-check-1.0.0.apk` (đối chiếu cả trên CI lẫn trên máy). `assets/products.dat` giống bản 1.0.0, lưu không nén. Vẫn là versionCode 1 — chưa phát hành |
| Artifact CI `61a8594-debug`, `99f2ed3-debug` | ⚠️ ký khóa debug (trước khi có secret), **không cài lên PDA** đang chạy 1.0.0 |
| Gọi API thật từ app | ❌ chưa (PDA chưa có mạng ở lần thử đầu) |
| Broadcast của từng hãng PDA | ❌ chưa thử; vài action là phỏng đoán (mục 6) |
| Camera scan | ❌ chưa thử trên phần cứng |
| Nhập `.xlsx` 19 MB trên PDA (tốc độ, RAM) | ❌ chưa đo |

**v1.0.1** (versionCode 2, 08/11/2026; chỉ chạy test trên CI, chưa thử trên PDA): `ApiClient.describeFailure`
đổi lỗi mạng thành câu dễ hiểu (`NO_NETWORK` khi DNS lỗi, `TIMEOUT`, `CONNECT`, `SSL` → nhắc kiểm tra ngày
giờ hoặc Wi-Fi đòi đăng nhập), kèm gợi ý nhập product ID; dòng "Nguồn" ghi "API Buymed" khi lỗi ở bước
API; nhãn đầu màn hình hiện **MẤT MẠNG** (đỏ) khi bật online mà máy không có mạng, cập nhật theo
`CONNECTIVITY_ACTION`; thêm quyền `ACCESS_NETWORK_STATE`.

### 2a. v1.1.0 (versionCode 3, 15/11/2026)

Yêu cầu người dùng: (1) cập nhật master data, (2) lịch sử dễ đọc hơn, chạm vào một lần quét cũ để
xem lại thông tin + ảnh sản phẩm, (3) kiểm tra tồn kho khi quét; luồng *vào app → chọn kho (kho trên
Circa POS) → quét → loại tem, tên, id, mã QR; chạm → ảnh, tồn kho*; chữ "Tem thường/Tem gập" nhỏ lại.
Người dùng chọn: Buymed giữ làm dự phòng; màn hình chọn kho hiện tất cả kho, có ô tìm.

| Hạng mục | Trạng thái |
|---|---|
| Dữ liệu `loai_tem_rfid_global_san_pham.xlsx` → `assets/products.dat` | ✅ 72.394 SP; so với file 05/11: +169 SP, 2.364 SP đổi loại tem (2.263 từ chưa cấu hình → tem gập, phần lớn tên "[Trùng mã]"), SP 1467 temgap/túi → temthuong/hộp |
| Test JUnit (21), gồm đọc file Excel thật bằng `TableImporter` và so **từng SP** với `products.dat` | ✅ chạy trên Windows bằng JDK 17 portable (mục 7e), sau đợt review |
| `CircaClient` gọi API thật (kho, QR, tồn kho, chi tiết, khóa sai, khóa có ký tự xuống dòng, mã không tồn tại) | ✅ chạy trên JVM bằng khóa của người dùng, 15/11/2026, sau đợt review |
| Resource XML (aapt2 compile/link), dex (d8), APK ký v1+v2 bằng khóa release, căn lề 4 byte | ✅ `dist/rfid-tem-check-1.1.0.apk` (SHA-256 `d8c9d69c…57ac1`), build trên Windows (mục 7e) sau đợt review |
| Chạy trên PDA (giao diện mới, popup, ảnh, chọn kho) | ✅ 16/11/2026 trên CT48, xem mục 2b. Chưa thử: quét khi popup đang mở, đổi kho khi đang tải tồn |
| CI (`./gradlew test assembleRelease`) cho v1.1.0 | ✅ run `43734791530` (commit `091c0fa`), bản `-release`, chứng chỉ khớp |

Ghi chú chuyển dữ liệu: `products.dat` tạo bằng chính `tools/xlsx_to_tsv.py` (Python 3.12 embeddable +
openpyxl 3.1.5 trong `.build-cache/py`, chạy `python -I`). Script được sửa để chạy đúng trên Windows:
in UTF-8 ra console (trước đó lỗi cp1252) và ghi file nén với `newline="\n"` (trước đó gzip ở chế độ
text ghi CRLF). Kết quả giải nén giống từng byte bản tạo bằng port Node dùng trước đó. SHA-256 file
`b815ff20…9417`. Khác biệt có chủ đích với `TableImporter`: script đổi U+00A0 trong 7 tên thành dấu
cách, `TableImporter` giữ nguyên (test so tên sau khi chuẩn hóa khoảng trắng).

#### Review trước phát hành (15/11/2026)

Đã soát lại toàn bộ thay đổi v1.1.0, 15 điểm, đã sửa hết:
- Popup không lấy focus phím (`FLAG_NOT_FOCUSABLE`): máy quét wedge vẫn gõ vào ô quét của màn hình
  chính, mã mới đóng popup và hiện kết quả mới; Back đóng popup. Bỏ cơ chế gom phím trong popup.
- Đổi kho khi đang tải tồn: số của kho cũ bị bỏ (trước và sau khi gọi), không hiện nhầm sang kho mới.
- Làm mới tồn thất bại: giữ số cũ nhưng ghi "(chưa làm mới được, số lúc HH:mm:ss)" màu cam.
- Khung sản phẩm lúc "ĐANG TRA…" không mở popup của mã trước; quét dồn khi mạng chậm không gọi tồn
  cho kết quả đã bị thay; activity đã đóng thì bỏ kết quả về muộn.
- Khóa Circa / mã kho: chỉ giữ ký tự ASCII in được (dán khóa có xuống dòng không làm crash luồng mạng);
  lỗi runtime khi gọi Circa thành thông báo. Giá trị `null` trong QR cài đặt không thành chuỗi "null".
- Mất mạng ở bước Circa thì không gọi tiếp Buymed (tránh chờ timeout lần hai); thông báo timeout ghi
  đúng "Circa không phản hồi kịp" thay vì "API".
- Chi tiết SP lỗi 404 thì 10 phút sau mới hỏi lại (trước đó gọi lại mỗi lần quét).
- Tồn rất nhỏ so với đơn vị dán tem ghi "30 viên (< 0,1 hộp)" thay vì "≈ 0 hộp".
- Ảnh thu về đúng ~720 px (trước có thể gấp đôi), cache ≤ 1/8 heap và ≤ 8 MB.
- Cài đặt → Chọn kho: chặn khi ô khóa trống; sau khi chọn kho nạp lại khóa đã lưu vào form.
- Chọn kho: khóa không thấy kho nào thì báo rõ + THỬ LẠI; không tải lại mỗi lần quay về; nút MỞ CÀI
  ĐẶT quay lại Cài đặt thay vì mở chồng màn hình thứ hai.
- Bỏ field/hàm thừa (`productIdForCode`, mã/dạng bào chế/available không dùng).

Khóa ký `signing/rfidtag-release.jks` **không có trong repo** (repo public). Người dùng giữ bản
duy nhất; phải chép vào `signing/` trước khi build release, nếu không APK mới sẽ không cài đè được
lên bản đã cài. Alias `rfidtag`, mật khẩu đang để mặc định trong `app/build.gradle` và `build_apk.sh`
(xem backlog B5).

### 2b. Thử v1.1.0 trên PDA (16/11/2026) và v1.1.1 (versionCode 4)

Người dùng cài v1.1.0 lên CT48 và chụp màn hình (thư mục `UI_PDA/`, **không commit**: có tên kho, số
tồn, mã PO). Kết quả trên máy thật:
- Chọn kho Circa, quét QR thật: mã dạng mới `B51A1B510CC550000000151E` (24 ký tự hex, không theo mẫu
  timestamp + machine) tra qua Circa trong ~0,6 s → SP 6673, có lô/HSD, tem gập/hộp; mã
  `1764138802830C35` lần 2 lấy từ cache trong 9 ms. Tồn kho và đổi đơn vị đúng ("166 viên ≈ 1,7 hộp",
  "2.400 viên ≈ 2,2 hộp"). Popup có ảnh (6 ảnh), quy cách, số đăng ký, tồn theo vị trí và lô.
- Máy quét đưa được mã vào app (chưa rõ qua wedge hay broadcast).

Lỗi thấy trên ảnh và cách sửa ở v1.1.1:
- **Lịch sử không hiện:** trên 480×800, thẻ loại tem + khung sản phẩm + bộ đếm chiếm hết màn hình,
  `ListView` lịch sử (`layout_weight=1`) còn 0 px. Sửa: thẻ loại tem, khung sản phẩm, bộ đếm chuyển
  thành **header của `ListView`** (`res/layout/main_header.xml`), cả vùng dưới ô quét cuộn được; mỗi
  lần quét (lúc "ĐANG TRA…" và khi có kết quả) tự cuộn về đầu. Dòng "Chạm để xem ảnh và tồn kho ›"
  thay bằng dấu `›` bên phải khung (giống dòng lịch sử), thẻ loại tem bớt đệm, để dòng lịch sử đầu
  tiên lộ ra dưới màn hình.
- **Popup hiện tồn kho cũ:** mở popup lúc 23:22 vẫn thấy số "cập nhật 20:56". Sửa: mở popup mà tồn
  cũ hơn `Resolver.STOCK_TTL_MS` (60 s) thì tự lấy lại (không ép, vẫn dùng cache 60 s).
- Dòng "Nguồn" có hai con số ("Circa (599 ms) · đã lưu cache · 609 ms") → bỏ số ở giữa, chỉ giữ tổng.
- Popup: nhãn "Mã QR:" bị đổi sang font monospace cùng giá trị → chỉ giá trị dùng monospace
  (`TypefaceSpan`). Số lượng từng lô thêm đơn vị ("66 viên").
- Chữ bị cắt: gợi ý ô quét "Quét QR hoặ…" → "Quét mã QR"; dòng dữ liệu trên header "Dữ liệu gốc
  15/11…" → "gốc 15/11/2026" (`ProductRepository.shortSourceLabel`, màn Cài đặt vẫn ghi đầy đủ).

Đã chạy: biên dịch toàn bộ, quét `invokedynamic` (không có), 21/21 test JUnit; APK
`dist/rfid-tem-check-1.1.1.apk` build trên Windows (mục 7e), ký v1+v2 khóa release, chứng chỉ khớp
`2441b374…`, lề 4 byte đúng, SHA-256 `cd23097b…8076`. **Chưa chạy 1.1.1 trên PDA**: cần xem cuộn lịch
sử, chạm dòng lịch sử (vị trí đã trừ header), nút XÓA LỊCH SỬ, khung sản phẩm, và wedge vẫn gõ vào ô
quét sau khi cuộn.

### 2c. v1.2.0 (versionCode 5, 17/11/2026): gọn giao diện + Tồn NW (Nextwaves)

Yêu cầu người dùng (kèm ảnh `UI_PDA/`), và lựa chọn của họ khi được hỏi:
- Hàng quét: bỏ **TRA** (máy quét tự gửi Enter; bàn phím mở bằng ABC có phím Go) và **CAM**; chỉ giữ
  **ABC**, ô và nút cao 40 dp. `CameraScanActivity` vẫn còn trong code + manifest nhưng không có nút
  mở (muốn bật lại: thêm nút gọi `startActivityForResult` và xử lý `EXTRA_TEXT` như bản 1.1.1).
- Thẻ loại tem 24sp / dòng "Dán tem trên" 14sp, đệm nhỏ (trước 30sp/17sp).
- Khung sản phẩm: tên (2 dòng, dấu `›` bên phải) → `ID: … QR: …` → `Lô: … HSD: …` → `SKU: …` →
  `Tồn POS: …` → `Tồn NW: …`. Nhãn và giá trị nối bằng NBSP để khi xuống dòng không tách nhãn khỏi
  giá trị; mã QR dạng 24 ký tự hex tự xuống dòng riêng. Vị trí SKU (sau Lô·HSD) do agent chọn —
  người dùng chỉ nói "thêm SKU".
- Bỏ dòng "Nguồn: …" ở màn chính và popup (`LookupResult.source` vẫn được tính, chỉ không hiện).
- "Tồn kho" → **"Tồn POS"** (màn chính, popup, dòng lịch sử "POS … · NW n thẻ").
- **Tồn NW** = số thẻ EPC còn tồn của sản phẩm trong kho Nextwaves (NW) tương ứng: `NextwavesClient`
  (thuật toán sổ kho của brief NW, `docs/API.md` mục C), `Resolver.fetchNw` (cache 60 s theo kho NW +
  SP; SP không có trên NW hỏi lại sau 10 phút), executor riêng `MainActivity.nwNet` để NW chậm (nhiều
  call, có thể retry 429) không chặn Tồn POS hay lần quét sau. Popup: "Tồn NW: 31 thẻ · 93 cái", dòng
  phụ "Nextwaves Lumina · cập nhật … · khớp tổng NW" hoặc "Chưa khớp: NW ghi X trên thẻ, đếm sổ kho ra
  Y", số chưa gắn thẻ; LÀM MỚI làm mới cả POS lẫn NW.
- Khóa NW: brief yêu cầu chỉ để ở server, nhưng app không có backend → app gọi thẳng NW. Người dùng
  tạo khóa riêng cho APK và chọn (17/11/2026) **nhúng lúc build, không lên GitHub**:
  - Khóa nằm trong `app/src/main/java/com/buymed/rfidtag/BuiltinKeys.java` (gitignored):
    `public final class BuiltinKeys { public static final String NW_KEY = "nwc_pk_…:nwc_sk_…"; }`.
    `Settings.builtinKey` đọc bằng reflection, nên thiếu file (CI, clone mới) vẫn biên dịch; khi đó
    không có khóa sẵn. Máy dev mới: xin khóa từ người dùng, tạo lại file này. Bản sao khóa dạng text ở
    `.build-cache/nw_key.txt` (gitignored) trên máy build hiện tại.
  - Thứ tự dùng: khóa nhập tay / quét QR (`nwKey` trong `RFIDCFG`, ô trong Cài đặt và
    `tools/config_qr.html`) → khóa có sẵn trong app. Ô Cài đặt để trống khi dùng khóa có sẵn (không
    hiện khóa lên màn hình); dòng trạng thái ghi nguồn khóa.
  - APK build trên máy chứa khóa → `dist/*.apk` đã gitignore từ v1.2.0 (1.0.0–1.1.1 vẫn được theo dõi,
    không có khóa). Chép APK qua USB. APK từ CI không có khóa (không có `BuiltinKeys`) → dùng QR.
  - `minifyEnabled false`; nếu bật R8 phải giữ `BuiltinKeys` (chỉ được gọi qua reflection).
- Kho NW: người dùng chọn **tự khớp theo tên kho Circa** (LUMINA → Lumina, FLORITA → Florita); kho
  khác nhập UUID trong Cài đặt / QR (`nwWarehouseId`), không có thì hiện "kho này chưa nối kho
  Nextwaves".

Đã chạy: biên dịch cả khi **có** và **không có** `BuiltinKeys.java`, quét `invokedynamic` (không
có), **29/29 test** (21 `LogicTest` + 8
`NextwavesTest`: khớp kho, parse sản phẩm/summary/EPC, thuật toán sổ kho gồm chia đều cho nhiều EPC,
xuất kho, bán lẻ một phần, kho khác, lật trang, mảng trần, làm tròn; thông báo lỗi 401/429/500/HTML;
thiếu lớp khóa không crash).
Test file Excel thật **bị bỏ qua** vì `loai_tem_rfid_global_san_pham.xlsx` không còn trong Downloads
(dữ liệu đóng gói không đổi từ 1.1.0).

**NW thật, khóa của người dùng (17/11/2026 ~00:15)** — `NextwavesClient` trên JVM, cả 8 dòng bảng test
của brief khớp, `epcQuantity == rfidStock`, chưa cần tra từng EPC: 1467 Florita 1 thẻ · 1.104 viên;
1467 Lumina 0; 1890 Florita 10 · 10 viên; 10871 Lumina 31 · 93 cái; 2013079 Lumina 146 · 1.460 cái;
1066747 Lumina 80 · 800 cái; 93933 Lumina 150 · 150 que; 999999999 không có trên NW. Thêm 6673 Lumina
(SP quét trên PDA) 1 thẻ · 100 viên. 0,2–0,9 s mỗi SP (từ laptop). Khóa giả → 401 + thông báo.

APK `dist/rfid-tem-check-1.2.0.apk` (có khóa NW, **không commit**) ký khóa release, chứng chỉ khớp.
**Chưa**: chạy trên PDA (giao diện mới, Tồn NW qua mạng kho).

### 2d. v1.2.1 (versionCode 6, 17/11/2026): tên sản phẩm bị cắt

Ảnh `UI_PDA/Screenshot_20261117-003148.png` (1.2.0 trên CT48): tên 2 dòng "Bao Cao Su Her Feel Siêu
Mỏng 003 Medevice 3S (H/3c)" bị đẩy xuống và mất nửa dưới dòng 2. Nguyên nhân: tên và dấu `›` (24sp)
chung một `LinearLayout` ngang với `baselineAligned` mặc định và gravity top → tên bị dịch theo
baseline của `›`, còn chiều cao hàng không tăng theo. Sửa (`main_header.xml`):
- Tên một hàng riêng, toàn chiều ngang, tối đa **3 dòng**, `lineSpacingExtra` 2 dp (dấu tiếng Việt).
- Đường kẻ mảnh ngăn tên với các dòng mã (ID·QR, Lô·HSD, SKU).
- Tồn POS + Tồn NW trong ô nền nhạt (`bg_image`), dấu `›` căn giữa dọc bên phải ô, hàng đặt
  `baselineAligned="false"`; ô ẩn khi không có dòng tồn nào (tra lỗi).
Các hàng ngang khác (lịch sử, header, bộ đếm, đầu popup) dùng `gravity="center_vertical"` nên không
bị dịch theo baseline.

Đã chạy: biên dịch, 29/29 test, aapt2 link layout mới; APK `dist/rfid-tem-check-1.2.1.apk` (có khóa NW,
không commit) ký khóa release, chứng chỉ khớp, đã chép vào `Download` của PDA. **Chưa** xem trên PDA.

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
4. **Circa POS** `GET /v1/pos/{store}/qr/{code}` (từ v1.1.0; cần bật online, có khóa Circa và đã
   chọn kho) → product_id, SKU, lô, HSD, đơn vị trên QR, ảnh → lưu `qr_cache` (cột `sku, lot, expiry,
   unit`, DB version 2, nâng cấp bằng `ALTER TABLE` nên cache cũ trên máy được giữ). Circa không trả
   machineCode nên đường này không nạp `batch_cache`.
5. **API check Buymed** (dự phòng: khi Circa không tra được/không cấu hình, có token) → lưu
   `qr_cache` + `batch_cache`.

Hiển thị:
- Đường 1–4: loại tem lấy từ **dữ liệu local** (Circa không trả loại tem). Không có trong local →
  `KHÔNG CÓ DỮ LIỆU`, tên lấy từ Circa.
- Đường 5: hiển thị `rfidTagType` **của API Buymed** (vì cấu hình theo từng customer), và cảnh báo nếu
  local khác hoặc local không có sản phẩm. Lý do: API là nguồn đúng tại thời điểm đó; cảnh báo giúp
  phát hiện file dữ liệu cũ.

Tồn kho + chi tiết sản phẩm (`Resolver.fetchExtra`) chạy **sau** khi đã hiện loại tem, trên executor
riêng (`MainActivity.net`), nên API chậm không làm chậm việc quét. Tồn kho cache 60 s theo
(kho, product) — quét liên tiếp nhiều thùng cùng sản phẩm chỉ hỏi một lần; nút LÀM MỚI trong popup bỏ
qua cache. Chi tiết sản phẩm (ảnh, quy cách, quy đổi đơn vị) cache theo product trong phiên; Circa trả
lỗi (vd. 404) thì 10 phút sau mới hỏi lại; mất mạng ở bước tồn kho thì bỏ qua bước chi tiết.

Hướng triệt để (đã đề xuất cho người dùng, chưa ai làm):
- **In `?p=<productId>` vào QR** khi sinh mã. Tài liệu API nói query string bị bỏ qua, nên API cũ vẫn
  chạy; app đã hỗ trợ sẵn `p=` → 100% offline.
- Hoặc endpoint nhẹ `code → productId` không gọi sang Circa.
- Hoặc xuất định kỳ bảng `mã QR → productId` theo PO đang nhập để nạp vào máy.

## 4. Dữ liệu

- Nguồn hiện tại (từ v1.1.0): `loai_tem_rfid_global_san_pham.xlsx` (người dùng gửi 15/11/2026; **không
  có trong repo**), sheet `global_rfid_tag_type`, cột `product_id, product_name, volumes, type_tag,
  type_tag_label, rfid_unit_name, configured`. Ô kiểu `t="str"`, không có `sharedStrings.xml`.
  72.394 SP: `temthuong` 42.709 · `temgap` 21.151 · chưa cấu hình 8.534. Cột `volumes` (quy cách)
  chưa đưa vào app (popup lấy quy cách từ Circa).
- File `Master data rfid 1.xlsx` (13/11) **không có loại tem** (chỉ đơn vị in + tỷ lệ quy đổi) —
  không dùng được thay thế.
- Nguồn cũ (v1.0.x): `rfid_client_san_pham_1.xlsx` (05/11/2026), sheet `client_rfid_unit`, cột:
  `product_id, product_name, type_tag, type_tag_label, rfid_unit_name`; chuỗi inline.
- 72.225 sản phẩm (dữ liệu cũ), không trùng ID, ID lớn nhất 999903777:
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
| `MainActivity` | Màn hình quét. Nhận input qua keyboard wedge (Enter/Tab/`\n`), broadcast, camera. Bỏ trùng cùng chuỗi trong 800 ms. Header có dòng **Kho** (chạm → chọn kho), thẻ loại tem có màu, khung sản phẩm (chạm → popup), bộ đếm — ba phần này là header của `ListView` lịch sử (`main_header.xml`, từ v1.1.1) nên cuộn cùng lịch sử; lịch sử 200 dòng gần nhất (chỉ trong RAM, chạm → popup; vị trí trừ `getHeaderViewsCount()`). Mỗi lần quét cuộn về đầu. Mở popup mà Tồn POS hoặc Tồn NW cũ hơn 60 s thì tự lấy lại. Ba executor: `exec` (tra mã), `net` (Tồn POS/chi tiết), `nwNet` (Tồn NW). Từ v1.2.0 hàng quét chỉ có ô nhập + ABC. Lần đầu có khóa mà chưa chọn kho → tự mở `StoreActivity` |
| `ScanDetailDialog` | Popup một lần quét: ảnh (chạm để xem ảnh tiếp), tên, quy cách, ID/SKU/QR/lô/HSD/đơn vị, thời điểm + kho lúc quét, cảnh báo (từ v1.2.0 không còn dòng nguồn), Tồn POS, Tồn NW, tồn POS theo SKU → vị trí → lô (tối đa 80 dòng), nút LÀM MỚI (cả POS lẫn NW). Cửa sổ không lấy focus phím (`FLAG_NOT_FOCUSABLE`) nên máy quét wedge vẫn gõ vào màn hình chính; Back do `MainActivity` đóng popup |
| `StoreActivity` | Chọn kho: `CircaClient.stores` (`/v1/authz/me`), tìm không dấu theo tên/mã POS, lưu id/code/name/client_id; đổi kho thì xóa cache tồn |
| `CircaClient` | Circa POS API (mục A của `docs/API.md`): `stores`, `qr`, `stock`, `detail`; tách parse khỏi HTTP để test trên JVM; `Auth` thay cho `Settings` để gọi được trên JVM thường; `stockLine` đổi tồn từ đơn vị gốc sang đơn vị dán tem |
| `NextwavesClient` | Nextwaves public API (mục C của `docs/API.md`): `product` (Circa id → SP NW), `epcStock` (summary + sổ kho lật trang + tra từng EPC khi lệch, ≤ 200 EPC, 4 luồng), retry 429; `warehouseFor` khớp kho Circa → kho NW theo tên; parse tách khỏi HTTP |
| `ImageLoader` | Tải ảnh https (tối đa 8 MB), giảm mẫu rồi thu về ~720 px, `LruCache` ≤ 8 MB và ≤ 1/8 heap, 2 luồng, không cache đĩa |
| `Resolver` | Luồng tra (mục 3) chạy trên executor đơn luồng; `fetchExtra` lấy tồn kho (cache 60 s theo kho + SP) + chi tiết (cache phiên, lỗi thì 10 phút hỏi lại); `fetchNw` đếm thẻ NW (cache 60 s theo kho NW + SP, SP NW cache phiên, 404 hỏi lại sau 10 phút); QR cài đặt và đổi khóa/kho xóa các cache này |
| `QrParser` | Làm sạch input (ký tự điều khiển, BOM, zero-width, tiền tố AIM `]Q1`), phân loại EMPTY / PRODUCT_ID / QR_CODE / CONFIG; tách timestamp/machine |
| `ProductRepository` | `HashMap<Long, Product>` trong RAM (ước tính ~15 MB, chưa đo), `CountDownLatch` báo đã nạp xong; thay dữ liệu bằng ghi file tạm rồi rename |
| `ProductStore` | Đọc/ghi TSV gzip |
| `TableImporter` | CSV/TSV (RFC 4180, field nhiều dòng) và XLSX (ZipFile + XmlPullParser, sharedStrings/inline/rich text, tìm sheet đầu qua workbook rels). Không phụ thuộc Android ngoài interface XmlPullParser → test được trên JVM |
| `DataImporter` | Copy URI vào cache, nhận dạng định dạng, JSON qua `android.util.JsonReader` |
| `ScanCache` | SQLite `scan_cache.db` (version 2): `qr_cache(code PK, product_id, created, sku, lot, expiry, unit)`, `batch_cache(ts, machine, product_id, created, PK(ts,machine))` |
| `ApiClient` | API Buymed (dự phòng): `HttpURLConnection` POST check, timeout kết nối 6 s / đọc 12 s, parse bằng `org.json`; `describeFailure` dùng chung cho cả Circa |
| `Settings` | SharedPreferences `settings`; áp dụng QR cấu hình `RFIDCFG:`; khóa Circa và mã kho chỉ giữ ký tự ASCII in được (`headerSafe`) vì đi vào header HTTP |
| `SettingsActivity` | Form cài đặt; nhập dữ liệu (SAF `ACTION_OPEN_DOCUMENT`), khôi phục dữ liệu gốc, xóa cache; việc nặng chạy nền sau dialog chặn |
| `CameraScanActivity` | Camera1 + ZXing core (QR, DataMatrix, Code128, EAN-13), cắt giữa khung rồi thử lại cả khung, đèn flash, tự xin quyền camera |
| `Feedback` | Âm: thường 1 bíp, gập 2 bíp, lỗi âm dài + rung |
| `LookupResult`, `HistoryAdapter`, `Product`, `DateFmt` | Model/hiển thị |

Màu (từ v1.1.0 theo màn hình mẫu người dùng gửi, "Tem Thường – Vita C glucose"): header `#1F5FA8`,
nút `#1F6FC5`, nền `#F3F5F8`; tem thường `#23843A` xanh lá, tem gập `#D9730D` cam, loại khác/cài đặt
`#1F5FA8`, chưa cấu hình `#5F6B73` xám, lỗi/không có dữ liệu `#B3261E` đỏ. Chữ loại tem 30sp (v1.0: 40sp).

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
| `circa_key` | rỗng | khóa Circa POS, gửi `Bearer` tới `CircaClient.BASE_URL` (cố định `https://api.v2.circa.vn`) |
| `store_id`, `store_code`, `store_name`, `circa_client_id` | rỗng / 1 | kho đang chọn |
| `nw_key` | rỗng | khóa Nextwaves (v1.2.0), gửi `Bearer` tới `NextwavesClient.BASE_URL` (cố định) |
| `nw_warehouse_id` | rỗng | UUID kho NW nhập tay; rỗng = khớp theo tên kho Circa (`Settings.nwWarehouseId`) |

`online` từ v1.1.0 điều khiển cả Circa (tra mã + tồn kho) lẫn Buymed, từ v1.2.0 cả Nextwaves.

QR cấu hình: `RFIDCFG:{"circaKey":…,"nwKey":…,"nwWarehouseId":…,"storeId":…,"storeCode":…,"storeName":…,"baseUrl":…,"token":…,"customerId":…,"online":…,"batchInference":…,"scanAction":…,"scanExtra":…}`
(`nwWarehouseId` phải là UUID hoặc rỗng, sai thì cả QR bị từ chối).
An toàn: `baseUrl` phải `https://`; đổi `baseUrl` mà QR không kèm `token` → xóa token đang lưu (để QR
lạ không làm app gửi token sang server khác).

`circaKey` chỉ đổi khóa; địa chỉ Circa cố định trong app nên QR lạ không thể chuyển khóa sang server
khác. `storeId` (kèm `storeCode`/`storeName` tùy chọn) đặt sẵn kho.

Tạo mã: `tools/config_qr.html` — trang HTML một file, chạy offline (thư viện qrcode-generator MIT nhúng
sẵn, không gọi mạng). Nhập khóa Circa (chính), khóa Nextwaves (từ v1.2.0, kiểm dạng
`nwc_pk_…:nwc_sk_…`) và/hoặc token Buymed + môi trường + customerId (mục
"API Buymed (dự phòng)") → QR `RFIDCFG:{circaKey[,baseUrl,token,customerId]}` (tự thêm `Basic ` cho
token Buymed). Ô khóa kiểu password; bấm **Xóa** hoặc đóng tab là xóa khóa và mã QR. Không đưa
`storeId` vào trang: app tự mở màn hình chọn kho sau khi quét.
Đã kiểm (15/11/2026, Node, chạy code của chính trang): payload đúng định dạng `Settings.applyConfig`
(chỉ Circa / Circa + Buymed / chỉ Buymed), chặn khi không nhập gì, khóa có dấu cách, customerId sai
khi có token Buymed; token 162 ký tự vẫn vừa (QR version 11). **Chưa** quét thử bằng PDA, chưa giải mã
ngược QR bằng bộ đọc độc lập.

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

### b) Gradle / Android Studio (đã chạy trên CI, chưa thử trên Windows)

AGP 8.7.3, Gradle wrapper 8.14.3 (có `distributionSha256Sum`), compileSdk/targetSdk 34, minSdk 21,
Java 1.8. Cần JDK 17+ và Android SDK 34. Ký release bằng `signing/rfidtag-release.jks` nếu có, không
có thì dùng khóa debug.

Lưu ý: khi mở repo trong VS Code ngày 08/11/2026, extension Java/Gradle đã tự chạy Gradle và
**tự nâng wrapper lên 9.7.1** (sửa `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`,
`gradle-wrapper.properties`). Thay đổi đó đã được hoàn tác; nếu thấy lại thì không commit.
Trên Windows: cài Android Studio → mở `C:\tags_RFID` → để Studio tạo `local.properties`/tải SDK →
Build → Generate Signed/Build APK, hoặc `.\gradlew.bat assembleRelease`. Việc đầu tiên nên làm là
chạy thử và sửa lỗi build nếu có.

### c) Ghi chú môi trường cloud cũ (chỉ khi agent chạy trên container Claude Code cloud)

`dl.google.com` bị chặn (`maven.google.com` redirect về đó) → không tải được AGP/SDK. Maven Central
đôi khi trả 429, đợi vài giây thử lại, hoặc dùng `repo1.maven.org` thay `repo.maven.apache.org`.
`raw.githubusercontent.com` dùng được. Host API Buymed bị chặn.

### d) GitHub Actions — `.github/workflows/build.yml`

Chạy khi push vào `main`, khi có PR, hoặc bấm tay (*Run workflow*). `ubuntu-latest` (hiện là
ubuntu-26.04, có sẵn Android SDK) + Temurin JDK 17, cache Gradle. Các bước: khôi phục khóa ký từ
secret (nếu có) → `./gradlew --no-daemon test assembleRelease` (in từng test ra log) → đổi tên APK
thành `rfid-tem-check-<versionName>-<sha7>-<release|debug>.apk`, ghi `SHA256SUMS.txt` và
`aapt dump badging` vào Summary → `apksigner verify --print-certs` (bản `release` mà chứng chỉ
khác SHA-256 `2441b374…5fad3` của khóa RFID Tem Check thì **fail**, không upload) → upload artifact
(giữ 30 ngày); test lỗi thì upload `app/build/reports/tests/` → xóa file khóa.

Khóa release: PKCS12 (dù đuôi `.jks`), alias `rfidtag`, `CN=RFID Tem Check, O=Buymed, C=VN`, hạn đến
2054-03-23, chứng chỉ SHA-256 `2441b37448f4140b586aa1d310040706c08a257882416c87c0083da85fc5fad3`
(trùng chứng chỉ trong `dist/rfid-tem-check-1.0.0.apk`, đã đối chiếu ngày 08/11/2026).

- Secret `RELEASE_KEYSTORE_BASE64` (base64 của `signing/rfidtag-release.jks`) **đã thêm ngày
  08/11/2026** → push vào `main` ra bản `-release`. `RELEASE_KEYSTORE_PASSWORD` (map vào `KS_PASS`
  mà `app/build.gradle` đọc) không cần vì mật khẩu là mặc định. Thiếu secret thì CI ký bằng khóa
  debug sinh mới mỗi lần chạy (đuôi `-debug`), các bản đó không cài đè lên nhau được.
- Secret không có ở PR từ fork → PR luôn ra bản `debug`. `permissions: contents: read`.
- Không đưa token API vào workflow; test file Excel thật (`-Prfid.xlsx`) không chạy trên CI vì file
  không có trong repo.

### e) Windows không có Android Studio (đã dùng cho v1.1.0, 15/11/2026)

Máy người dùng (Windows 11, Git Bash) không có JDK/SDK/WSL/Python. Đã dựng trong `.build-cache/`
(gitignored): Temurin JDK 17 portable (`jdk17-tmp/jdk-17.0.21+9`), `android-34.jar`, jar test, Python
3.12.8 embeddable + openpyxl 3.1.5 (`py/`, cho `tools/xlsx_to_tsv.py`), và từ
Google Maven: `aapt2` 8.7.3-12006047 (windows), `r8` 8.7.18 (d8), `apksig` 8.7.3. Các bước tương đương
`build_apk.sh`: chèn `package=` vào bản sao manifest → `aapt2 compile --dir res` → `aapt2 link -I
android-34.jar --min-sdk-version 21 --target-sdk-version 34 --version-code/--version-name -A assets -0 dat
--java gen` → `javac -source 8 -target 8 -bootclasspath android-34.jar` → `jar` → `D8 --release
--min-api 21 --lib android-34.jar` (cùng zxing) → gộp `classes.dex` vào APK (giữ nguyên kiểu nén) →
ký v1+v2 bằng `com.android.apksig.ApkSigner` với `signing/rfidtag-release.jks` (apksig tự căn lề 4
byte) → `ApkVerifier` + so SHA-256 chứng chỉ + kiểm lề → `aapt2 dump badging`. Script và tool ký
nằm ngoài repo (scratchpad của phiên); muốn dùng lại thì đưa vào `tools/` (backlog B7).
Kiểm tra compile nhanh không cần aapt2: sinh `R.java` giả từ `res/`, `javac` toàn bộ, quét
`javap -c` tìm `invokedynamic`.

## 8. Test

`app/src/test/java/com/buymed/rfidtag/LogicTest.java` — 21 test JUnit4: các dạng QR trong tài liệu,
làm sạch input, product id trực tiếp/`p=`, QR cấu hình, tách lô, round-trip TSV, CSV có ngoặc kép/alias/
trùng/ID lỗi, CSV `;` và TSV không header, chỉ số cột Excel, parse response API Buymed OK/INVALID, lỗi
mạng; **Circa** (v1.1.0): danh sách kho (sắp theo tên), tra QR, tồn kho (bỏ dòng của sản phẩm khác),
chi tiết + đổi đơn vị ("3.312 viên = 3 hộp", "≈ 1,5 hộp", "30 viên (< 0,1 hộp)"), định dạng số, thông
báo lỗi 401/500/HTML, lỗi mạng ghi đúng tên dịch vụ ("Circa không phản hồi kịp"), làm sạch khóa Circa
(`headerSafe`) và giá trị null trong QR cài đặt (`jsonStr`), URL tồn kho dùng `product_ids`, tìm kho
không dấu; và đọc file Excel thật (chỉ chạy khi có
`-Prfid.xlsx=…`, kiểm cả XmlPullParser bật và tắt namespace, và so từng SP với `assets/products.dat`
khi chạy từ thư mục `app/` hoặc gốc repo).

Smoke test API thật (không commit, cần khóa trong biến môi trường): gọi `CircaClient.stores/qr/stock/
detail` trên JVM với `Auth` dựng tay — đã chạy 15/11/2026: 33 kho; QR `1764138802830C35` → 1467
(116 ms); QR `1792815001086C1` (mã PDA quét hôm 08/11) → 86445; tồn 1467 "3.312 viên = 3 hộp" (Kho
Inbound, 3 lô); mã giả → 500 + thông báo; khóa sai → 401 + thông báo. Chạy lại sau đợt review (cùng
ngày): kết quả như trên, thêm khóa có ký tự xuống dòng → `BAD_REQUEST` + "Khóa API Circa hoặc mã kho
có ký tự không hợp lệ…" (không crash).

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

`NextwavesTest.java` (v1.2.0) — 7 test: khớp kho theo tên, nhãn kho, UUID; parse sản phẩm (có/không
bọc `data`), summary; thuật toán sổ kho (chia đều, xuất, bán lẻ một phần, kho khác bị bỏ, EPC dạng
object, lật trang, trang rỗng, mảng trần, làm tròn 3 số); trả lời từng EPC; dòng hiển thị; lỗi HTTP.

Smoke test NW thật (không commit, khóa đọc từ file ngoài repo, không in khóa): gọi
`NextwavesClient.product/epcStock` cho các SP trong bảng test của brief. 17/11/2026 mới chạy với khóa
giả (401 đúng thông báo); **chưa chạy với khóa thật**.

Chưa có test cho: `Resolver` (phụ thuộc `ScanCache`/`Settings` của Android), `DataImporter.readJson`,
`ImageLoader`, toàn bộ UI (popup, chọn kho, quét khi popup đang mở).

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
| Circa là nguồn tra mã chính, Buymed dự phòng (v1.1.0) | Circa trả cả SKU/lô/HSD/ảnh, ~0,1–0,3 s, một khóa dùng chung cho tồn kho; người dùng chọn giữ Buymed dự phòng |
| Loại tem vẫn từ dữ liệu local khi tra qua Circa | Circa POS không trả loại tem (chỉ `is_rfid_unit`) |
| Khóa Circa không đóng gói, địa chỉ Circa cố định trong code | Repo public, APK chia sẻ; QR cài đặt lạ không đổi được nơi nhận khóa |
| Tồn kho lấy sau khi hiện loại tem, executor riêng, cache 60 s | Không làm chậm thao tác quét; quét nhiều thùng cùng SP không gọi API lặp |
| Tồn kho hiện `on_hand_qty`, đổi sang đơn vị dán tem theo `retail_units.convert_rate` | `available_qty` cấp SKU có thể 0 dù lô còn hàng (Kho Inbound) |
| Popup thay vì màn hình mới, popup không lấy focus phím | Màn hình chính vẫn nhận cả broadcast lẫn wedge khi popup đang mở; không phải tự gom phím trong popup |
| Ảnh chỉ tải khi mở popup, không cache đĩa | Ảnh PNG ~160 KB/ảnh; người dùng chỉ cần khi chạm vào |
| Khóa Nextwaves trên PDA, app gọi thẳng NW (v1.2.0) | Brief NW muốn khóa chỉ ở server, nhưng không có backend; người dùng chọn cách giống khóa Circa (17/11/2026). Khóa không trong APK/repo, `allowBackup=false`, địa chỉ NW cố định |
| Đếm thẻ NW bằng sổ kho thay vì tra từng EPC | 3+ call thay vì 1 call/EPC; tra từng EPC chỉ khi tổng lệch `rfidStock`, tối đa 200 EPC |
| Kho NW khớp theo tên kho Circa | Người dùng chọn; NW chỉ có 2 kho đã biết (Lumina, Florita); UUID nhập tay cho kho khác |
| Bỏ TRA, CAM; giữ ABC (v1.2.0) | Người dùng chọn; màn 480×800 cần chỗ cho Tồn POS/NW |

## 10. Rủi ro / lỗi đã biết

- Phần lớn đã chạy trên CT48 (mục 2b); chưa thử: camera, nhập file trên máy, quét khi popup mở.
  Rủi ro khi đổi model PDA: hành vi keyboard wedge (gửi Enter hai lần, gửi theo ký tự…), broadcast
  sai action, camera xoay/khung không khớp.
- `MainActivity` khóa executor đơn luồng trong lúc chờ nạp dữ liệu (`waitForData`): lần quét đầu tiên
  đợi đến khi nạp xong. Có thể chấp nhận được, nhưng cần đo trên PDA.
- Lịch sử và bộ đếm mất khi thoát app.
- Phiên bản ghi lặp ở `app/build.gradle` và `build_apk.sh`.
- Mật khẩu keystore mặc định ghi rõ trong code (chỉ an toàn chừng nào file `.jks` không lộ).
- API timeout cố định 6 s/12 s; chưa retry; chưa phân biệt lỗi 4xx/5xx trên UI ngoài `errorCode`.
- Không có cơ chế cập nhật dữ liệu tự động; phải nhập tay hoặc build lại APK.
- ~~`HistoryAdapter` hiển thị "CHƯA CH"~~ — v1.1.0 hiện "CHƯA CẤU HÌNH" (chip 2 dòng).
- Khóa Circa `ck_u_…` là khóa **cá nhân** của người dùng (quyền "Người xem", thấy 33 kho); mọi PDA
  dùng chung khóa đó. Nên xin khóa tài khoản dịch vụ. Endpoint Circa không có tài liệu chính thức,
  có thể đổi mà không báo.
- Circa trả HTTP 500 cho mã QR không tồn tại → không phân biệt được với sự cố server.
- Quét wedge khi popup đang mở dựa vào `FLAG_NOT_FOCUSABLE` (phím đi xuống cửa sổ màn hình chính)
  — chưa thử trên máy thật; nếu model nào không nhận, đóng popup rồi quét (broadcast không bị ảnh hưởng).
- Ảnh giải mã ARGB_8888 ~720 px (~2 MB/ảnh trong RAM, cache ≤ 8 MB); chưa đo trên PDA RAM thấp.
- Khóa Nextwaves nằm trên mọi PDA (SharedPreferences); PDA bị root / mất máy thì lộ khóa → đổi khóa
  trên NW. Endpoint NW dùng ở đây không có tài liệu chính thức về sổ kho; thuật toán đếm dựa trên
  brief (đã đối chiếu 7 SP ở Lumina/Florita), có thể sai nếu NW đổi cách ghi sổ.
- Sản phẩm bán chạy có sổ kho lớn: mỗi 1.000 dòng là 1 call (tối đa 50 trang); tra từng EPC khi lệch
  có thể tới 200 call. Chưa đo thời gian trên PDA / mạng kho.

## 11. Backlog đề xuất (theo thứ tự)

**P0 — cần người dùng**
- A0. ~~Cài 1.1.0 lên PDA, chọn kho, quét mã thật, popup + ảnh~~ — **xong 16/11/2026** (mục 2b).
  Còn lại với 1.2.0 (`dist/rfid-tem-check-1.2.0.apk` trên máy build, cài đè): giao diện gọn mới,
  Tồn NW ở kho Lumina/Florita (mạng kho, thời gian đếm), kho khác hiện "chưa nối kho Nextwaves"; cuộn lịch sử, chạm dòng lịch sử, XÓA
  LỊCH SỬ, LÀM MỚI, đổi kho, quét mã mới khi popup đang mở và sau khi đã cuộn xuống (wedge và
  broadcast). Ghi lại phiên bản Android của CT48 (Cài đặt → Thông tin điện thoại).
- A1. Người dùng cài APK lên PDA, cấu hình token, thử: quét wedge/broadcast/camera, tra
  `1101363`, tra mã QR thật online rồi offline lần 2, nhập file dữ liệu. Thu thập: model PDA, phiên
  bản Android, ảnh màn hình lỗi.
- A2. Hỏi đội QR/backend: (a) in được `?p=<productId>` vào QR không; (b) giả định "cùng 13 số đầu +
  machineCode = cùng sản phẩm" đúng không; (c) có endpoint nhẹ `code → productId` không.

**P1 — dev**
- B1. ~~Dựng đường build được kiểm chứng~~ — **xong 08/11/2026** bằng GitHub Actions (mục 7d).
  Secret khóa release đã thêm, CI ra bản `-release` cài đè được. Còn lại: lần phát hành tới dùng APK
  từ CI, tăng `versionCode`/`versionName` ở **cả** `app/build.gradle` và `build_apk.sh`.
- B2. Sửa lỗi phát sinh từ A1.
- B3. Lưu lịch sử quét (SQLite) và xuất CSV (mã, product, tem, thời gian, nguồn) để đối soát.
- B4. Tìm sản phẩm theo tên (ô tìm kiếm) cho trường hợp tem/QR hỏng.
- B5. Gom phiên bản về một chỗ (ví dụ `version.properties` dùng chung cho Gradle và script); chuyển
  mật khẩu keystore sang biến môi trường/`keystore.properties` (gitignored).
- B6. Unit test cho `Resolver` (tách interface cho cache/settings/api).
- B7. Đưa đường build Windows (mục 7e) vào `tools/` nếu người dùng muốn build không cần CI.
- B8. Đưa cột `volumes` (quy cách) của file dữ liệu mới vào app để popup có quy cách cả khi offline
  (đổi định dạng TSV thành 5 cột, `ProductStore` đọc được cả 4 và 5 cột).

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
