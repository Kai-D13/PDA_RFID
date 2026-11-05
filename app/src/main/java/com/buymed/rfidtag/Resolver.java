package com.buymed.rfidtag;

/**
 * Scan text -> tag type. Order of attempts, cheapest first:
 *  1. product id in the scanned text            (offline)
 *  2. QR code seen before                       (offline, local cache)
 *  3. another code from the same mint batch     (offline, only if enabled)
 *  4. check API, once per code, result cached   (online)
 * The tag type itself always comes from the local table, except when the API was called:
 * then the API's answer (which is per customer) is shown and a mismatch with the local table
 * is flagged so the data file can be corrected.
 */
public final class Resolver {
    private final ProductRepository repo;
    private final ScanCache cache;
    private final Settings settings;

    public Resolver(ProductRepository repo, ScanCache cache, Settings settings) {
        this.repo = repo;
        this.cache = cache;
        this.settings = settings;
    }

    public LookupResult resolve(String raw) {
        long start = System.currentTimeMillis();
        LookupResult r = new LookupResult();
        QrParser.Parsed p = QrParser.parse(raw);
        r.input = p.text;

        if (p.kind == QrParser.EMPTY) {
            return fail(r, start, "Mã quét rỗng");
        }
        if (p.kind == QrParser.CONFIG) {
            try {
                int n = settings.applyConfig(p.code);
                r.status = LookupResult.CONFIG;
                r.source = "QR cài đặt: đã áp dụng " + n + " mục";
                r.input = "RFIDCFG";
            } catch (Exception e) {
                fail(r, start, "QR cài đặt không hợp lệ: " + e.getMessage());
                r.input = "RFIDCFG";
            }
            r.elapsedMs = System.currentTimeMillis() - start;
            return r;
        }
        if (!repo.awaitReady(15000)) {
            return fail(r, start, "Dữ liệu local chưa tải xong, thử lại sau vài giây");
        }

        ApiClient.Response api = null;
        long pid;
        if (p.kind == QrParser.PRODUCT_ID) {
            pid = p.productId;
            r.source = "Product ID";
        } else {
            r.code = p.code;
            pid = cache.productIdForCode(p.code);
            r.source = "Local · mã đã quét trước đó";
            if (pid < 0 && settings.batchInference()) {
                pid = cache.productIdForBatch(p.code);
                r.source = "Local · suy ra từ lô in cùng máy";
            }
            if (pid < 0) {
                if (!settings.online()) {
                    return fail(r, start, "Mã QR không chứa product_id và chưa có trong cache. "
                            + "Bật tra cứu online trong Cài đặt, hoặc nhập product ID.");
                }
                if (settings.token().isEmpty()) {
                    return fail(r, start, "Chưa có token API. Vào Cài đặt để nhập token "
                            + "(hoặc quét QR cài đặt), hoặc nhập product ID.");
                }
                api = ApiClient.check(settings, p.code);
                if (!api.ok) {
                    String msg = api.message == null || api.message.isEmpty() ? "" : " — " + api.message;
                    return fail(r, start, "API lỗi " + api.errorCode + msg);
                }
                pid = api.productId;
                cache.remember(p.code, pid, api.machineCode);
                r.source = "API Buymed (" + api.elapsedMs + " ms) · đã lưu cache";
            }
        }

        r.productId = pid;
        Product local = repo.get(pid);
        if (api != null) {
            r.tagType = api.rfidTagType;
            r.productName = api.productName != null ? api.productName : local != null ? local.name : null;
            r.rfidUnit = api.rfidUnit != null ? api.rfidUnit : local != null ? local.rfidUnit : null;
            r.status = LookupResult.statusForTag(r.tagType);
            StringBuilder w = new StringBuilder();
            if (api.isNotFound) w.append("Circa chưa cấu hình sản phẩm, API dùng mặc định. ");
            String localTag = local == null ? null : local.typeTag;
            if (local == null) {
                w.append("Sản phẩm chưa có trong dữ liệu local.");
            } else if (localTag == null ? r.tagType != null : !localTag.equalsIgnoreCase(String.valueOf(r.tagType))) {
                w.append("Dữ liệu local ghi ").append(localTag == null ? "chưa cấu hình" : localTag)
                        .append(", khác API. Nên cập nhật file dữ liệu.");
            }
            if (w.length() > 0) r.warning = w.toString().trim();
        } else if (local == null) {
            r.status = LookupResult.NOT_IN_DATA;
            r.warning = "Product " + pid + " không có trong dữ liệu local (" + repo.size() + " SP).";
        } else {
            r.tagType = local.typeTag;
            r.productName = local.name;
            r.rfidUnit = local.rfidUnit;
            r.status = LookupResult.statusForTag(local.typeTag);
        }
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }

    private static LookupResult fail(LookupResult r, long start, String error) {
        r.status = LookupResult.ERROR;
        r.error = error;
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }
}
