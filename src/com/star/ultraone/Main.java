package com.star.ultraone;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage {

    public static final String TAG = "UltraOne";
    public static final String PKG = "com.google.android.apps.subscriptions.red";
    public static final String MOD = "com.star.ultraone";
    public static final String CMD = "com.star.ultraone.CMD";
    public static final String EVT = "com.star.ultraone.EVT";
    static final String[] SKUS = { "g1.play.20tb", "g1.play.ai.pro", "g1.30tb" };

    static Context ctx;
    static ClassLoader cl;
    static Object client;
    static Object activity;
    static volatile Object pd20, pd30;
    static volatile String tk20, tk30, off20, off30;
    static volatile boolean ready;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        log("loaded in " + lp.processName);
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { activity = p.thisObject; }
            });
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ctx = (Context) p.thisObject;
                    listen();
                    autoOverlay();
                    new Thread(new Runnable() { public void run() { connect(); } }).start();
                }
            });
        } catch (Throwable t) { log("hook err " + Log.getStackTraceString(t)); }
    }

    static void autoOverlay() {
        try {
            Intent i = new Intent();
            i.setClassName(MOD, MOD + ".OverlayService");
            ctx.startForegroundService(i);
            log("overlay service requested");
        } catch (Throwable t) { log("autoOverlay failed " + t); }
    }

    static void listen() {
        try {
            IntentFilter f = new IntentFilter(CMD);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String cmd = i.getStringExtra("cmd");
                    log("cmd=" + cmd);
                    if ("query".equals(cmd)) query();
                    else if ("subscribe".equals(cmd)) subscribe(i.getStringExtra("which"));
                    else if ("state".equals(cmd)) emit("20TB=" + (pd20 != null ? off20 : "无") + " 30TB=" + (pd30 != null ? off30 : "无"));
                }
            };
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            log("receiver registered");
        } catch (Throwable t) { log("listen err " + Log.getStackTraceString(t)); }
    }

    static ClassLoader loader() throws Exception {
        if (cl != null) return cl;
        File apkFile = new File("/data/local/tmp/module_521.apk");
        if (apkFile.exists()) {
            // Android 14+ 不允许从可写目录加载 dex，/data/local/tmp 里的只读文件可以
            cl = new DexClassLoader(apkFile.getAbsolutePath(), ctx.getCodeCacheDir().getAbsolutePath(), null, ctx.getClassLoader());
            emit("loader=tmp");
        } else {
            // 内存加载，不落盘（API 26+）
            byte[] data = readAsset("billing.dex");
            cl = new dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(data), ctx.getClassLoader());
            emit("loader=memory " + data.length + "B");
        }
        return cl;
    }

    static byte[] readAsset(String name) throws Exception {
        String apk = ctx.getPackageManager().getApplicationInfo(MOD, 0).sourceDir;
        AssetManager am = AssetManager.class.newInstance();
        Method add = AssetManager.class.getMethod("addAssetPath", String.class);
        add.invoke(am, apk);
        InputStream is = am.open(name);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    static void copy(File a, File b) throws Exception {
        InputStream is = new java.io.FileInputStream(a);
        FileOutputStream fos = new FileOutputStream(b);
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); is.close();
    }

    static void connect() {
        try {
            ClassLoader l = loader();
            Class<?> BC = l.loadClass("com.android.billingclient.api.BillingClient");
            Class<?> PUL = l.loadClass("com.android.billingclient.api.PurchasesUpdatedListener");
            Object pul = Proxy.newProxyInstance(l, new Class<?>[]{ PUL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) { emit("purchase " + (a != null && a.length > 0 ? a[0] : "")); return null; }
            });
            Object b = BC.getMethod("newBuilder", Context.class).invoke(null, ctx);
            Class<?> Builder = l.loadClass("com.android.billingclient.api.BillingClient$Builder");
            Builder.getMethod("setListener", PUL).invoke(b, pul);
            try { Builder.getMethod("enablePendingPurchases").invoke(b); } catch (Throwable ignored) { }
            client = Builder.getMethod("build").invoke(b);
            Class<?> SLL = l.loadClass("com.android.billingclient.api.BillingClientStateListener");
            Object sll = Proxy.newProxyInstance(l, new Class<?>[]{ SLL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if ("onBillingSetupFinished".equals(m.getName())) {
                        int code = -1;
                        try { code = (Integer) a[0].getClass().getMethod("getResponseCode").invoke(a[0]); } catch (Throwable t) { }
                        ready = code == 0;
                        emit("billing setup code=" + code);
                    } else { ready = false; emit("billing disconnected"); }
                    return null;
                }
            });
            client.getClass().getMethod("startConnection", SLL).invoke(client, sll);
            emit("connecting");
        } catch (Throwable t) { emit("connect err " + t); log(Log.getStackTraceString(t)); }
    }

    static void query() {
        try {
            if (client == null || !ready) { connect(); emit("not ready, reconnecting"); return; }
            ClassLoader l = loader();
            Class<?> QPDP = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams");
            Class<?> QB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Builder");
            Class<?> Product = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product");
            Class<?> PB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product$Builder");
            Class<?> PDL = l.loadClass("com.android.billingclient.api.ProductDetailsResponseListener");
            List<Object> prods = new ArrayList<Object>();
            for (String sku : SKUS) {
                Object pb = Product.getMethod("newBuilder").invoke(null);
                PB.getMethod("setProductId", String.class).invoke(pb, sku);
                PB.getMethod("setProductType", String.class).invoke(pb, "subs");
                prods.add(PB.getMethod("build").invoke(pb));
            }
            Object qb = QPDP.getMethod("newBuilder").invoke(null);
            QB.getMethod("setProductList", List.class).invoke(qb, prods);
            Object params = QB.getMethod("build").invoke(qb);
            Object pdl = Proxy.newProxyInstance(l, new Class<?>[]{ PDL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) { dump(a[0], a[1]); return null; }
            });
            client.getClass().getMethod("queryProductDetailsAsync", QPDP, PDL).invoke(client, params, pdl);
            emit("query sent");
        } catch (Throwable t) { emit("query err " + t); }
    }

    static String tierName(String pid) {
        if (pid.contains("20tb")) return "Ultra 5x · 20TB · 后续 $99.99/月";
        if (pid.contains("ai.pro")) return "Ultra 20x · 30TB · 后续 $199.99/月";
        return "30TB 另一档 · 后续 $149.99/月";
    }

    static String tierShort(String pid) {
        if (pid.contains("20tb")) return "20TB(5x)";
        if (pid.contains("ai.pro")) return "30TB(20x)";
        return "30TB(149档)";
    }

    static int rank(String oid) {
        if (oid == null || "null".equals(oid) || oid.isEmpty()) return 0;
        if (oid.contains("12mo")) return 12;
        if (oid.contains("6mo")) return 6;
        if (oid.contains("3mo")) return 3;
        if (oid.contains("1mo")) return 1;
        return 0;
    }

    static String periodText(String p) {
        if (p == null) return "";
        if ("P1Y".equals(p)) return "12 个月";
        if (p.startsWith("P") && p.endsWith("M")) return p.substring(1, p.length() - 1) + " 个月";
        if (p.startsWith("P") && p.endsWith("D")) return p.substring(1, p.length() - 1) + " 天";
        return p;
    }

    static String offerLabel(String oid) {
        if (oid == null || "null".equals(oid) || oid.isEmpty()) return "无试用";
        if (oid.contains("12mo")) return "12 个月免费";
        if (oid.contains("6mo")) return "6 个月免费";
        if (oid.contains("3mo")) return "3 个月免费";
        if (oid.contains("1mo")) return "1 个月免费";
        return oid;
    }

    static void dump(Object res, Object list) {
        try {
            int code = (Integer) res.getClass().getMethod("getResponseCode").invoke(res);
            int n = list == null ? 0 : ((List<?>) list).size();
            if (code != 0) { emit("查询失败 code=" + code); return; }
            emit("──────── 查询结果 共 " + n + " 个商品 ────────");
            for (Object pd : (List<?>) list) {
                String pid = String.valueOf(pd.getClass().getMethod("getProductId").invoke(pd));
                emit("");
                emit("■ " + tierName(pid));
                emit("   " + pid);
                Object offs = pd.getClass().getMethod("getSubscriptionOfferDetails").invoke(pd);
                if (offs == null) { emit("   （无 offer）"); continue; }
                List<Object[]> items = new ArrayList<Object[]>();
                for (Object o : (List<?>) offs) {
                    String oid = String.valueOf(o.getClass().getMethod("getOfferId").invoke(o));
                    String tok = String.valueOf(o.getClass().getMethod("getOfferToken").invoke(o));
                    Object phases = o.getClass().getMethod("getPricingPhases").invoke(o);
                    List<?> pl = (List<?>) phases.getClass().getMethod("getPricingPhaseList").invoke(phases);
                    String micros = "", price = "", period = "";
                    for (Object ph : pl) {
                        micros = String.valueOf(ph.getClass().getMethod("getPriceAmountMicros").invoke(ph));
                        price = String.valueOf(ph.getClass().getMethod("getFormattedPrice").invoke(ph));
                        period = String.valueOf(ph.getClass().getMethod("getBillingPeriod").invoke(ph));
                        break;
                    }
                    items.add(new Object[]{ oid, "0".equals(micros), price, period, tok });
                }
                java.util.Collections.sort(items, new java.util.Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) {
                        return rank((String) b[0]) - rank((String) a[0]);
                    }
                });
                for (Object[] it : items) {
                    String oid = (String) it[0];
                    boolean free = (Boolean) it[1];
                    String price = (String) it[2];
                    String period = (String) it[3];
                    String tok = (String) it[4];
                    String label = offerLabel("null".equals(oid) ? null : oid);
                    emit((free ? "   ★ " : "     ") + label + "  " + price
                            + "  ·  首期 " + periodText(period));
                    if (free && rank(oid) == 12) {
                        if (pid.contains("20tb")) { pd20 = pd; tk20 = tok; off20 = oid; }
                        else if (pid.contains("ai.pro")) { pd30 = pd; tk30 = tok; off30 = oid; }
                    }
                }
            }
            emit("");
            emit("══ 可开档位 ══");
            emit("   20TB (Ultra 5x) ：" + (pd20 != null ? "★ 12 个月免费" : "无 12 个月免费"));
            emit("   30TB (Ultra 20x)：" + (pd30 != null ? "★ 12 个月免费" : "无 12 个月免费"));
            emit("");
        } catch (Throwable t) { emit("解析失败 " + t); }
    }

    static void subscribe(String want) {
        try {
            Object _pd = "30".equals(want) ? pd30 : pd20;
            String _tk = "30".equals(want) ? tk30 : tk20;
            String _off = "30".equals(want) ? off30 : off20;
            if (_pd == null) { emit("没有 " + ("30".equals(want) ? "30TB" : "20TB") + " 的 12 个月免费 offer，先点查询"); return; }
            final Object pd = _pd;
            final String tok = _tk;
            final Activity act = (Activity) activity;
            if (act == null) { emit("no activity"); return; }
            emit("launching " + ("30".equals(want) ? "30TB" : "20TB") + "/" + _off);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        ClassLoader loader = pd.getClass().getClassLoader();
                        Class<?> PDP = loader.loadClass("com.android.billingclient.api.BillingFlowParams$ProductDetailsParams");
                        Class<?> PD = loader.loadClass("com.android.billingclient.api.ProductDetails");
                        Class<?> PDB = loader.loadClass("com.android.billingclient.api.BillingFlowParams$ProductDetailsParams$Builder");
                        Object pb = PDP.getMethod("newBuilder").invoke(null);
                        PDB.getMethod("setProductDetails", PD).invoke(pb, pd);
                        PDB.getMethod("setOfferToken", String.class).invoke(pb, tok);
                        Object pdp = PDB.getMethod("build").invoke(pb);
                        Class<?> BFP = loader.loadClass("com.android.billingclient.api.BillingFlowParams");
                        Class<?> BFB = loader.loadClass("com.android.billingclient.api.BillingFlowParams$Builder");
                        Object b = BFP.getMethod("newBuilder").invoke(null);
                        List<Object> one = new ArrayList<Object>(); one.add(pdp);
                        BFB.getMethod("setProductDetailsParamsList", List.class).invoke(b, one);
                        Object params = BFB.getMethod("build").invoke(b);
                        Object res = client.getClass().getMethod("launchBillingFlow", Activity.class, BFP).invoke(client, act, params);
                        emit("launched " + res);
                    } catch (Throwable t) { emit("subscribe err " + t); }
                }
            });
        } catch (Throwable t) { emit("subscribe outer " + t); }
    }

    static void emit(String msg) {
        log(msg);
        try {
            Intent i = new Intent(EVT);
            i.setPackage(MOD);
            i.putExtra("msg", msg == null ? "" : msg);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) { }
    }

    static void log(String s) {
        Log.i(TAG, s);
        try { XposedBridge.log("[" + TAG + "] " + s); } catch (Throwable ignored) { }
    }
}
