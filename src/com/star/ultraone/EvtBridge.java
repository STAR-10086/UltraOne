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

    public static synchronized void detach(Context c) {
        if (refs > 0) refs--;
        if (refs > 0 || receiver == null) return;
        try { c.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        receiver = null;
    }
}
