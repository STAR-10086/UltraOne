package com.star.ultraone;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 注入 Google One 进程，用 Play Billing 查询隐藏 offer 并直开收银台。
 *
 * 通信模型：
 *   模块进程 (com.star.ultraone) --CMD 广播--> Google One 进程
 *   Google One 进程 --EVT 广播--> 模块进程（悬浮窗显示）
 */
public class Main implements IXposedHookLoadPackage {

    public static final String TAG = "UltraOne";
    public static final String PKG = "com.google.android.apps.subscriptions.red";
    public static final String MOD = "com.star.ultraone";
    public static final String CMD = "com.star.ultraone.CMD";
    public static final String EVT = "com.star.ultraone.EVT";
    public static final String VERSION = "3.2";

    static Context ctx;
    static ClassLoader billing;
    static Object client;
    static Object activity;
    static volatile boolean ready;
    static volatile boolean connecting;
    static volatile boolean receiverOn;

    static final HashMap<String, Object> pdMap = new HashMap<String, Object>();
    static final HashMap<String, String> tkMap = new HashMap<String, String>();
    static final HashMap<String, String> offerMap = new HashMap<String, String>();
    static final HashMap<String, String> labelMap = new HashMap<String, String>();
    static final HashMap<String, Boolean> freeMap = new HashMap<String, Boolean>();
    static final HashMap<String, String> periodMap = new HashMap<String, String>();
    static final HashMap<String, String> tierMap = new HashMap<String, String>();
    static volatile int pendingBatches;
    static volatile boolean summaryScheduled;

    static Object keepRef;
    static String lastSku = "g1.2tb.ai.subs_variant";
    static long lastLaunch = 0;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        if (!PKG.equals(lp.processName)) { log("skip subprocess " + lp.processName); return; }
        log("inject v" + VERSION);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Context c = (Context) p.thisObject;
                        if (c == null) return;
                        ctx = c;
                        listen();
                        hookAppBilling(c.getClassLoader());
                        new Thread(new Runnable() {
                            public void run() { connect(); }
                        }, "ultraone-connect").start();
                    } catch (Throwable t) { log("application hook " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { activity = p.thisObject; }
            });
        } catch (Throwable t) { log("hook err " + Log.getStackTraceString(t)); }
    }

    // ─────────────────── 从 app 自己的 Billing 回调里捡 token ───────────────────
    //
    // 叠加续期最麻烦的是"拿当前订阅的 purchaseToken"。
    // 之前靠电脑 adb 读 Play 的 library.db 再 push 文件，这里改成两条路都自己走：
    //   1) hook app 自己的 BillingClient 回调（onPurchasesUpdated / onQueryPurchasesResponse）
    //      —— app 每次进 Google One 都会查订阅，回调里的 Purchase 就带 token，零 root
    //   2) 拿不到才退回 su 读库（见 TokenVault，在模块进程里做）
    // 购买完成后 Play 也会回调 onPurchasesUpdated，所以叠加完自动就有新 token。

    static volatile String harvested;
    static volatile long harvestedAt;
    static volatile String harvestedDoc;

    /** 探测：把 classloader 结构、billing 类真名、模块 apk 路径都打出来 */
    static void probe(ClassLoader appCl) {
        emit("═══ 探测开始 ═══");
        try {
            ClassLoader ml = Main.class.getClassLoader();
            emit("module CL = " + (ml == null ? "null" : ml.getClass().getName()));
            emit("app CL    = " + appCl.getClass().getName());
            dumpLoader("module", ml);
            dumpLoader("app", appCl);
        } catch (Throwable t) { emit("probe loader err " + t); }

        // 在 app 的 dex 里搜 billing / Purchase 相关类名
        try {
            java.util.TreeSet<String> hits = new java.util.TreeSet<String>();
            for (String cn : listClasses(appCl)) {
                String low = cn.toLowerCase();
                if (low.contains("billingclient") || low.endsWith(".purchase")
                        || low.contains("purchaselistener") || low.contains("productdetails")) {
                    hits.add(cn);
                }
            }
            emit("billing 相关类共 " + hits.size() + " 个：");
            int n = 0;
            for (String s : hits) {
                if (n++ > 40) { emit("  …"); break; }
                emit("  " + s);
            }
        } catch (Throwable t) { emit("probe classes err " + t); }

        // 对 Purchase 类 dump 方法名
        try {
            Class<?> pc = null;
            for (String cn : listClasses(appCl)) {
                if (cn.endsWith("Purchase") && cn.toLowerCase().contains("billing")) { pc = Class.forName(cn, false, appCl); break; }
            }
            if (pc != null) {
                emit("Purchase 类 = " + pc.getName());
                for (Method m : pc.getDeclaredMethods()) {
                    emit("   M " + m.getReturnType().getSimpleName() + " " + m.getName()
                            + "(" + m.getParameterCount() + ")");
                }
                for (Field f : pc.getDeclaredFields()) {
                    emit("   F " + f.getType().getSimpleName() + " " + f.getName());
                }
                // 构造器签名
                for (java.lang.reflect.Constructor<?> co : pc.getDeclaredConstructors()) {
                    emit("   C (" + co.getParameterCount() + ")");
                }
            } else emit("没找到 Purchase 类");
        } catch (Throwable t) { emit("probe purchase err " + t); }
        emit("═══ 探测结束 ═══");
    }

    static void dumpLoader(String tag, ClassLoader cl) {
        if (cl == null) { emit(tag + " = null"); return; }
        Class<?> c = cl.getClass();
        while (c != null && !c.getName().equals("dalvik.system.BaseDexClassLoader")) c = c.getSuperclass();
        if (c == null) { emit(tag + " 不是 BaseDexClassLoader（父链：" + chain(cl.getClass()) + "）"); return; }
        try {
            Field f = c.getDeclaredField("pathList");
            f.setAccessible(true);
            Object pl = f.get(cl);
            Field fe = pl.getClass().getDeclaredField("dexElements");
            fe.setAccessible(true);
            Object[] els = (Object[]) fe.get(pl);
            emit(tag + " dexElements = " + els.length);
            for (Object e : els) {
                try {
                    Field ff = e.getClass().getDeclaredField("path");
                    ff.setAccessible(true);
                    emit("   path = " + ff.get(e));
                } catch (Throwable t) { emit("   path? " + t); }
            }
        } catch (Throwable t) { emit(tag + " dump err " + t); }
    }

    static String chain(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        while (c != null) { sb.append(c.getName()).append(" < "); c = c.getSuperclass(); }
        return sb.toString();
    }

    /** 列出 classloader 里所有类名 */
    static java.util.List<String> listClasses(ClassLoader cl) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Class<?> c = cl.getClass();
        while (c != null && !c.getName().equals("dalvik.system.BaseDexClassLoader")) c = c.getSuperclass();
        if (c == null) return out;
        try {
            Field f = c.getDeclaredField("pathList");
            f.setAccessible(true);
            Object pl = f.get(cl);
            Field fe = pl.getClass().getDeclaredField("dexElements");
            fe.setAccessible(true);
            Object[] els = (Object[]) fe.get(pl);
            for (Object e : els) {
                try {
                    Field df = e.getClass().getDeclaredField("dexFile");
                    df.setAccessible(true);
                    Object dex = df.get(e);
                    if (dex == null) continue;
                    Method entries = dex.getClass().getMethod("entries");
                    java.util.Enumeration<String> en = (java.util.Enumeration<String>) entries.invoke(dex);
                    while (en.hasMoreElements()) out.add(en.nextElement());
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * Google One 把 Billing 库做了 R8 混淆：只保留了 Purchase / SkuDetails /
     * ProxyBillingActivity 三个类名，方法名全变成 a()、b()，字段变成 a/b/c/d。
     * 所以不能按 getPurchaseToken 找，改 hook Purchase 的构造器——
     * 任何 Purchase 对象创建都必经这里，构造器第一个参数就是 Play 返回的 JSON。
     */
    static void hookAppBilling(ClassLoader appCl) {
        try {
            final Class<?> purchase = Class.forName("com.android.billingclient.api.Purchase", false, appCl);
            emit("Purchase 构造器数 = " + purchase.getDeclaredConstructors().length);
            XposedBridge.hookAllConstructors(purchase, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        harvestPurchase(p.thisObject);
                    } catch (Throwable t) { log("harvest ctor: " + t); }
                }
            });
            // 顺便 hook 它那个唯一的实例方法 a()，app 每次取 sku 列表也会经过
            for (Method m : purchase.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!(m.getReturnType() == java.util.ArrayList.class
                        || m.getReturnType() == List.class)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { harvestPurchase(p.thisObject); } catch (Throwable ignored) { }
                    }
                });
                emit("已 hook Purchase." + m.getName() + "()");
            }
            emit("已 hook Purchase 构造器");
        } catch (Throwable t) { log("hook Purchase: " + t); }
    }

    /** 从 Purchase 实例里把 token 挖出来（字段被混淆，按类型找 JSONObject） */
    static void harvestPurchase(Object purchase) {
        if (purchase == null) return;
        org.json.JSONObject json = null;
        try {
            for (Field f : purchase.getClass().getDeclaredFields()) {
                if (f.getType() == org.json.JSONObject.class) {
                    f.setAccessible(true);
                    Object v = f.get(purchase);
                    if (v instanceof org.json.JSONObject) { json = (org.json.JSONObject) v; break; }
                }
            }
        } catch (Throwable ignored) { }
        if (json == null) {
            // 退一步：读 String 字段当原始 JSON 解析
            try {
                for (Field f : purchase.getClass().getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(purchase);
                    if (v instanceof String && ((String) v).startsWith("{")) {
                        json = new org.json.JSONObject((String) v);
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        if (json == null) return;

        String token = json.optString("purchaseToken", null);
        if (token == null || token.length() < 40) return;
        String orderId = json.optString("orderId", null);
        String doc = null;
        try {
            org.json.JSONArray arr = json.optJSONArray("productIds");
            if (arr != null && arr.length() > 0) doc = arr.getString(0);
            else doc = json.optString("productId", null);
        } catch (Throwable ignored) { }
        record(token, doc == null ? orderId : doc);
    }

    /** 把 listener 包一层：先收割参数里的 Purchase，再原样转发 */
    static Object wrapListener(Object original, final String targetMethod) {
        if (original == null || !original.getClass().isInterface()
                && original.getClass().getInterfaces().length == 0) return null;
        Class<?>[] ifs = original.getClass().getInterfaces();
        if (ifs == null || ifs.length == 0) return null;
        for (Class<?> i : ifs) {
            if (!i.getName().contains("Listener")) return null;
        }
        final Object orig = original;
        try {
            return Proxy.newProxyInstance(original.getClass().getClassLoader(), ifs,
                    new InvocationHandler() {
                        public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                            if (targetMethod.equals(m.getName())) harvest(a);
                            try {
                                return m.invoke(orig, a);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause() == null ? e : e.getCause();
                            }
                        }
                    });
        } catch (Throwable t) { return null; }
    }

    /** 从回调参数里把 List<Purchase> 的 token 抠出来 */
    static void harvest(Object[] args) {
        if (args == null) return;
        for (Object a : args) {
            if (!(a instanceof List)) continue;
            for (Object o : (List<?>) a) {
                if (o == null) continue;
                try {
                    Object t = o.getClass().getMethod("getPurchaseToken").invoke(o);
                    if (t instanceof String && ((String) t).length() >= 40) {
                        String doc = null;
                        try {
                            Object pids = o.getClass().getMethod("getProducts").invoke(o);
                            if (pids instanceof List && !((List<?>) pids).isEmpty()) {
                                doc = String.valueOf(((List<?>) pids).get(0));
                            }
                        } catch (Throwable ignored) { }
                        record((String) t, doc);
                    }
                } catch (Throwable ignored) { }
            }
        }
    }

    static void record(String token, String doc) {
        if (token == null || token.length() < 40) return;
        if (token.equals(harvested)) return;
        harvested = token;
        harvestedAt = System.currentTimeMillis();
        harvestedDoc = doc;
        emit("捕获到现有订阅 token（" + token.length() + " 字符"
                + (doc == null ? "" : " · " + doc) + "）");
    }

    // ───────────────────────── 命令通道 ─────────────────────────

    static void listen() {
        if (receiverOn) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String cmd = i.getStringExtra("cmd");
                    log("cmd=" + cmd);
                    String token = i.getStringExtra("token");
                    if ("query".equals(cmd)) queryAll();
                    else if ("preset".equals(cmd)) runPreset(i.getStringExtra("preset"), token);
                    else if ("sub".equals(cmd)) subscribe(i.getStringExtra("sku"), i.getStringExtra("offer"), i.getBooleanExtra("upgrade", false), token);
                    else if ("state".equals(cmd)) state();
                    else if ("probe".equals(cmd)) probe(Main.class.getClassLoader());
                    else if ("summary".equals(cmd)) printSummary();
                }
            };
            IntentFilter f = new IntentFilter(CMD);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            receiverOn = true;
            log("receiver ready");
        } catch (Throwable t) { log("listen err " + Log.getStackTraceString(t)); }
    }

    static void state() {
        emit("状态：计费服务 " + (ready ? "已连接" : (connecting ? "连接中" : "未连接"))
                + " · 已缓存 " + pdMap.size() + " 个 offer");
        String h = harvested;
        if (h != null) {
            emit("已捕获现有订阅 token（" + h.length() + " 字符"
                    + (harvestedDoc == null ? "" : " · " + harvestedDoc) + "）"
                    + " · " + ((System.currentTimeMillis() - harvestedAt) / 1000) + " 秒前");
        } else {
            emit("还没捕获到订阅 token · 进 Google One 的「管理方案」页逛一下就会自动捕获");
        }
        if (ready) emit("就绪，可以查询或直接开档");
        else { emit("正在重连…"); connect(); }
    }

    // ───────────────────────── dex 加载 ─────────────────────────

    /** 找模块 apk 路径：PackageManager 优先，其次 dexElements，最后 ActivityThread */
    static String moduleApk() {
        // ① PackageManager（最稳；LSPosed 加载的模块 classloader 常常是内存 dex，没有 path）
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(MOD, 0);
            if (ai != null && ai.sourceDir != null && new File(ai.sourceDir).exists()) {
                log("moduleApk via PackageManager: " + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable t) { log("moduleApk pm: " + t); }

        // ② dexElements 里的 path
        try {
            ClassLoader cl = Main.class.getClassLoader();
            Field f = dalvik.system.BaseDexClassLoader.class.getDeclaredField("pathList");
            f.setAccessible(true);
            Object pathList = f.get(cl);
            Field fe = pathList.getClass().getDeclaredField("dexElements");
            fe.setAccessible(true);
            Object[] elements = (Object[]) fe.get(pathList);
            for (Object e : elements) {
                try {
                    Field ff = e.getClass().getDeclaredField("path");
                    ff.setAccessible(true);
                    String p = (String) ff.get(e);
                    if (p != null && p.endsWith(".apk") && p.contains(MOD)) return p;
                } catch (Throwable ignored) { }
            }
            for (Object e : elements) {
                try {
                    Field ff = e.getClass().getDeclaredField("path");
                    ff.setAccessible(true);
                    String p = (String) ff.get(e);
                    if (p != null && p.endsWith(".apk")) {
                        log("moduleApk via dexElements: " + p);
                        return p;
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) { log("moduleApk dexElements: " + t); }

        // ③ 从 /data/app 目录里按包名扫（root 才可能读，但 LSPosed 进程通常能读自己）
        try {
            File base = new File("/data/app");
            File[] dirs = base.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!d.getName().startsWith(MOD + "-")) continue;
                    File apk = new File(d, "base.apk");
                    if (apk.exists()) {
                        log("moduleApk via /data/app: " + apk);
                        return apk.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) { log("moduleApk dataapp: " + t); }
        return null;
    }

    static byte[] readBillingDex() {
        String apk = moduleApk();
        if (apk == null) { log("找不到模块 apk 路径"); return null; }
        try {
            ZipFile z = new ZipFile(apk);
            ZipEntry en = z.getEntry("assets/billing.dex");
            if (en == null) { z.close(); log("assets/billing.dex 不存在"); return null; }
            InputStream is = z.getInputStream(en);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            z.close();
            return bos.toByteArray();
        } catch (Throwable t) { log("readBillingDex err " + t); return null; }
    }

    static synchronized ClassLoader loader() throws Exception {
        if (billing != null) return billing;
        byte[] data = readBillingDex();
        if (data == null) throw new IllegalStateException("billing.dex 读取失败");
        try {
            billing = new dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(data), ctx.getClassLoader());
            emit("dex 已内存加载 " + data.length + " B");
        } catch (Throwable t) {
            File dir = ctx.getCodeCacheDir();
            File dex = new File(dir, "billing.dex");
            FileOutputStream fos = new FileOutputStream(dex);
            fos.write(data);
            fos.close();
            billing = new dalvik.system.DexClassLoader(dex.getAbsolutePath(),
                    ctx.getCodeCacheDir().getAbsolutePath(), null, ctx.getClassLoader());
            emit("dex 已落盘加载（API<26 兜底）");
        }
        return billing;
    }

    // ───────────────────────── 计费连接 ─────────────────────────

    static void connect() {
        if (connecting) return;
        connecting = true;
        try {
            final ClassLoader l = loader();
            Class<?> BC = l.loadClass("com.android.billingclient.api.BillingClient");
            Class<?> PUL = l.loadClass("com.android.billingclient.api.PurchasesUpdatedListener");
            Object pul = Proxy.newProxyInstance(l, new Class<?>[]{ PUL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    emit("采购回调 " + m.getName() + " " + (a != null && a.length > 0 ? String.valueOf(a[0]) : ""));
                    return null;
                }
            });
            Class<?> B = l.loadClass("com.android.billingclient.api.BillingClient$Builder");
            Object b = BC.getMethod("newBuilder", Context.class).invoke(null, ctx);
            B.getMethod("setListener", PUL).invoke(b, pul);
            try { B.getMethod("enablePendingPurchases").invoke(b); } catch (Throwable ignored) { }
            client = B.getMethod("build").invoke(b);

            Class<?> SLL = l.loadClass("com.android.billingclient.api.BillingClientStateListener");
            Object sll = Proxy.newProxyInstance(l, new Class<?>[]{ SLL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if ("onBillingSetupFinished".equals(m.getName())) {
                        int code = -1;
                        try { code = (Integer) a[0].getClass().getMethod("getResponseCode").invoke(a[0]); } catch (Throwable ignored) { }
                        ready = code == 0;
                        connecting = false;
                        emit("计费服务 " + (ready ? "已连接" : "连接失败 code=" + code));
                    } else {
                        ready = false;
                        connecting = false;
                        emit("计费服务已断开");
                    }
                    return null;
                }
            });
            client.getClass().getMethod("startConnection", SLL).invoke(client, sll);
            emit("正在连接 Play 计费服务…");
        } catch (Throwable t) {
            connecting = false;
            emit("连接失败 " + t);
            log(Log.getStackTraceString(t));
        }
    }

    // ───────────────────────── 查询 ─────────────────────────

    static void queryAll() {
        try {
            if (client == null) { connect(); emit("计费服务未就绪，已发起重连"); return; }
            if (!ready) { connect(); emit("计费服务未就绪，已发起重连"); return; }
            List<Skus.Item> all = Skus.CATALOG;
            int batch = 20;
            int n = (all.size() + batch - 1) / batch;
            pendingBatches = n;
            summaryScheduled = false;
            pdMap.clear(); tkMap.clear(); offerMap.clear();
            labelMap.clear(); freeMap.clear(); periodMap.clear(); tierMap.clear();
            emit("扫描 " + all.size() + " 个候选商品（" + n + " 批），结果会自动汇总…");
            for (int i = 0; i < all.size(); i += batch) {
                int end = Math.min(i + batch, all.size());
                queryBatch(new ArrayList<Skus.Item>(all.subList(i, end)));
            }
        } catch (Throwable t) { emit("查询异常 " + t); }
    }

    static void queryBatch(List<Skus.Item> items) {
        try {
            final ClassLoader l = loader();
            Class<?> QPDP = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams");
            Class<?> QB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Builder");
            Class<?> Product = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product");
            Class<?> PB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product$Builder");
            Class<?> PDL = l.loadClass("com.android.billingclient.api.ProductDetailsResponseListener");

            List<Object> prods = new ArrayList<Object>();
            for (Skus.Item it : items) {
                Object pb = Product.getMethod("newBuilder").invoke(null);
                PB.getMethod("setProductId", String.class).invoke(pb, it.id);
                PB.getMethod("setProductType", String.class).invoke(pb, "subs");
                prods.add(PB.getMethod("build").invoke(pb));
            }
            Object qb = QPDP.getMethod("newBuilder").invoke(null);
            QB.getMethod("setProductList", List.class).invoke(qb, prods);
            Object params = QB.getMethod("build").invoke(qb);

            Object pdl = Proxy.newProxyInstance(l, new Class<?>[]{ PDL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) { dump(a[0], a[1]); return null; }
            });
            keepRef = pdl;
            client.getClass().getMethod("queryProductDetailsAsync", QPDP, PDL).invoke(client, params, pdl);
        } catch (Throwable t) { emit("批量查询失败 " + t); }
    }

    static String tier(String pid) {
        int pm = Skus.months(pid);
        String tag = pm > 0 ? " · SKU 自带 " + pm + " 个月免费" : "";
        if (pid.contains("helium")) return "Google AI Pro 5TB" + tag;
        if (pid.contains("2tb.ai") || pid.contains("gemini.2tb")) return "Google AI Pro 5TB" + tag;
        if (pid.contains("2tb")) return "Google One 2TB" + tag;
        if (pid.contains("100gb")) return "Google One 100GB" + tag;
        if (pid.contains("200gb")) return "Google One 200GB" + tag;
        if (pid.contains("30gb")) return "Google One 30GB" + tag;
        if (pid.contains("20tb")) return "Ultra 5x · 20TB" + tag;
        if (pid.contains("ai.pro") || pid.contains("30tb") || pid.contains("ultra")) return "Ultra 20x · 30TB" + tag;
        return pid;
    }

    static void dump(Object res, Object list) {
        try {
            int code = (Integer) res.getClass().getMethod("getResponseCode").invoke(res);
            int n = list == null ? 0 : ((List<?>) list).size();
            if (code != 0) { emit("查询失败 code=" + code); return; }
            if (n == 0) { emit("本批没有命中商品（隐藏 SKU 未在该账号/区域开放）"); return; }
            for (Object pd : (List<?>) list) {
                String pid = String.valueOf(pd.getClass().getMethod("getProductId").invoke(pd));
                String title = String.valueOf(pd.getClass().getMethod("getTitle").invoke(pd));
                emit("");
                emit("■ " + tier(pid));
                emit("   " + pid + "  (" + title + ")");
                Object offs = pd.getClass().getMethod("getSubscriptionOfferDetails").invoke(pd);
                if (offs == null) { emit("   （无 offer）"); continue; }
                List<Object[]> rows = new ArrayList<Object[]>();
                for (Object o : (List<?>) offs) {
                    String oid = String.valueOf(o.getClass().getMethod("getOfferId").invoke(o));
                    String tok = String.valueOf(o.getClass().getMethod("getOfferToken").invoke(o));
                    Object phases = o.getClass().getMethod("getPricingPhases").invoke(o);
                    List<?> pl = (List<?>) phases.getClass().getMethod("getPricingPhaseList").invoke(phases);
                    String price = "", period = "", micros = "";
                    for (Object ph : pl) {
                        micros = String.valueOf(ph.getClass().getMethod("getPriceAmountMicros").invoke(ph));
                        price = String.valueOf(ph.getClass().getMethod("getFormattedPrice").invoke(ph));
                        period = String.valueOf(ph.getClass().getMethod("getBillingPeriod").invoke(ph));
                        break;
                    }
                    boolean free = "0".equals(micros);
                    rows.add(new Object[]{ oid, free, price, period, tok });
                }
                Collections.sort(rows, new Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) {
                        int ra = ((Boolean) a[1]) ? 1000 + Skus.months((String) a[0]) : Skus.months((String) a[0]);
                        int rb = ((Boolean) b[1]) ? 1000 + Skus.months((String) b[0]) : Skus.months((String) b[0]);
                        return rb - ra;
                    }
                });
                for (Object[] r : rows) {
                    String oid = (String) r[0];
                    boolean free = (Boolean) r[1];
                    String key = pid + "|" + oid;
                    pdMap.put(key, pd);
                    tkMap.put(key, (String) r[4]);
                    offerMap.put(key, oid);
                    labelMap.put(key, periodText((String) r[3]));
                    freeMap.put(key, free);
                    periodMap.put(key, (String) r[3]);
                    tierMap.put(pid, tier(pid));
                    emit("   " + (free ? "★ " : "  ") + (oid.equals("null") ? "基础档" : oid)
                            + "   " + r[2] + " · 首期 " + periodText((String) r[3])
                            + (free ? "   [免费]" : ""));
                }
            }
        } catch (Throwable t) { emit("解析失败 " + t); }
        finally { batchDone(); }
    }

    /** 每批回来减一，全部回来时自动打印汇总 */
    static void batchDone() {
        int left = pendingBatches - 1;
        pendingBatches = left;
        if (left > 0 || summaryScheduled) return;
        summaryScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() { summaryScheduled = false; printSummary(); }
        }, 1200);
    }

    /** 汇总：告诉用户这个账号能白拿什么 */
    static void printSummary() {
        if (freeMap.isEmpty() && pdMap.isEmpty()) {
            emit("还没有查询结果，先点「查询全部 offer」");
            return;
        }
        for (String line : Summary.report(freeMap, tierMap, periodMap)) emit(line);
        emitRows();
    }

    /** 把结构化汇总以广播送出，模块进程存盘后界面就能渲染卡片 */
    static void emitRows() {
        try {
            List<Summary.Row> rows = Summary.rows(freeMap, tierMap, periodMap);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Summary.Row r : rows) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("tier", r.tier);
                o.put("sku", r.sku);
                o.put("offer", r.offer);
                o.put("months", r.months);
                arr.put(o);
            }
            Intent i = new Intent(EVT);
            i.setPackage(MOD);
            i.putExtra("rows", arr.toString());
            ctx.sendBroadcast(i);
        } catch (Throwable t) { log("emitRows " + t); }
    }

    static String periodText(String p) {
        if (p == null) return "";
        if ("P1Y".equals(p)) return "12 个月";
        if (p.startsWith("P") && p.endsWith("M")) return p.substring(1, p.length() - 1) + " 个月";
        if (p.startsWith("P") && p.endsWith("W")) return p.substring(1, p.length() - 1) + " 周";
        if (p.startsWith("P") && p.endsWith("D")) return p.substring(1, p.length() - 1) + " 天";
        return p;
    }

    // ───────────────────────── 开档 ─────────────────────────

    static void runPreset(String presetId, String presetToken) {
        Skus.Preset p = Skus.preset(presetId);
        if (p == null) { emit("未知方案 " + presetId); return; }
        emit("▶ " + p.title);
        Skus.Step s = p.steps.get(0);
        subscribe(s.sku, s.offer, s.upgrade, presetToken);
    }

    static void subscribe(final String sku, final String offer, final boolean upgrade, final String presetToken) {
        if (sku == null) { emit("缺少 sku"); return; }
        lastSku = sku;
        String offerId = (offer == null || offer.isEmpty()) ? "null" : offer;
        final String key = sku + "|" + offerId;
        emit("准备：" + sku + " · " + (offerId.equals("null") ? "基础档" : offerId) + (upgrade ? "  [订阅升级叠加]" : ""));

        if (!upgrade) {
            pickAndLaunch(key, null);
            return;
        }
        // ① 模块进程在本机读库取好的（不需要电脑 adb）
        if (presetToken != null && presetToken.length() >= 40) {
            emit("用模块本机取的 token（" + presetToken.length() + " 字符）");
            pickAndLaunch(key, presetToken);
            return;
        }
        // ② 从 app 自己的 Billing 回调里捡到的（零 root）
        String h = harvested;
        if (h != null && h.length() >= 40) {
            emit("用 app 回调里捕获的 token（" + h.length() + " 字符"
                    + (harvestedDoc == null ? "" : " · " + harvestedDoc) + "）");
            pickAndLaunch(key, h);
            return;
        }
        queryPurchases(new TokenCb() {
            public void on(String token) {
                if (token == null || token.length() < 10) {
                    emit("没读到现有订阅 token，按普通开通处理");
                    pickAndLaunch(key, null);
                } else {
                    emit("现有订阅 token=" + token.substring(0, Math.min(18, token.length())) + "…");
                    pickAndLaunch(key, token);
                }
            }
        });
    }

    interface TokenCb { void on(String token); }

    static void pickAndLaunch(String key, String oldToken) {
        if (pdMap.containsKey(key)) {
            Object pd = pdMap.get(key);
            String tok = tkMap.get(key);
            launchFlow(pd, tok, oldToken);
            return;
        }
        // 缓存里没有：先查这一条，再拉起
        final String k = key;
        final String old = oldToken;
        final int bar = k.indexOf('|');
        String sku = k.substring(0, bar);
        emit("缓存里没有该 offer，先查询 " + sku);
        final ClassLoader l;
        try { l = loader(); } catch (Throwable t) { emit("loader err " + t); return; }
        try {
            Class<?> QPDP = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams");
            Class<?> QB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Builder");
            Class<?> Product = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product");
            Class<?> PB = l.loadClass("com.android.billingclient.api.QueryProductDetailsParams$Product$Builder");
            Class<?> PDL = l.loadClass("com.android.billingclient.api.ProductDetailsResponseListener");
            Object pb = Product.getMethod("newBuilder").invoke(null);
            PB.getMethod("setProductId", String.class).invoke(pb, sku);
            PB.getMethod("setProductType", String.class).invoke(pb, "subs");
            List<Object> prods = new ArrayList<Object>();
            prods.add(PB.getMethod("build").invoke(pb));
            Object qb = QPDP.getMethod("newBuilder").invoke(null);
            QB.getMethod("setProductList", List.class).invoke(qb, prods);
            Object params = QB.getMethod("build").invoke(qb);
            Object pdl = Proxy.newProxyInstance(l, new Class<?>[]{ PDL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    dump(a[0], a[1]);
                    if (pdMap.containsKey(k)) launchFlow(pdMap.get(k), tkMap.get(k), old);
                    else emit("查询后仍没有 " + k + "，可能该 SKU 在账号/区域不可用");
                    return null;
                }
            });
            keepRef = pdl;
            client.getClass().getMethod("queryProductDetailsAsync", QPDP, PDL).invoke(client, params, pdl);
        } catch (Throwable t) { emit("单条查询失败 " + t); }
    }

    // ───────────────────────── 取现有订阅 token ─────────────────────────

    static String suToken() {
        try {
            String cmd = "cat /data/data/com.android.vending/databases/library.db > /data/local/tmp/ultra_lib.db 2>/dev/null; "
                    + "chmod 644 /data/local/tmp/ultra_lib.db 2>/dev/null; echo ok";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            File f = new File("/data/local/tmp/ultra_lib.db");
            if (!f.exists() || f.length() < 1024) { emit("su 读库失败（无 root 或文件为空）"); return null; }
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    f.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery(
                    "select inapp_purchase_data from ownership where inapp_purchase_data is not null", null);
            String best = null;
            while (c.moveToNext()) {
                String data = c.getString(0);
                if (data == null || data.length() < 40) continue;
                try {
                    org.json.JSONObject j = new org.json.JSONObject(data);
                    String t = j.optString("purchaseToken", null);
                    String doc = j.optString("docId", "");
                    if (t == null || t.length() < 40) continue;
                    if (doc.contains("g1") || doc.contains("2tb") || doc.contains("20tb")) {
                        best = t;
                        if (doc.contains("2tb.ai")) break;
                    }
                } catch (Throwable ignored) { }
            }
            c.close();
            db.close();
            try { new File("/data/local/tmp/ultra_lib.db").delete(); } catch (Throwable ignored) { }
            if (best != null) emit("已从 Play 库读到订阅 token（" + best.length() + " 字符）");
            return best;
        } catch (Throwable t) { emit("su 读库异常 " + t); return null; }
    }

    static void queryPurchases(final TokenCb cb) {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            final ClassLoader l = loader();
            Class<?> PRL = l.loadClass("com.android.billingclient.api.PurchasesResponseListener");
            Object listener = Proxy.newProxyInstance(l, new Class<?>[]{ PRL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if ("onQueryPurchasesResponse".equals(m.getName()) && done.compareAndSet(false, true)) {
                        cb.on(extractToken(a));
                    }
                    return null;
                }
            });
            keepRef = listener;
            boolean sent = false;
            try {
                Class<?> QPP = l.loadClass("com.android.billingclient.api.QueryPurchasesParams");
                Class<?> QPB = l.loadClass("com.android.billingclient.api.QueryPurchasesParams$Builder");
                Object qb = QPP.getMethod("newBuilder").invoke(null);
                QPB.getMethod("setProductType", String.class).invoke(qb, "subs");
                Object params = QPB.getMethod("build").invoke(qb);
                client.getClass().getMethod("queryPurchasesAsync", QPP, PRL).invoke(client, params, listener);
                sent = true;
            } catch (Throwable ignored) { }
            if (!sent) client.getClass().getMethod("queryPurchasesAsync", String.class, PRL).invoke(client, "subs", listener);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    if (done.compareAndSet(false, true)) {
                        emit("3 秒内没有采购回调，改读 Play 库 / 历史购买");
                        String viaSu = suToken();
                        if (viaSu != null) { cb.on(viaSu); return; }
                        queryHistory(new TokenCb() {
                            public void on(String t2) {
                                if (t2 == null || t2.length() < 10) emit("拿不到现有订阅 token，按普通开通处理");
                                cb.on(t2);
                            }
                        });
                    }
                }
            }, 3000);
        } catch (Throwable t) { emit("查询已有订阅失败 " + t); cb.on(null); }
    }

    static void queryHistory(final TokenCb cb) {
        try {
            ClassLoader l = loader();
            Class<?> PHL = l.loadClass("com.android.billingclient.api.PurchaseHistoryResponseListener");
            Object listener = Proxy.newProxyInstance(l, new Class<?>[]{ PHL }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if (m.getName().contains("History")) cb.on(extractToken(a));
                    return null;
                }
            });
            keepRef = listener;
            client.getClass().getMethod("queryPurchaseHistoryAsync", String.class, PHL).invoke(client, "subs", listener);
        } catch (Throwable t) { cb.on(null); }
    }

    static String extractToken(Object[] a) {
        try {
            for (int i = a.length - 1; i >= 0; i--) {
                Object v = a[i];
                if (v instanceof List) {
                    List<?> list = (List<?>) v;
                    if (list.isEmpty()) return null;
                    Object pu = list.get(0);
                    return String.valueOf(pu.getClass().getMethod("getPurchaseToken").invoke(pu));
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ───────────────────────── 拉起收银台 ─────────────────────────

    static Activity liveActivity() {
        try {
            Class<?> AT = Class.forName("android.app.ActivityThread");
            Object at = AT.getMethod("currentActivityThread").invoke(null);
            Field f = AT.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(at);
            java.util.Collection<?> vals = (java.util.Collection<?>) map.getClass().getMethod("values").invoke(map);
            for (Object rec : vals) {
                try {
                    Field af = rec.getClass().getDeclaredField("activity");
                    af.setAccessible(true);
                    Object a = af.get(rec);
                    if (a instanceof Activity) {
                        Activity act = (Activity) a;
                        if (!act.isFinishing() && !act.isDestroyed()) return act;
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return (Activity) activity;
    }

    static void launchFlow(final Object pd, final String offerToken, final String oldToken) {
        long now = System.currentTimeMillis();
        if (now - lastLaunch < 3000) { emit("3 秒内已发起过一次，忽略重复点击"); return; }
        lastLaunch = now;
        final Activity act = liveActivity();
        emit("activity=" + (act == null ? "null" : act.getClass().getName()));
        if (act == null) { emit("没有可用 Activity，先打开 Google One 主界面"); return; }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    ClassLoader l = pd.getClass().getClassLoader();
                    Class<?> PDP = l.loadClass("com.android.billingclient.api.BillingFlowParams$ProductDetailsParams");
                    Class<?> PD = l.loadClass("com.android.billingclient.api.ProductDetails");
                    Class<?> PDB = l.loadClass("com.android.billingclient.api.BillingFlowParams$ProductDetailsParams$Builder");
                    Object pb = PDP.getMethod("newBuilder").invoke(null);
                    PDB.getMethod("setProductDetails", PD).invoke(pb, pd);
                    if (offerToken != null && !"null".equals(offerToken)) {
                        PDB.getMethod("setOfferToken", String.class).invoke(pb, offerToken);
                    }
                    Object pdp = PDB.getMethod("build").invoke(pb);
                    List<Object> one = new ArrayList<Object>();
                    one.add(pdp);

                    Class<?> BFP = l.loadClass("com.android.billingclient.api.BillingFlowParams");
                    Class<?> BFB = l.loadClass("com.android.billingclient.api.BillingFlowParams$Builder");
                    Object b = BFP.getMethod("newBuilder").invoke(null);
                    BFB.getMethod("setProductDetailsParamsList", List.class).invoke(b, one);

                    if (oldToken != null) {
                        Class<?> SUP = l.loadClass("com.android.billingclient.api.BillingFlowParams$SubscriptionUpdateParams");
                        Class<?> SUB = l.loadClass("com.android.billingclient.api.BillingFlowParams$SubscriptionUpdateParams$Builder");
                        Object sb = SUP.getMethod("newBuilder").invoke(null);
                        try { sb = SUB.getMethod("setReplaceProrationMode", int.class).invoke(sb, 5); }
                        catch (Throwable e1) {
                            try { sb = SUB.getMethod("setReplaceSkusProrationMode", int.class).invoke(sb, 5); }
                            catch (Throwable e2) { emit("proration 设置失败 " + e2); }
                        }
                        try { sb = SUB.getMethod("setOldPurchaseToken", String.class).invoke(sb, oldToken); }
                        catch (Throwable e1) {
                            try { sb = SUB.getMethod("setOldSkuPurchaseToken", String.class).invoke(sb, oldToken); }
                            catch (Throwable e2) { emit("oldToken 设置失败 " + e2); }
                        }
                        Object sup = SUB.getMethod("build").invoke(sb);
                        b = BFB.getMethod("setSubscriptionUpdateParams", SUP).invoke(b, sup);
                        emit("已带上订阅升级参数（proration=5）");
                    }
                    Object params = BFB.getMethod("build").invoke(b);
                    Object res = client.getClass().getMethod("launchBillingFlow", Activity.class, BFP).invoke(client, act, params);
                    emit("已拉起 Play 收银台：" + res);
                } catch (Throwable t) { emit("拉起失败 " + t); log(Log.getStackTraceString(t)); }
            }
        });
    }

    // ───────────────────────── 输出 ─────────────────────────

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
