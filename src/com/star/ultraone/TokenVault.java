package com.star.ultraone;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 在模块自己的进程里用 su 读 Play 的 library.db，解析出订阅 purchaseToken。
 *
 * 为什么放在模块进程而不是注入的 Google One 进程：
 *   - Magisk / KernelSU 的 su 授权按 uid 记，UltraOne 授权一次就永久有效
 *   - Google One 的 uid 每次都弹框，且叠加流程中途弹框会打断收银台
 *   - 模块进程读完整库再解析，不依赖 BillingClient 的 queryPurchases 回调
 *
 * 读法：su 把 library.db 及其 -wal / -shm 一起拷到模块私有目录，再本地打开。
 */
public final class TokenVault {

    private static final String PREFS = "ultraone_token";
    private static final String K_TOKEN = "token";
    private static final String K_ACCOUNT = "account";
    private static final String K_ORDER = "order";
    private static final String K_UNTIL = "validUntil";
    private static final String K_AT = "fetchedAt";
    private static final String K_DOCID = "docId";

    /** 缓存有效期：叠加后 Play 写库有延迟，所以只在同一次点击内复用 */
    private static final long CACHE_MS = 4000;

    private static long lastFetch;
    private static String lastToken;
    private static String lastErr;

    private TokenVault() {}

    /** 一次取 token 的结果 */
    public static final class Result {
        public final String token;
        public final String account;
        public final String orderId;
        public final String validUntil;
        public final String docId;
        public final String error;
        public Result(String token, String account, String orderId, String validUntil, String docId, String error) {
            this.token = token; this.account = account; this.orderId = orderId;
            this.validUntil = validUntil; this.docId = docId; this.error = error;
        }
        public boolean ok() { return token != null && token.length() >= 40; }
    }

    /** 从缓存或设备取一次 */
    public static synchronized Result fetch(Context ctx) {
        long now = System.currentTimeMillis();
        if (lastToken != null && now - lastFetch < CACHE_MS) {
            SharedPreferences p = prefs(ctx);
            return new Result(lastToken, p.getString(K_ACCOUNT, null), p.getString(K_ORDER, null),
                    p.getString(K_UNTIL, null), p.getString(K_DOCID, null), null);
        }
        Result r = readFromDevice(ctx);
        if (r.ok()) {
            lastToken = r.token;
            lastFetch = now;
            lastErr = null;
            prefs(ctx).edit()
                    .putString(K_TOKEN, r.token)
                    .putString(K_ACCOUNT, r.account)
                    .putString(K_ORDER, r.orderId)
                    .putString(K_UNTIL, r.validUntil)
                    .putString(K_DOCID, r.docId)
                    .putLong(K_AT, now)
                    .apply();
        } else {
            lastErr = r.error;
        }
        return r;
    }

    /** 强制重新取（跳过缓存） */
    public static synchronized Result refresh(Context ctx) {
        lastToken = null;
        lastFetch = 0;
        return fetch(ctx);
    }

    public static String lastError() { return lastErr; }

    /** 上次成功取到的（可能是几小时前的），仅用于界面展示 */
    public static Result cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String t = p.getString(K_TOKEN, null);
        if (t == null) return new Result(null, null, null, null, null, "还没取过 token");
        return new Result(t, p.getString(K_ACCOUNT, null), p.getString(K_ORDER, null),
                p.getString(K_UNTIL, null), p.getString(K_DOCID, null), null);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ─────────────────────────── 读设备 ───────────────────────────

    private static Result readFromDevice(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "playdb");
        try {
            if (!dir.exists() && !dir.mkdirs()) return fail("无法创建缓存目录");
            // 清掉上次的副本
            deleteQuietly(new File(dir, "library.db"));
            deleteQuietly(new File(dir, "library.db-wal"));
            deleteQuietly(new File(dir, "library.db-shm"));

            String base = "/data/data/com.android.vending/databases/library.db";
            String d = dir.getAbsolutePath();
            // cp 连同 -wal / -shm 一起拿，否则刚叠加完的 token 还在 WAL 里没落主库
            String cmd = "cp " + base + " " + d + "/library.db 2>/dev/null; "
                    + "cp " + base + "-wal " + d + "/library.db-wal 2>/dev/null; "
                    + "cp " + base + "-shm " + d + "/library.db-shm 2>/dev/null; "
                    + "chmod 666 " + d + "/library.db* 2>/dev/null; "
                    + "chmod 777 " + d + " 2>/dev/null; "
                    + "restorecon -R " + d + " 2>/dev/null; echo done";

            String out = runSu(cmd);
            if (out == null) return fail("su 不可用：Magisk/KernelSU 里给 UltraOne 授权 root");
            if (!out.contains("done")) return fail("su 执行失败：" + out.trim());

            File db = new File(dir, "library.db");
            if (!db.exists() || db.length() < 1024) {
                // 兜底：走 stdout 直接把字节搬过来（SELinux 不让 root 写 app 目录时用这条）
                if (!pullViaStdout(base, db)) {
                    return fail("读不到 library.db（" + (db.exists() ? db.length() + " B" : "不存在")
                            + "）· 确认 root 已授权且 Play 商店已登录");
                }
            }

            Result r = parse(db);
            if (!r.ok() && db.length() >= 1024) {
                // 可能是 WAL 没应用成功，删掉再试一次纯主库
                deleteQuietly(new File(dir, "library.db-wal"));
                deleteQuietly(new File(dir, "library.db-shm"));
                r = parse(db);
            }
            return r;
        } catch (Throwable t) {
            return fail("读库异常 " + t);
        } finally {
            deleteQuietly(new File(dir, "library.db-wal"));
            deleteQuietly(new File(dir, "library.db-shm"));
        }
    }

    /** 兜底：su cat 出原始字节，由 app 自己写文件（SELinux label 一定正确） */
    private static boolean pullViaStdout(String remote, File dst) {
        Process p = null;
        java.io.FileOutputStream fos = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + remote});
            final Process proc = p;
            Thread killer = new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(15000); proc.destroy(); } catch (Throwable ignored) { }
                }
            });
            killer.setDaemon(true);
            killer.start();

            // stderr 必须单独抽干，否则管道写满会卡死
            final InputStream es = p.getErrorStream();
            Thread drain = new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] b = new byte[2048];
                        while (es.read(b) > 0) { }
                    } catch (Throwable ignored) { }
                }
            });
            drain.setDaemon(true);
            drain.start();

            fos = new java.io.FileOutputStream(dst);
            InputStream is = p.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            p.waitFor();
            return dst.length() > 1024;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 执行 su -c，返回 stdout；su 不可用返回 null */
    private static String runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final InputStream is = p.getInputStream();
            final StringBuilder sb = new StringBuilder();
            final Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
                    } catch (Throwable ignored) { }
                }
            });
            reader.start();
            // su 弹授权框时可能一直不返回，给 12 秒
            final Process proc = p;
            Thread killer = new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(12000); proc.destroy(); } catch (Throwable ignored) { }
                }
            });
            killer.setDaemon(true);
            killer.start();
            try { p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Throwable ignored) { p.waitFor(); }
            reader.join(1500);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 解析 ownership 表，挑出最匹配的订阅 */
    private static Result parse(File dbFile) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            // 有些版本列名不同，先看有哪些列
            List<String> cols = new ArrayList<String>();
            Cursor info = db.rawQuery("pragma table_info(ownership)", null);
            while (info.moveToNext()) cols.add(info.getString(1));
            info.close();
            if (cols.isEmpty()) return fail("library.db 里没有 ownership 表");

            String dataCol = pick(cols, "inapp_purchase_data", "inapp_purchase_data_");
            String docCol = pick(cols, "doc_id", "docid");
            String accCol = pick(cols, "account", "account_id");
            String untilCol = pick(cols, "subs_valid_until_time", "valid_until_time");
            if (dataCol == null) return fail("ownership 表没有 inapp_purchase_data 列（列：" + cols + "）");

            StringBuilder sel = new StringBuilder("select ");
            sel.append(dataCol);
            sel.append(docCol != null ? ", " + docCol : ", ''");
            sel.append(accCol != null ? ", " + accCol : ", ''");
            sel.append(untilCol != null ? ", " + untilCol : ", 0");
            sel.append(" from ownership where ").append(dataCol).append(" is not null");
            c = db.rawQuery(sel.toString(), null);

            Result best = null;
            long bestScore = Long.MIN_VALUE;
            while (c.moveToNext()) {
                String data = c.getString(0);
                if (data == null || data.length() < 40) continue;
                String docId = c.getString(1);
                String account = c.getString(2);
                long until = 0;
                try { until = c.getLong(3); } catch (Throwable ignored) { }

                String token = null, orderId = null;
                try {
                    JSONObject j = new JSONObject(data);
                    token = j.optString("purchaseToken", null);
                    orderId = j.optString("orderId", null);
                    if (until == 0) until = j.optLong("validUntilTs", 0);
                } catch (Throwable ignored) {
                    // 有些记录是 base64 / 双层 JSON，退一步用正则抓
                    token = grab(data, "purchaseToken");
                    orderId = grab(data, "orderId");
                }
                if (token == null || token.length() < 40) continue;

                long score = scoreOf(docId, until);
                if (score > bestScore) {
                    bestScore = score;
                    best = new Result(token, account, orderId,
                            until > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                                    java.util.Locale.US).format(new java.util.Date(until)) : null,
                            docId, null);
                }
            }
            if (best == null) return fail("库里没有带 purchaseToken 的订阅记录（可能还没开通）");
            return best;
        } catch (Throwable t) {
            return fail("解析失败 " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
            try { if (db != null) db.close(); } catch (Throwable ignored) { }
        }
    }

    /** 排序权重：Pro 5TB 叠加档 > 其他 g1 订阅 > 别的 */
    private static long scoreOf(String docId, long until) {
        long s = 0;
        if (docId != null) {
            if (docId.contains("g1.2tb.ai.subs_variant")) s += 10_000_000_000L;
            else if (docId.contains("g1.2tb.ai")) s += 9_000_000_000L;
            else if (docId.contains("g1.play.20tb") || docId.contains("g1.play.ai.pro")) s += 8_000_000_000L;
            else if (docId.contains("g1.2tb") || docId.contains("20tb")) s += 7_000_000_000L;
            else if (docId.startsWith("g1.")) s += 1_000_000_000L;
        }
        // 到期越晚越优先（同一 doc 有多条历史记录时取最新）
        s += Math.min(until / 1000, 900_000_000L);
        return s;
    }

    private static String pick(List<String> cols, String... names) {
        for (String n : names) for (String c : cols) if (c.equalsIgnoreCase(n)) return c;
        for (String n : names) for (String c : cols) if (c.toLowerCase().contains(n.toLowerCase())) return c;
        return null;
    }

    private static String grab(String s, String key) {
        int i = s.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = s.indexOf(':', i);
        if (c < 0) return null;
        int q1 = s.indexOf('"', c);
        if (q1 < 0) return null;
        int q2 = s.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return s.substring(q1 + 1, q2);
    }

    private static void deleteQuietly(File f) {
        try { if (f.exists()) f.delete(); } catch (Throwable ignored) { }
    }

    private static Result fail(String msg) { return new Result(null, null, null, null, null, msg); }
}
