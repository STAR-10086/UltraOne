package com.star.ultraone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 悬浮窗：在 Google One 界面上直接查询 offer 并一键开档 */
public class OverlayService extends Service implements LogBus.Sink {

    private static final int BG = 0xF0151820;
    private static final int CARD = 0xFF21262F;
    private static final int TXT = 0xFFE8EAF0;
    private static final int DIM = 0xFF8A91A3;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int GREEN = 0xFF3DDC84;

    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams lp;
    private TextView out;
    private TextView status;
    private ScrollView scroll;
    private boolean collapsed = false;
    private int expandedHeight;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startAsForeground();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        expandedHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.62);
        show();
        LogBus.addSink(this);
        EvtBridge.attach(this);
    }

    private void startAsForeground() {
        String ch = "ultraone";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "UltraOne", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("UltraOne 悬浮窗运行中")
                .setContentText("回到 Google One 即可操作 offer")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
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
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
        lp = new WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(14);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.22);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(BG, 18));
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setElevation(dp(12));

        // 标题栏（拖动 + 折叠 + 关闭）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(GREEN);
        dot.setTextSize(10);
        dot.setPadding(0, 0, dp(6), 0);
        head.addView(dot);

        final TextView title = new TextView(this);
        title.setText("UltraOne");
        title.setTextColor(TXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tp);

        TextView fold = new TextView(this);
        fold.setText("▾");
        fold.setTextColor(DIM);
        fold.setTextSize(15);
        fold.setPadding(dp(10), 0, dp(10), 0);
        fold.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCollapse(); }
        });
        head.addView(fold);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(DIM);
        close.setTextSize(15);
        close.setPadding(dp(6), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopSelf(); }
        });
        head.addView(close);
        root.addView(head);

        status = new TextView(this);
        status.setText("未连接 · 先在 LSPosed 勾选 Google One");
        status.setTextColor(DIM);
        status.setTextSize(10);
        status.setPadding(0, dp(2), 0, dp(6));
        root.addView(status);

        // 日志区
        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTextColor(Color.parseColor("#C9D1D9"));
        out.setTextSize(10);
        out.setTypeface(Typeface.MONOSPACE);
        out.setLineSpacing(dp(2), 1f);
        out.setText(LogBus.dump().length() == 0 ? "点「查询全部」扫描隐藏 offer" : LogBus.dump());
        out.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(out);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150));
        root.addView(scroll, sp);

        // 第一排：查询 / 状态 / 清空
        LinearLayout row0 = new LinearLayout(this);
        row0.setOrientation(LinearLayout.HORIZONTAL);
        row0.setPadding(0, dp(8), 0, 0);
        row0.addView(btn("查询全部 offer", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.send(OverlayService.this, "query", null, null, false, null);
                setStatus("已请求扫描全部 SKU…");
            }
        }));
        row0.addView(btn("取 token", CARD, GREEN, new View.OnClickListener() {
            public void onClick(View v) { LogBus.fetchToken(OverlayService.this); }
        }));
        row0.addView(btn("清空", CARD, DIM, new View.OnClickListener() {
            public void onClick(View v) { LogBus.clear(); out.setText(""); }
        }));
        root.addView(row0);

        // 方案按钮：横向滚动
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(0, dp(8), 0, 0);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (final Skus.Preset p : Skus.PRESETS) {
            TextView b = new TextView(this);
            b.setText(p.title);
            b.setTextSize(11);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(9), dp(12), dp(9));
            b.setBackground(round(p.steps.get(0).upgrade ? 0xFF2E7D5B : CARD, 10));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LogBus.runPreset(OverlayService.this, p.id);
                    setStatus("已请求：" + p.title);
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.setMargins(0, 0, dp(6), 0);
            b.setLayoutParams(bp);
            strip.addView(b);
        }
        hs.addView(strip);
        root.addView(hs);

        TextView hint = new TextView(this);
        hint.setText("按住标题栏拖动 · 按钮在 Google One 前台时生效");
        hint.setTextColor(Color.parseColor("#5A6474"));
        hint.setTextSize(9);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        root.addView(hint);

        head.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int ox, oy;
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
        try { wm.addView(panel, lp); } catch (Throwable t) { android.util.Log.e(Main.TAG, "addView " + t); }
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        if (scroll != null) scroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapsed) setStatus("已收起（点 ▾ 展开）");
    }

    private TextView btn(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setBackground(round(bg, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void setStatus(final String s) {
        if (status == null) return;
        status.post(new Runnable() { public void run() { status.setText(s); } });
    }

    @Override public void onLine(final String line) {
        if (out == null) return;
        if (line.startsWith("计费服务")) setStatus(line);
        out.post(new Runnable() {
            public void run() {
                out.setText(LogBus.dump());
                if (scroll != null) scroll.post(new Runnable() {
                    public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    @Override public void onDestroy() {
        LogBus.removeSink(this);
        EvtBridge.detach(this);
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) { }
        panel = null;
        super.onDestroy();
    }
}
