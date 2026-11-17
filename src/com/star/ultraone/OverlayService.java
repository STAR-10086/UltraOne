package com.star.ultraone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class OverlayService extends Service {

    private static final int BG = 0xF214161C;
    private static final int CARD = 0xFF1B1F28;
    private static final int TXT = 0xFFE8EAF0;
    private static final int DIM = 0xFF8A91A3;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int GREEN = 0xFF4ADE80;

    private WindowManager wm;
    private LinearLayout panel;
    private TextView out;
    private TextView status;
    private ScrollView scroll;
    private final StringBuilder sb = new StringBuilder();
    private BroadcastReceiver rx;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotif(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, buildNotif());
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        show();
        register();
    }

    private Notification buildNotif() {
        String ch = "ultraone";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "UltraOne", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("UltraOne 运行中")
                .setContentText("Google One offer 查询器")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build();
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private void show() {
        if (panel != null) return;
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.45);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(BG, 18));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setElevation(dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(ACCENT);
        dot.setTextSize(11);
        dot.setPadding(0, 0, dp(8), 0);
        head.addView(dot);

        TextView title = new TextView(this);
        title.setText("UltraOne");
        title.setTextColor(TXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(DIM);
        close.setTextSize(16);
        close.setPadding(dp(8), 0, dp(4), 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hide(); }
        });
        head.addView(close);
        root.addView(head);

        status = new TextView(this);
        status.setText("连接中…");
        status.setTextColor(DIM);
        status.setTextSize(11);
        status.setPadding(0, dp(2), 0, dp(8));
        root.addView(status);

        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTextColor(Color.parseColor("#C9D1D9"));
        out.setTextSize(11);
        out.setTypeface(Typeface.MONOSPACE);
        out.setLineSpacing(dp(3), 1f);
        out.setText("点「查询」读取 Play 结算库里的 offer");
        scroll.addView(out);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210));
        root.addView(scroll, sp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(btn("查询", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) { send("query", null); }
        }));
        row.addView(btn("开 20TB", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { send("subscribe", "20"); }
        }));
        row.addView(btn("开 30TB", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { send("subscribe", "30"); }
        }));
        root.addView(row);

        TextView drag = new TextView(this);
        drag.setText("按住此标题栏可拖动");
        drag.setTextColor(Color.parseColor("#556074"));
        drag.setTextSize(9);
        drag.setGravity(Gravity.CENTER);
        drag.setPadding(0, dp(8), 0, 0);
        root.addView(drag);

        title.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int ox, oy;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); ox = lp.x; oy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + (int) (e.getRawX() - dx);
                        lp.y = oy + (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(panel, lp); } catch (Throwable ignored) { }
                        return true;
                }
                return false;
            }
        });

        panel = root;
        try { wm.addView(panel, lp); } catch (Throwable t) { android.util.Log.i(Main.TAG, "addView " + t); }
    }

    private TextView btn(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(9), 0, dp(9));
        b.setBackground(round(bg, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void hide() {
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) { }
        panel = null;
    }

    private void send(String cmd, String which) {
        Intent i = new Intent(Main.CMD);
        i.setPackage(Main.PKG);
        i.putExtra("cmd", cmd);
        if (which != null) i.putExtra("which", which);
        sendBroadcast(i);
        log("→ " + ("query".equals(cmd) ? "查询 offer" : "拉起 " + ("30".equals(which) ? "30TB" : "20TB") + " 收银台"));
    }

    private void register() {
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { log(i.getStringExtra("msg")); }
        };
        IntentFilter f = new IntentFilter(Main.EVT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);
    }

    private void log(final String s) {
        if (s == null) return;
        android.util.Log.i(Main.TAG, s);
        if (s.startsWith("billing setup")) status.setText(s.replace("billing setup ", "计费服务 "));
        if (s.startsWith("connecting")) status.setText("正在连接 Play 计费服务…");
        sb.append(s).append('\n');
        if (sb.length() > 6000) sb.delete(0, sb.length() - 6000);
        if (out != null) out.post(new Runnable() {
            public void run() {
                out.setText(sb.toString());
                if (scroll != null) scroll.post(new Runnable() {
                    public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    @Override public void onDestroy() {
        hide();
        try { if (rx != null) unregisterReceiver(rx); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
