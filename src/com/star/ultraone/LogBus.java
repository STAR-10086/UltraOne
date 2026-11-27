package com.star.ultraone;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/** 模块进程内的日志总线：OverlayService / MainActivity 共用一份输出 */
public final class LogBus {

    public interface Sink { void onLine(String line); }

    private static final int MAX = 400;
    private static final List<String> LINES = new ArrayList<String>();
    private static final List<Sink> SINKS = new ArrayList<Sink>();

    private LogBus() {}

    public static synchronized void append(String line) {
        if (line == null) return;
        LINES.add(line);
        while (LINES.size() > MAX) LINES.remove(0);
        for (Sink s : new ArrayList<Sink>(SINKS)) {
            try { s.onLine(line); } catch (Throwable ignored) { }
        }
    }

    public static synchronized void clear() { LINES.clear(); }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void addSink(Sink s) { if (!SINKS.contains(s)) SINKS.add(s); }

    public static synchronized void removeSink(Sink s) { SINKS.remove(s); }

    /** 向 Google One 进程投递命令 */
    public static void send(Context ctx, String cmd, String sku, String offer, boolean upgrade, String preset) {
        Intent i = new Intent(Main.CMD);
        i.setPackage(Main.PKG);
        i.putExtra("cmd", cmd);
        if (sku != null) i.putExtra("sku", sku);
        if (offer != null) i.putExtra("offer", offer);
        if (preset != null) i.putExtra("preset", preset);
        i.putExtra("upgrade", upgrade);
        try { ctx.sendBroadcast(i); } catch (Throwable t) { append("发送失败 " + t); }
    }
}
