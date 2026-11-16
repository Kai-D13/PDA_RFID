# API cho RFID Tem Check — tóm tắt cho dev

Từ v1.1.0 app dùng **Circa POS API** (mục A) để tra mã QR, tồn kho, ảnh; từ v1.2.0 thêm **Nextwaves** (mục C)
để đếm số thẻ EPC còn tồn ("Tồn NW"); **API Buymed RFID QR**
(mục B) chỉ còn là dự phòng khi Circa không tra được mã.

---

# A. Circa POS API (`api.v2.circa.vn`) — nguồn chính từ v1.1.0

Tìm ra từ network capture của `pos.v2.circa.vn/danh-sach-ton-kho` (15/11/2026) và gọi thử bằng
khóa API thật. Không có tài liệu chính thức; các điểm "lạ" dưới đây đều đã đo trực tiếp.

## Xác thực

| Header | Giá trị |
|---|---|
| `Authorization` | `Bearer ck_u_…` — khóa API **cá nhân** (gắn tài khoản người tạo, quyền "Người xem"). Không commit, không đóng gói vào APK |
| `X-Store-Id` | id kho (UUID) đang chọn |
| `X-Circa-App` | `pos` |
| `X-Client-Id` | `client_id` của kho (hiện là `1` = CIRCA) |

Khóa sai/hết hạn → HTTP 401 `{"success":false,"errors":[{"code":"invalid_token","message":"Token sai",…}]}`.
Envelope chung: `{"success":true,"data":…}` hoặc `{"success":false,"errors":[{code,message,status}]}`.

## Endpoint app dùng (tất cả là GET, chỉ đọc)

| Mục đích | Endpoint | App đọc |
|---|---|---|
| Danh sách kho | `/v1/authz/me` | `data.clients[].client_id`, `data.clients[].stores[] {id, code, name}` (33 kho với khóa hiện tại) |
| Mã QR → sản phẩm | `/v1/pos/{storeId}/qr/{code}` | `product_id, product_name, sku, lot, expired_date, unit_name, image_urls[]` (~100–300 ms) |
| Tồn kho 1 sản phẩm | `/v1/inventory/admin/stock/search?query=pos_id={storeId},product_ids={id},include_negative_qty=true,&limit=50&page=1` | `list[] {sku, product_id, base_unit, on_hand_qty, available_qty, items[] {location_code, location_name, on_hand_qty, details[] {lot_date, on_hand_qty, available_qty, doc_code}}}` |
| Chi tiết sản phẩm | `/v1/pos/{storeId}/product/{id}` | `name, volumes, registration_number, image_urls[], retail_units[] {unit, convert_rate, base_unit, is_rfid_unit}` |

`code` là phần mã sau dấu `/` cuối của QR (`1764138802830C35`), app URL-encode trước khi gọi.
Tham số `query` app URL-encode cả cụm (`pos_id%3D…%2Cproduct_ids%3D…`); server nhận như bản không encode.

## Điểm bất thường (đã kiểm chứng 15/11/2026)

- Lọc tồn kho theo `product_id=` **bị bỏ qua** (trả về sản phẩm khác); `product_ids=` lọc đúng
  (`product_ids=14` → 0 dòng). `sku=` cũng bị bỏ qua, `sku_code=` lọc đúng. App vẫn lọc lại theo
  `product_id` khi đọc, phòng khi server bỏ qua bộ lọc.
- Mã QR không tồn tại → **HTTP 500** `{"code":"UNKNOWN","message":"Hệ thống gặp lỗi…"}`, không phân
  biệt được với lỗi server thật; app báo cả hai khả năng.
- Sản phẩm không có → `/product/{id}` trả 404 `NotFoundError`; tồn kho trả `list: []`.
- `available_qty` ở cấp SKU có thể là 0 trong khi từng lô vẫn có `available_qty` > 0 (hàng ở Kho
  Inbound). App hiển thị `on_hand_qty` là con số chính.
- `convert_rate` trong `retail_units` tính theo đơn vị gốc (`base_unit: true`), ví dụ SP 1467:
  hộp 1104, túi 24, viên 1; `is_rfid_unit` đánh dấu đơn vị dán tem (hộp). App dùng để đổi
  "3.312 viên = 3 hộp".
- `pos.v2.circa.vn/thuocsi-warehouse/qrcode?code=…` (POS web dùng để tra QR) cần cookie đăng nhập,
  khóa API không dùng được (307 → `/login`). Dùng `/v1/pos/{store}/qr/{code}` thay thế.
- Ảnh sản phẩm ở `https://cdn-gcs.thuocsi.vn/thuocsi-live/images/…` là public (PNG ~160 KB), không
  cần header.

Ví dụ (rút gọn) `GET /v1/pos/{store}/qr/1764138802830C35`:

```json
{"success":true,"data":{"product_id":1467,"product_name":"Vita c glucose mekophar (lốc/46 túi/24 viên)",
 "sku":"CIRCA.MEK-VIT-001","lot":"25055KN","vat":5,"expired_date":"23-08-2027","unit_name":"túi",
 "retail_unit_detail":{"unit":"túi","convert_rate":24,"is_rfid_unit":false},
 "image_urls":["https://cdn-gcs.thuocsi.vn/thuocsi-live/images/2024edf13ec8d84db02bd1c818f2b823", "…"]}}
```

Ví dụ (rút gọn) tồn kho SP 1467 ở kho mặc định của khóa:

```json
{"success":true,"data":{"list":[{"sku":"CIRCA.MEK-VIT-001","product_id":1467,"base_unit":"viên",
 "on_hand_qty":3312,"available_qty":0,"seller_code":"CIRCA","items":[{"location_code":"WH-INBOUND",
 "location_name":"Kho Inbound","on_hand_qty":3312,"details":[
  {"lot_date":"25074KN-181027","on_hand_qty":1104,"available_qty":1104,"doc_code":"PO000…"}, "…"]}]}],
 "total":1,"limit":50,"page":1}}
```

---

# B. API Buymed RFID QR — dự phòng

Nguồn: tài liệu nội bộ "Circa RFID QR API v1" (file HTML, do BA/SE Buymed phát hành). **File gốc
chứa token production — không commit vào repo này.** Hỏi người dùng nếu cần bản gốc.

## Host & xác thực

| | Staging | Production |
|---|---|---|
| Base URL | `https://open-apis.stg.buymed.tech` | `https://open-apis.buymed.com` |
| Header | `Authorization: Basic <token>` | `Authorization: Basic <token>` |

Token khác nhau cho từng môi trường, nằm trong tài liệu gốc. Trong app, người dùng nhập ở
Cài đặt hoặc quét QR `RFIDCFG:{...}`; app tự thêm `Basic ` nếu chuỗi dán vào chưa có scheme.

## Envelope chung

```json
{ "status": "OK|INVALID|ERROR", "message": "...", "errorCode": "...", "data": [ { ... } ] }
```

`OK` → HTTP 200, `INVALID` → 400 (thử lại vẫn lỗi), `ERROR` → 500 (thử lại có thể được).
`data` luôn là mảng 1 phần tử. Rẽ nhánh theo `status`/`errorCode`, **không** theo `message`
(tiếng Việt, có thể đổi câu chữ).

## 1. Check (chỉ đọc) — app dùng làm dự phòng khi Circa không tra được mã

`POST /api/v1/circa/rfid-qr/check`

```json
{ "qr": "thuocsi.vn/qr/1787548411337B731", "customerId": 32145 }
```

`qr` nhận: URL `https://qr.v2.circa.vn/item/<code>`, URL `https://thuocsi.vn/qr/<code>`, hoặc mã trần.
Server lấy phần sau dấu `/` cuối, **bỏ query string và fragment**, bỏ `/` ở cuối.
`customerId` bắt buộc: Circa cấu hình tem theo từng khách hàng.

Response thật (05/11/2026, production, rút gọn) — có thêm vài trường không có trong tài liệu
(`circa*`, `printUnit*`):

```json
{
  "status": "OK", "message": "Thành công.",
  "data": [{
    "sourceQrCode": "1787548411337B731",
    "productId": 1101363,
    "productName": "Kẹo giải rượu Party Smart Himalaya (H/2v)",
    "sellerCode": "TAYAU_INT", "sku": "TAYAU_INT.9DCBBCV8",
    "lot": "SG2500041", "expiredDate": "30-06-2027", "vat": 5,
    "receiptCode": "PO853982", "machineCode": "B7",
    "qrUnitRaw": "hộp", "unit": "hộp", "unitConvertRate": 2,
    "rfidUnit": "hộp", "rfidUnitConvertRate": 2,
    "rfidConfigScope": "GLOBAL",
    "rfidTagType": "temthuong",
    "circaUnits": [
      {"unitName": "viên", "convertRate": 1, "baseUnit": true,  "isRfidUnit": false},
      {"unitName": "hộp",  "convertRate": 2, "baseUnit": false, "isRfidUnit": true},
      {"unitName": "túi",  "convertRate": 1, "baseUnit": false, "isRfidUnit": false}
    ],
    "countQr": 1,
    "isNotFound": false, "isRfidUnitMissing": false, "isUnitUnmapped": false,
    "canGenerate": true, "warnings": null,
    "circaReceiptCode": "PO01967622", "circaSellerCode": "CIRCA", "circaSku": "CIRCA.9DCBBCV8",
    "circaUnitCode": "I7p1JuMa", "circaUnitName": "hộp",
    "printUnit": "hộp", "printUnitConvertRate": 2
  }]
}
```

App chỉ đọc: `productId`, `productName`, `rfidTagType`, `rfidUnit`, `machineCode`, `isNotFound`
(xem `ApiClient.parse`).

Ý nghĩa quan trọng:
- `rfidTagType`: `temthuong` | `temgap`. Khi Circa không có cấu hình (`isNotFound: true`) server
  trả mặc định `temgap`.
- `countQr = round(unitConvertRate / rfidUnitConvertRate)`, kẹp trong 1…1000 — số tem cần dán.
- Khi có cảnh báo: vẫn 200/`OK` nhưng envelope có `errorCode` = cảnh báo nặng nhất; danh sách đầy đủ
  ở `data[0].warnings` (`MISSING_MACHINE_CODE`, `CIRCA_PRODUCT_NOT_FOUND`, `MISSING_RFID_UNIT`,
  `QR_UNIT_NOT_MAPPED`, `INVALID_UNIT_CONVERT_RATE`, `INVALID_RFID_CONVERT_RATE`,
  `COUNT_QR_FALLBACK`, `QR_UNIT_SMALLER_THAN_RFID_UNIT`, `MISSING_RECEIPT_CODE`).

Theo người dùng, gọi API này **chậm và tốn hạ tầng** — lý do app tồn tại. Thời gian server đo được
trong ví dụ: ~204 ms (`X-Execution-Time`), chưa tính mạng kho.

## 2. Generate (GHI — app KHÔNG dùng)

`POST /api/v1/circa/rfid-qr` — cùng body, cần user token, **không idempotent**: mỗi lần gọi sinh
mã QR con mới (`qrCodes`, `qrCircaUrls`). Không gọi endpoint này từ app trừ khi người dùng yêu cầu rõ.
Ví dụ trong tài liệu cho thấy các mã sinh cùng một lần có chung tiền tố timestamp + máy in:
`1757563200000MC011`, `…MC012`, `…MC013` — cơ sở cho tính năng "suy ra theo lô in".

## Mã lỗi (envelope `errorCode`)

| errorCode | HTTP | Ý nghĩa |
|---|---|---|
| MISSING_QR_CODE | 400 | `qr` rỗng |
| INVALID_QR_CODE | 400 | mã không tồn tại / đã vô hiệu |
| MISSING_QR_UNIT | 400 | mã cha không có đơn vị |
| MISSING_QR_PRODUCT | 400 | mã cha không có product id |
| MISSING_PRODUCT_INFO | 400 | thiếu/0 `customerId` |
| QUANTITY_LIMITED | 400 | cần > 1000 mã |
| MISSING_MACHINE_CODE | 400 | (generate) mã cha thiếu máy in |
| CIRCA_RFID_INFO_FAILED | 500 | Circa lỗi — thử lại |
| GENERATE_QR_CODE_FAILED | 500 | (generate) dịch vụ QR từ chối |
| GENERATE_QR_CODE_NOT_ENOUGH | 500 | (generate) sinh thiếu mã |

App tự thêm các mã nội bộ: `NETWORK` (không kết nối được), `BAD_RESPONSE` (JSON lỗi),
`HTTP_<code>` (body rỗng), `NO_PRODUCT` (OK nhưng thiếu `productId`).

---

# C. Nextwaves Public API (`api-circa.nextwaves.app`) — "Tồn NW" từ v1.2.0

Nguồn: brief nội bộ "Tồn Nextwaves theo thẻ EPC" (16/11/2026, kèm code mẫu Node). Brief viết cho
một backend giữ khóa; app này không có backend nên **gọi thẳng từ PDA** (người dùng chọn 17/11/2026).
Khóa riêng cho APK, **nhúng lúc build** từ `BuiltinKeys.java` (gitignored, không commit; APK có khóa
không đưa lên GitHub); QR cài đặt / màn Cài đặt có thể đè bằng khóa khác.

## Xác thực

`Authorization: Bearer nwc_pk_…:nwc_sk_…` — mỗi tenant một khóa. Base URL cố định trong app
(`NextwavesClient.BASE_URL` = `https://api-circa.nextwaves.app/api/v1/public`); QR cài đặt chỉ đổi
được khóa / kho, không đổi được nơi nhận khóa. Khóa sai → HTTP 401.

## Kho

Kho NW là UUID riêng, không có trong dữ liệu Circa. App ghép theo **tên kho Circa** đang chọn
(`NextwavesClient.warehouseFor`): có chữ `LUMINA` → Lumina `3f7bd541-7370-4ce0-9048-231cf061e91a`,
`FLORITA` → Florita `eb5e2b4f-6f92-413b-90ef-6be1730b94c0`. Kho khác: nhập UUID trong Cài đặt
(`nw_warehouse_id`) hoặc QR cài đặt (`nwWarehouseId`), hoặc thêm vào `warehouseFor`.

## Đếm số thẻ EPC còn tồn (NW không có endpoint public trả thẳng con số này)

| Bước | Endpoint (GET) | App đọc |
|---|---|---|
| 1. Circa id → sản phẩm NW | `/products/by-external/{circaProductId}` (số trần `1467`; `CIRCA-1467` trả 404) | `id` (UUID), `productIdentifier`, `title`, `baseUnitOfMeasure`. 404 = sản phẩm chưa có trên NW |
| 2. Tổng theo số lượng | `/stock/summary?productId={uuid}&warehouseId={wh}` | `rfidStock` (tổng **đơn vị cơ sở** trên các thẻ, không phải số thẻ), `systemStock`, `untaggedStock` |
| 3. Sổ kho | `/stock/ledger?productId=&warehouseId=&limit=1000&afterLedgerNumber=N` | `{data:[{warehouseId, quantity, epcs[]}], nextAfter}`; lặp với `afterLedgerNumber=nextAfter` đến khi `data` rỗng |
| 4. Từng thẻ (chỉ khi 3 lệch 2) | `/integrations/circa/epcs/{epc}` | `status == "active"` và `warehouseId == wh` → còn tồn, `baseQuantity` |

Thuật toán bước 3 (`NextwavesClient.parseLedgerPage` + `Ledger`): mỗi dòng của đúng kho chia đều
`quantity` (dương nhập, âm xuất/bán) cho các EPC trong dòng; làm tròn 3 chữ số; EPC có số dư > 0 là
còn tồn. `epcCount` = số EPC đó, `epcQuantity` = tổng số dư. Thẻ bán lẻ một phần vẫn là 1 thẻ.
Lệch `rfidStock` quá 0,01 → bước 4 (4 luồng song song; bỏ qua nếu sổ kho có hơn 200 EPC, khi đó
app ghi "(chưa khớp NW)").

Điểm bất thường (theo brief, đã được người viết brief kiểm tra):
- Thiếu `afterLedgerNumber` → trả 200 dòng **mới nhất** dạng mảng trần; `offset/page/from/sort/
  actionType` bị bỏ qua; `limit` tối đa 1000.
- `/tags?productId=…` bỏ qua bộ lọc; `/stock/rfid/…`, `/stock/epc-breakdown/…` chỉ nhận token người
  dùng, không nhận khóa public.
- Gọi dồn → 429; app thử lại sau 1 s, 2 s, 3 s… tối đa 6 lần. Timeout mỗi call: kết nối 8 s, đọc 20 s.

Hiển thị: "Tồn NW: 31 thẻ · 93 cái" (đơn vị = `baseUnitOfMeasure`). Cache 60 s theo (kho NW, sản
phẩm) như Tồn POS; sản phẩm 404 thì 10 phút sau mới hỏi lại; LÀM MỚI bỏ qua cache.

Bảng test của brief (số liệu 16/11/2026 ~23:50, thay đổi theo thời gian; điều cần đạt là
`epcQuantity == rfidStock`): 1467 Florita 1 thẻ/1.104 viên · 1467 Lumina 0 · 1890 Florita 10/10 ·
10871 Lumina 31/93 cái · 2013079 Lumina 146/1.460 · 1066747 Lumina 80/800 · 93933 Lumina 150/150 que ·
999999999 → không có trên NW.
