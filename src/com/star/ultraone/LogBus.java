package com.star.ultraone;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** 模块进程内的日志总线：OverlayService / MainActivity 共用，并落盘防进程被杀后丢 */
public final class LogBus {

    public interface Sink { void onLine(String line); }

    private static final int MAX = 500;
    private static final int MAX_FILE = 256 * 1024;
    private static final List<String> LINES = new ArrayList<String>();
    private static final List<Sink> SINKS = new ArrayList<Sink>();
    private static File logFile;

    private LogBus() {}

    /** 首次使用时把磁盘上的历史日志读回来 */
    public static synchronized void init(Context c) {
        if (logFile != null || c == null) return;
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            logFile = new File(dir, "ultraone.log");
            if (!logFile.exists() || logFile.length() == 0) return;
            RandomAccessFile raf = new RandomAccessFile(logFile, "r");
            long len = raf.length();
            long from = Math.max(0, len - 64 * 1024);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            raf.close();
            String all = new String(buf, "UTF-8");
            if (from > 0) {
                int nl = all.indexOf('\n');
                if (nl > 0) all = all.substring(nl + 1);
            }
            for (String line : all.split("\n")) {
                if (line.length() > 0 && LINES.size() < MAX) LINES.add(line);
            }
        } catch (Throwable ignored) { }
    }

    public static synchronized void append(String line) {
        if (line == null) return;
        LINES.add(line);
        while (LINES.size() > MAX) LINES.remove(0);
        writeFile(line);
        for (Sink s : new ArrayList<Sink>(SINKS)) {
            try { s.onLine(line); } catch (Throwable ignored) { }
        }
    }

    private static void writeFile(String line) {
        if (logFile == null) return;
        try {
            if (logFile.length() > MAX_FILE) {
                // 超了就砍掉前一半
                RandomAccessFile raf = new RandomAccessFile(logFile, "rw");
                long len = raf.length();
                byte[] tail = new byte[(int) (len / 2)];
                raf.seek(len - tail.length);
                raf.readFully(tail);
                raf.setLength(0);
                raf.write(tail);
                raf.close();
            }
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
    }

    public static synchronized void clear() {
        LINES.clear();
        try { if (logFile != null && logFile.exists()) logFile.delete(); } catch (Throwable ignored) { }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized List<String> lines() { return new ArrayList<String>(LINES); }

    public static synchronized void addSink(Sink s) { if (!SINKS.contains(s)) SINKS.add(s); }

    public static synchronized void removeSink(Sink s) { SINKS.remove(s); }

    /** 向 Google One 进程投递命令 */
    public static void send(Context ctx, String cmd, String sku, String offer, boolean upgrade, String preset) {
        send(ctx, cmd, sku, offer, upgrade, preset, null);
    }

    public static void send(Context ctx, String cmd, String sku, String offer,
                            boolean upgrade, String preset, String token) {
        Intent i = new Intent(Main.CMD);
        i.setPackage(Main.PKG);
        i.putExtra("cmd", cmd);
        if (sku != null) i.putExtra("sku", sku);
        if (offer != null) i.putExtra("offer", offer);
        if (preset != null) i.putExtra("preset", preset);
        if (token != null) i.putExtra("token", token);
        i.putExtra("upgrade", upgrade);
        try { ctx.sendBroadcast(i); } catch (Throwable t) { append("发送失败 " + t); }
    }

    /** 打印「能拿到什么免费订阅」汇总 */
    public static void summary(Context ctx) {
        send(ctx, "summary", null, null, false, null);
    }

    /** 一键开某个 SKU/offer（汇总里点「开这个」用） */
    public static void open(final Context ctx, final String sku, final String offer) {
        if (sku == null) return;
        append("→ 开 " + sku + (offer == null ? "" : " [" + offer + "]"));
        final boolean upgrade = offer != null && (offer.contains("6months")
                || offer.contains("3months") || offer.contains("2months"));
        if (!upgrade) {
            send(ctx, "sub", sku, offer, false, null, null);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                TokenVault.Result r = TokenVault.fetch(ctx);
                if (r.ok()) {
                    append("叠加用 token · " + safe(r.account) + " · " + r.token.length() + " 字符");
                    send(ctx, "sub", sku, offer, true, null, r.token);
                } else {
                    append("取 token 失败：" + r.error + "，退回进程内查询");
                    send(ctx, "sub", sku, offer, true, null, null);
                }
            }
        }, "ultraone-token").start();
    }

    /**
     * 一键方案：需要叠加时先在模块进程把 token 取好，再带进广播。
     * 这样叠加全程在手机上完成，不需要电脑 adb 推 token 文件。
     */
    public static void runPreset(final Context ctx, final String presetId) {
        final Skus.Preset p = Skus.preset(presetId);
        if (p == null) { append("未知方案 " + presetId); return; }
        boolean needToken = false;
        for (Skus.Step s : p.steps) if (s.upgrade) needToken = true;

        append("▶ " + p.title);
        if (!needToken) {
            send(ctx, "preset", null, null, false, presetId);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                append("叠加需要现有订阅 token，正在本机读取…");
                TokenVault.Result r = TokenVault.fetch(ctx);
                if (r.ok()) {
                    append("token 已取到 · " + safe(r.account) + " · 到期 " + safe(r.validUntil)
                            + " · " + r.token.length() + " 字符");
                    send(ctx, "preset", null, null, false, presetId, r.token);
                } else {
                    append("取 token 失败：" + r.error);
                    append("退回 Google One 进程内查询（可能拿不到 token）");
                    send(ctx, "preset", null, null, false, presetId, null);
                }
            }
        }, "ultraone-token").start();
    }

    /** 单独取一次 token，结果只打印 */
    public static void fetchToken(final Context ctx) {
        send(ctx, "state", null, null, false, null);
        new Thread(new Runnable() {
            public void run() {
                append("正在读取 Play 库（root 路径）…");
                TokenVault.Result r = TokenVault.refresh(ctx);
                if (r.ok()) {
                    append("✓ 账号 " + safe(r.account));
                    append("✓ 订单 " + safe(r.orderId));
                    append("✓ 到期 " + safe(r.validUntil));
                    append("✓ SKU  " + safe(r.docId));
                    append("✓ token " + r.token.length() + " 字符 · "
                            + r.token.substring(0, Math.min(24, r.token.length())) + "…");
                    append("叠加时点方案按钮即可，会直接用这个 token");
                } else {
                    append("✗ root 路径：" + r.error);
                    append("  不影响叠加：点方案按钮时会用 app 回调里捕获的 token（零 root）");
                }
            }
        }, "ultraone-token").start();
    }

    private static String safe(String s) { return s == null || s.length() == 0 ? "-" : s; }
}
