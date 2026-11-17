package com.star.ultraone;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0E1014;
    private static final int CARD = 0xFF1B1F28;
    private static final int TXT = 0xFFE8EAF0;
    private static final int DIM = 0xFF8A91A3;
    private static final int ACCENT = 0xFF4C8DFF;

    private TextView logView;
    private final StringBuilder sb = new StringBuilder();
    private BroadcastReceiver rx;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(28), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("UltraOne");
        title.setTextColor(TXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Google One Ultra 试用 offer 读取器 · 模块需在 LSPosed 中勾选 Google One 作用域");
        sub.setTextColor(DIM);
        sub.setTextSize(11);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 卡片1：环境
        LinearLayout env = card();
        env.addView(section("环境"));
        LinearLayout e1 = row();
        e1.addView(btn("授予悬浮窗", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } else toast("已授权");
            }
        }));
        e1.addView(btn("启动悬浮窗", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) { toast("先授予悬浮窗权限"); return; }
                startForegroundService(new Intent(MainActivity.this, OverlayService.class));
                append("悬浮窗已启动，切到 Google One 查看");
            }
        }));
        env.addView(e1);
        root.addView(env, cardLp());

        // 卡片2：操作
        LinearLayout act = card();
        act.addView(section("操作"));
        LinearLayout a1 = row();
        a1.addView(btn("查询 offer", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) { send("query", null); }
        }));
        a1.addView(btn("开 20TB", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { send("subscribe", "20"); }
        }));
        a1.addView(btn("开 30TB", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { send("subscribe", "30"); }
        }));
        act.addView(a1);
        root.addView(act, cardLp());

        // 卡片3：日志
        LinearLayout logCard = card();
        logCard.addView(section("日志"));
        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#C9D1D9"));
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setText("就绪。\n先在 Play 里切到目标账号并绑卡，再点「查询 offer」。");
        sv.addView(logView);
        logCard.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.setMargins(0, 0, 0, dp(8));
        root.addView(logCard, llp);

        setContentView(root);

        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { append(i.getStringExtra("msg")); }
        };
        IntentFilter f = new IntentFilter(Main.EVT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, 16));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        return l;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(10);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView btn(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(10), 0, dp(10));
        b.setBackground(round(bg, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void send(String cmd, String which) {
        Intent i = new Intent(Main.CMD);
        i.setPackage(Main.PKG);
        i.putExtra("cmd", cmd);
        if (which != null) i.putExtra("which", which);
        sendBroadcast(i);
        append("→ " + ("query".equals(cmd) ? "查询 offer" : "拉起 " + ("30".equals(which) ? "30TB" : "20TB") + " 收银台"));
    }

    private void append(String s) {
        if (s == null) return;
        sb.append(s).append('\n');
        if (sb.length() > 8000) sb.delete(0, sb.length() - 8000);
        logView.setText(sb.toString());
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        try { if (rx != null) unregisterReceiver(rx); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
