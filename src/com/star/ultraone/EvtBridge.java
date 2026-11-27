package com.star.ultraone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 接收 Google One 进程回传的 EVT 广播。
 *
 * 必须动态注册：EVT 来自另一个 uid，静态 receiver 在 exported=false 时收不到，
 * 而 exported=true 会让任意应用伪造日志。这里用引用计数保证只注册一次。
 */
public final class EvtBridge {

    private static BroadcastReceiver receiver;
    private static int refs;

    private EvtBridge() {}

    public static synchronized void attach(Context c) {
        if (c == null) return;
        refs++;
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                String msg = i.getStringExtra("msg");
                if (msg != null) LogBus.append(msg);
                String rows = i.getStringExtra("rows");
                if (rows != null) saveRows(ctx, rows);
            }
        };
        IntentFilter f = new IntentFilter(Main.EVT);
        try {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else c.registerReceiver(receiver, f);
        } catch (Throwable t) {
            receiver = null;
            LogBus.append("注册 EVT 失败 " + t);
        }
    }

    /** 结构化汇总存盘，界面下次打开就能直接画卡片 */
    static void saveRows(Context c, String json) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            java.util.List<SummaryStore.Row> rows = new java.util.ArrayList<SummaryStore.Row>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                SummaryStore.Row r = new SummaryStore.Row();
                r.tier = o.optString("tier", "");
                r.sku = o.optString("sku", "");
                r.offer = o.optString("offer", "");
                r.months = o.optInt("months", 0);
                rows.add(r);
            }
            SummaryStore.save(c, rows);
        } catch (Throwable t) { LogBus.append("保存汇总失败 " + t); }
    }

    public static synchronized void detach(Context c) {
        if (refs > 0) refs--;
        if (refs > 0 || receiver == null) return;
        try { c.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        receiver = null;
    }
}
