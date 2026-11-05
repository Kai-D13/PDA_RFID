#!/usr/bin/env python3
"""Chuyển file Excel cấu hình tem RFID sang file dữ liệu local của app.

Dùng:
    pip install openpyxl
    python3 tools/xlsx_to_tsv.py rfid_client_san_pham.xlsx app/src/main/assets/products.dat

Đầu ra là TSV nén gzip (đặt tên .dat khi đóng gói vào app, vì công cụ build tự giải nén file .gz trong assets), mỗi dòng: product_id <TAB> type_tag <TAB> rfid_unit_name <TAB> product_name
(type_tag rỗng = "Chưa cấu hình"). Đây cũng là định dạng app lưu nội bộ, nên file .tsv.gz
này có thể nạp thẳng vào app qua Cài đặt -> Nhập dữ liệu.

Thêm --json out.json nếu muốn xuất kèm bản JSON (mảng object) để xem/đối chiếu.
"""
import argparse
import gzip
import json
import sys

import openpyxl

ALIASES = {
    "id": {"productid", "id"},
    "type": {"typetag", "rfidtagtype", "tagtype"},
    "label": {"typetaglabel"},
    "unit": {"rfidunitname", "rfidunit", "unit"},
    "name": {"productname", "name"},
}
LABEL_TO_TYPE = {"tem thường": "temthuong", "tem gập": "temgap"}


def norm(h):
    return "".join(ch for ch in str(h or "").lower() if ch.isalnum())


def clean(v):
    if v is None:
        return ""
    return " ".join(str(v).replace("\t", " ").split())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("xlsx")
    ap.add_argument("out")
    ap.add_argument("--json")
    a = ap.parse_args()

    ws = openpyxl.load_workbook(a.xlsx, read_only=True).active
    rows = ws.iter_rows(values_only=True)
    header = [norm(h) for h in next(rows)]
    col = {}
    for key, names in ALIASES.items():
        for i, h in enumerate(header):
            if h in names and key not in col:
                col[key] = i
    if "id" not in col:
        sys.exit("Không tìm thấy cột product_id trong dòng tiêu đề: %s" % header)

    out, skipped, seen = [], 0, set()
    for r in rows:
        def get(k):
            i = col.get(k)
            return r[i] if i is not None and i < len(r) else None

        try:
            pid = int(float(str(get("id")).strip()))
        except (TypeError, ValueError):
            skipped += 1
            continue
        t = clean(get("type")).lower()
        if not t:
            t = LABEL_TO_TYPE.get(clean(get("label")).lower(), "")
        if pid in seen:
            skipped += 1
            continue
        seen.add(pid)
        out.append((pid, t, clean(get("unit")), clean(get("name"))))

    out.sort()
    with gzip.open(a.out, "wt", encoding="utf-8", compresslevel=9) as f:
        for row in out:
            f.write("\t".join(str(x) for x in row) + "\n")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump([{"product_id": p, "type_tag": t or None, "rfid_unit_name": u or None,
                        "product_name": n} for p, t, u, n in out], f, ensure_ascii=False)

    stats = {}
    for _, t, _, _ in out:
        stats[t or "(chưa cấu hình)"] = stats.get(t or "(chưa cấu hình)", 0) + 1
    print("Đã ghi %d sản phẩm vào %s (bỏ qua %d dòng). %s" % (len(out), a.out, skipped, stats))


if __name__ == "__main__":
    main()
