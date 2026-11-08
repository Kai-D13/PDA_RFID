# API Circa RFID QR — tóm tắt cho dev

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

## 1. Check (chỉ đọc) — app đang dùng endpoint này

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
