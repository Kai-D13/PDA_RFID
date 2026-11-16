package com.buymed.rfidtag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scan text -> tag type. Order of attempts, cheapest first:
 *  1. product id in the scanned text            (offline)
 *  2. QR code seen before                       (offline, local cache)
 *  3. another code from the same mint batch     (offline, only if enabled)
 *  4. Circa POS QR lookup, result cached        (online, needs Circa key + store)
 *  5. Buymed check API, result cached           (online fallback, needs Buymed token)
 * The tag type itself always comes from the local table, except when the Buymed API was called:
 * then its answer (which is per customer) is shown and a mismatch with the local table is
 * flagged so the data file can be corrected.
 *
 * Stock and product details (images, pack size, unit conversions) are fetched separately by
 * {@link #fetchExtra} after the tag type is on screen, so a slow stock call never delays it.
 */
public final class Resolver {
    /** Scanning many cartons of one product in a row should not hit the stock API each time. */
    static final long STOCK_TTL_MS = 60000;
    /** A product whose details Circa does not have is not asked for again for this long. */
    static final long DETAIL_RETRY_MS = 10 * 60000;

    private final ProductRepository repo;
    private final ScanCache cache;
    private final Settings settings;
    private final Map<String, CircaClient.Stock> stockCache = Collections.synchronizedMap(new Lru<String, CircaClient.Stock>(200));
    private final Map<Long, CircaClient.Detail> detailCache = Collections.synchronizedMap(new Lru<Long, CircaClient.Detail>(300));
    private final Map<Long, Long> detailMiss = Collections.synchronizedMap(new Lru<Long, Long>(300));

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
        r.storeLabel = settings.storeLabel();

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
        CircaClient.QrInfo circa = null;
        long pid;
        if (p.kind == QrParser.PRODUCT_ID) {
            pid = p.productId;
            r.source = "Product ID";
        } else {
            r.code = p.code;
            pid = -1;
            ScanCache.Hit hit = cache.lookup(p.code);
            if (hit != null) {
                pid = hit.productId;
                r.sku = hit.sku;
                r.lot = hit.lot;
                r.expiry = hit.expiry;
                r.qrUnit = hit.unit;
                r.source = "Local · mã đã quét trước đó";
            }
            if (pid < 0 && settings.batchInference()) {
                pid = cache.productIdForBatch(p.code);
                r.source = "Local · suy ra từ lô in cùng máy";
            }
            if (pid < 0) {
                if (!settings.online()) {
                    return fail(r, start, "Mã QR không chứa product_id và chưa có trong cache. "
                            + "Bật tra cứu online trong Cài đặt, hoặc nhập product ID.");
                }
                String circaError = null;
                boolean offline = false;
                if (settings.canCallCirca()) {
                    // Set before the call so a failure is not labelled with a local step.
                    r.source = "Circa";
                    CircaClient.Reply<CircaClient.QrInfo> q = CircaClient.qr(CircaClient.Auth.of(settings), p.code);
                    if (q.ok) {
                        circa = q.value;
                        pid = circa.productId;
                        r.sku = circa.sku;
                        r.lot = circa.lot;
                        r.expiry = circa.expiry;
                        r.qrUnit = circa.unit;
                        r.images.addAll(circa.images);
                        cache.rememberCirca(p.code, circa);
                        // The total time is appended where the source is shown; a second figure confused operators.
                        r.source = "Circa · đã lưu cache";
                    } else {
                        circaError = q.message;
                        // No HTTP answer at all (no Internet, timeout, captive Wi-Fi): Buymed would
                        // fail the same way and double the wait, so do not try it.
                        offline = q.networkError;
                    }
                }
                if (pid < 0 && !offline && settings.canCallBuymed()) {
                    r.source = "API Buymed";
                    api = ApiClient.check(settings, p.code);
                    if (api.ok) {
                        pid = api.productId;
                        cache.remember(p.code, pid, api.machineCode);
                        r.source = "API Buymed · đã lưu cache";
                    } else {
                        String b = api.networkError ? api.message
                                : "API Buymed lỗi " + api.errorCode
                                + (api.message == null || api.message.isEmpty() ? "" : " — " + api.message);
                        return fail(r, start, (circaError != null ? circaError + "\n" : "") + b
                                + " Hoặc bấm ABC để nhập product ID.");
                    }
                }
                if (pid < 0) {
                    return fail(r, start, circaError != null ? circaError + " Hoặc bấm ABC để nhập product ID."
                            : missingSetupMessage());
                }
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
            r.productName = circa != null ? circa.productName : null;
            r.warning = "Product " + pid + " không có trong dữ liệu local (" + repo.size() + " SP), "
                    + "chưa biết loại tem. Nên cập nhật file dữ liệu.";
        } else {
            r.tagType = local.typeTag;
            r.productName = local.name;
            r.rfidUnit = local.rfidUnit;
            r.status = LookupResult.statusForTag(local.typeTag);
        }
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }

    private String missingSetupMessage() {
        if (!settings.hasCircaKey() && settings.token().isEmpty()) {
            return "Mã QR chưa có trong cache và chưa có khóa API Circa. Quét QR cài đặt hoặc vào Cài đặt, "
                    + "hoặc bấm ABC để nhập product ID.";
        }
        if (settings.hasCircaKey() && !settings.hasStore()) {
            return "Chưa chọn kho Circa. Bấm dòng \"Kho\" ở đầu màn hình để chọn, hoặc bấm ABC để nhập product ID.";
        }
        return "Không tra được mã QR. Hoặc bấm ABC để nhập product ID.";
    }

    private static LookupResult fail(LookupResult r, long start, String error) {
        r.status = LookupResult.ERROR;
        r.error = error;
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }

    // ------------------------------------------------------------------ stock + details

    /** Stock and product details for one result; applied to the result on the UI thread. */
    public static final class Extra {
        public CircaClient.Stock stock;
        public CircaClient.Detail detail;
        public String error;
    }

    /**
     * Fetches stock in the current store (cached {@link #STOCK_TTL_MS}, unless {@code force})
     * and product details (cached for the session). Blocking; call off the UI thread.
     */
    public Extra fetchExtra(long productId, boolean force) {
        Extra x = new Extra();
        if (!settings.online()) {
            x.error = "Đang tắt tra cứu online (Cài đặt)";
            return x;
        }
        if (!settings.hasCircaKey()) {
            x.error = "Chưa có khóa API Circa (Cài đặt)";
            return x;
        }
        if (!settings.hasStore()) {
            x.error = "Chưa chọn kho";
            return x;
        }
        CircaClient.Auth auth = CircaClient.Auth.of(settings);
        // Stock first: it is what the operator is waiting for.
        String key = auth.storeId + "/" + productId;
        CircaClient.Stock cached = stockCache.get(key);
        boolean networkDown = false;
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < STOCK_TTL_MS) {
            x.stock = cached;
        } else {
            CircaClient.Reply<CircaClient.Stock> s = CircaClient.stock(auth, productId);
            if (s.ok) {
                x.stock = s.value;
                stockCache.put(key, s.value);
            } else {
                x.error = s.message;
                networkDown = s.networkError;
            }
        }
        x.detail = detailCache.get(productId);
        Long missedAt = detailMiss.get(productId);
        boolean recentlyMissing = missedAt != null && System.currentTimeMillis() - missedAt < DETAIL_RETRY_MS;
        if (x.detail == null && !networkDown && !recentlyMissing) {
            CircaClient.Reply<CircaClient.Detail> d = CircaClient.detail(auth, productId);
            // Details are a nice-to-have; a failure here must not hide the stock.
            if (d.ok) {
                x.detail = d.value;
                detailCache.put(productId, d.value);
                detailMiss.remove(productId);
            } else if (!d.networkError) {
                // e.g. 404 for a product Circa does not have: do not ask again on every scan.
                detailMiss.put(productId, System.currentTimeMillis());
            }
        }
        return x;
    }

    /** Drops cached stock, e.g. after switching store. */
    public void clearStockCache() {
        stockCache.clear();
    }

    private static final class Lru<K, V> extends LinkedHashMap<K, V> {
        private final int max;

        Lru(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
        }
    }
}
