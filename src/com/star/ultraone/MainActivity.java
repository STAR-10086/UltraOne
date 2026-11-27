package com.star.ultraone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块主界面：权限引导 + 启动悬浮窗 + 方案列表 + 实时日志 */
public class MainActivity extends Activity implements LogBus.Sink {

    private static final int BG = 0xFF0B0D12;
    private static final int CARD = 0xFF171B23;
    private static final int TXT = 0xFFE8EAF0;
    private static final int DIM = 0xFF8A91A3;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int WARN = 0xFFF5A524;
    private static final int GREEN = 0xFF3DDC84;

    private TextView logView;
    private TextView permView;
    private ScrollView logScroll;
    private LinearLayout summaryBox;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LogBus.init(this);
        build();
        LogBus.addSink(this);
        EvtBridge.attach(this);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPerm();
        renderSummary();
        if (logView != null) logView.setText(LogBus.dump().length() == 0 ? "（暂无输出）" : LogBus.dump());
    }

    @Override protected void onDestroy() {
        LogBus.removeSink(this);
        EvtBridge.detach(this);
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(32), dp(16), dp(16));

        ScrollView page = new ScrollView(this);
        page.addView(root);

        TextView title = new TextView(this);
        title.setText("UltraOne");
        title.setTextColor(TXT);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Google One 隐藏免费试用 offer · LSPosed 模块 v" + Main.VERSION);
        sub.setTextColor(DIM);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        // 状态卡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 14));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("环境检查");
        cardTitle.setTextColor(TXT);
        cardTitle.setTextSize(13);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(cardTitle);

        permView = new TextView(this);
        permView.setTextColor(DIM);
        permView.setTextSize(11);
        permView.setLineSpacing(dp(3), 1f);
        permView.setPadding(0, dp(8), 0, dp(10));
        card.addView(permView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(btn("授予悬浮窗", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { openOverlayPermission(); }
        }));
        row1.addView(btn("省电无限制", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) { openBatterySettings(); }
        }));
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        row2.addView(btn("启动悬浮窗", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { startOverlay(); }
        }));
        row2.addView(btn("停止悬浮窗", CARD, DIM, new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                LogBus.append("悬浮窗已停止");
            }
        }));
        card.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(6), 0, 0);
        row3.addView(btn("打开 Google One", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) { openGoogleOne(); }
        }));
        row3.addView(btn("能拿什么", ACCENT, Color.WHITE, new View.OnClickListener() {
            public void onClick(View v) { LogBus.summary(MainActivity.this); }
        }));
        row3.addView(btn("取订阅 token", CARD, GREEN, new View.OnClickListener() {
            public void onClick(View v) { LogBus.fetchToken(MainActivity.this); }
        }));
        row3.addView(btn("打开 LSPosed", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
                if (i == null) LogBus.append("没装 LSPosed 管理器");
                else startActivity(i);
            }
        }));
        card.addView(row3);

        root.addView(card, lp(dp(0), dp(12), dp(0), 0));

        // 步骤说明
        TextView steps = new TextView(this);
        steps.setText("使用顺序\n"
                + "1. LSPosed 里启用 UltraOne，作用域勾选「Google One」\n"
                + "2. 强制停止 Google One（设置→应用→Google One→强行停止）\n"
                + "3. 回本页点「启动悬浮窗」，再把省电策略设为「无限制」\n"
                + "4. 打开 Google One 并确认当前账号\n"
                + "5. 悬浮窗点「查询全部 offer」看有哪些免费档\n"
                + "6. 点方案按钮直接拉起 Play 收银台");
        steps.setTextColor(DIM);
        steps.setTextSize(11);
        steps.setLineSpacing(dp(4), 1f);
        steps.setPadding(dp(4), dp(14), dp(4), dp(10));
        root.addView(steps);

        // 能拿什么（查询后自动填）
        TextView sTitle = new TextView(this);
        sTitle.setText("这个账号能白拿什么");
        sTitle.setTextColor(TXT);
        sTitle.setTextSize(13);
        sTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sTitle.setPadding(dp(2), dp(4), 0, dp(8));
        root.addView(sTitle);

        summaryBox = new LinearLayout(this);
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(summaryBox, lp(dp(0), 0, dp(0), dp(6)));

        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.addView(btn("扫描并汇总", GREEN, Color.BLACK, new View.OnClickListener() {
            public void onClick(View v) {
                LogBus.send(MainActivity.this, "query", null, null, false, null);
                LogBus.append("开始扫描 143 个候选商品…");
            }
        }));
        sRow.addView(btn("重新汇总", CARD, TXT, new View.OnClickListener() {
            public void onClick(View v) { LogBus.summary(MainActivity.this); }
        }));
        root.addView(sRow, lp(dp(0), 0, dp(0), dp(16)));

        // 方案列表
        TextView pTitle = new TextView(this);
        pTitle.setText("一键方案");
        pTitle.setTextColor(TXT);
        pTitle.setTextSize(13);
        pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setPadding(dp(2), 0, 0, dp(8));
        root.addView(pTitle);

        for (final Skus.Preset p : Skus.PRESETS) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(round(CARD, 12));
            item.setPadding(dp(14), dp(11), dp(14), dp(11));
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LogBus.runPreset(MainActivity.this, p.id);
                    LogBus.append("→ " + p.title);
                }
            });

            TextView t = new TextView(this);
            t.setText(p.title);
            t.setTextColor(TXT);
            t.setTextSize(12);
            item.addView(t);

            TextView d = new TextView(this);
            d.setText(p.desc);
            d.setTextColor(DIM);
            d.setTextSize(10);
            d.setPadding(0, dp(3), 0, 0);
            item.addView(d);

            root.addView(item, lp(dp(0), dp(6), dp(0), 0));
        }

        // 日志
        TextView lTitle = new TextView(this);
        lTitle.setText("输出日志");
        lTitle.setTextColor(TXT);
        lTitle.setTextSize(13);
        lTitle.setTypeface(Typeface.DEFAULT_BOLD);
        lTitle.setPadding(dp(2), dp(16), 0, dp(6));
        root.addView(lTitle);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#C9D1D9"));
        logView.setTextSize(10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setText(LogBus.dump().length() == 0 ? "（暂无输出）" : LogBus.dump());
        logScroll.addView(logView);
        root.addView(logScroll, lp(dp(0), dp(240), dp(0), 0));

        root.addView(btn("清空日志", CARD, DIM, new View.OnClickListener() {
            public void onClick(View v) { LogBus.clear(); logView.setText("（暂无输出）"); }
        }), lp(dp(0), dp(8), dp(0), dp(20)));

        setContentView(page);
    }

    /** 把存下来的汇总渲染成卡片，按档位分组，点一行直接开 */
    private void renderSummary() {
        if (summaryBox == null) return;
        summaryBox.removeAllViews();
        final java.util.List<SummaryStore.Row> rows = SummaryStore.load(this);
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没扫描。点「扫描并汇总」看看这个账号能拿到哪些免费订阅。");
            empty.setTextColor(DIM);
            empty.setTextSize(11);
            empty.setPadding(dp(4), dp(4), dp(4), dp(4));
            summaryBox.addView(empty);
            return;
        }

        String curTier = null;
        for (final SummaryStore.Row r : rows) {
            if (!r.tier.equals(curTier)) {
                curTier = r.tier;
                TextView h = new TextView(this);
                h.setText("【" + curTier + "】");
                h.setTextColor(ACCENT);
                h.setTextSize(12);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(dp(4), dp(10), dp(4), dp(5));
                summaryBox.addView(h);
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackground(round(CARD, 10));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setGravity(Gravity.CENTER_VERTICAL);

            TextView m = new TextView(this);
            m.setText(r.months > 0 ? r.months + " 个月" : "免费");
            m.setTextColor(GREEN);
            m.setTextSize(13);
            m.setTypeface(Typeface.DEFAULT_BOLD);
            m.setMinWidth(dp(56));
            card.addView(m);

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            card.addView(mid, mp);

            TextView sku = new TextView(this);
            sku.setText(shortSku(r.sku));
            sku.setTextColor(TXT);
            sku.setTextSize(11);
            mid.addView(sku);

            TextView off = new TextView(this);
            off.setText(r.offer == null || r.offer.equals("null") ? "基础档" : r.offer);
            off.setTextColor(DIM);
            off.setTextSize(9);
            mid.addView(off);

            TextView go = new TextView(this);
            go.setText("开这个");
            go.setTextColor(ACCENT);
            go.setTextSize(11);
            go.setPadding(dp(10), dp(4), dp(4), dp(4));
            card.addView(go);

            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LogBus.open(MainActivity.this, r.sku, r.offer);
                    LogBus.append("→ " + r.sku + " [" + r.offer + "]");
                }
            });
            summaryBox.addView(card, lp(dp(0), 0, dp(0), dp(5)));
        }
    }

    private String shortSku(String s) {
        if (s == null) return "?";
        if (s.startsWith("g1.")) s = s.substring(3);
        return s;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int bo) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, bo);
        return p;
    }

    private TextView btn(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        b.setBackground(round(bg, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void refreshPerm() {
        if (permView == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean battery = isBatteryOk();
        boolean one = isInstalled("com.google.android.apps.subscriptions.red");
        boolean lsposed = isInstalled("org.lsposed.manager");
        StringBuilder sb = new StringBuilder();
        sb.append(overlay ? "✓ " : "✗ ").append("悬浮窗权限\n");
        sb.append(battery ? "✓ " : "! ").append("电池省电策略（需无限制）\n");
        sb.append(one ? "✓ " : "✗ ").append("Google One 已安装\n");
        sb.append(lsposed ? "✓ " : "! ").append("LSPosed 管理器\n");
        sb.append("作用域需手动勾选：com.google.android.apps.subscriptions.red");
        permView.setText(sb.toString());
        permView.setTextColor(overlay ? DIM : WARN);
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Throwable t) { return false; }
    }

    private boolean isBatteryOk() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private void openOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) { LogBus.append("打不开悬浮窗权限页 " + t); }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent();
            if (Build.VERSION.SDK_INT >= 23) {
                i.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
            } else {
                i.setAction(Settings.ACTION_SETTINGS);
            }
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }
            catch (Throwable t2) { LogBus.append("打不开电池设置 " + t); }
        }
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            LogBus.append("还没给悬浮窗权限，先点「授予悬浮窗」");
            openOverlayPermission();
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        LogBus.append("悬浮窗已启动，回到 Google One 操作");
        moveTaskToBack(true);
    }

    private void openGoogleOne() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.subscriptions.red");
            if (i == null) { LogBus.append("没装 Google One"); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { LogBus.append("打开 Google One 失败 " + t); }
    }

    @Override public void onLine(final String line) {
        if (logView == null) return;
        logView.post(new Runnable() {
            public void run() {
                logView.setText(LogBus.dump());
                if (logScroll != null) logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }
}
