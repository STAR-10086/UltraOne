package com.star.ultraone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SKU / offer 目录与一键方案。
 *
 * 数据来源：g1-acq-plus 的 sku-catalog.json（Android Play 侧实测命中）
 * 与 g1-acq.js 的 preset 表（iOS 侧对照）。
 *
 * 每条 Step = 一次 queryProductDetails + launchBillingFlow。
 * offer 为 null 表示用 ProductDetails 里的第一个 offer（base plan）。
 */
public final class Skus {

    private Skus() {}

    /** 一个可查询的商品 */
    public static final class Item {
        public final String id;
        public final String label;
        public Item(String id, String label) { this.id = id; this.label = label; }
    }

    /** 方案里的一步 */
    public static final class Step {
        public final String sku;
        public final String offer;
        public final String label;
        public final boolean upgrade;
        public Step(String sku, String offer, String label, boolean upgrade) {
            this.sku = sku; this.offer = offer; this.label = label; this.upgrade = upgrade;
        }
    }

    /** 一键方案 */
    public static final class Preset {
        public final String id;
        public final String title;
        public final String desc;
        public final List<Step> steps;
        public Preset(String id, String title, String desc, Step... s) {
            this.id = id; this.title = title; this.desc = desc;
            this.steps = Arrays.asList(s);
        }
    }

    // ─────────────────────────── 扫描目录 ───────────────────────────

    /** 全量候选商品：分批 queryProductDetails，命中什么就显示什么 */
    public static final List<Item> CATALOG = new ArrayList<Item>();
    static {
        // ── AI Pro 5TB · gemini 命名空间探测 ──
        add("com.google.gemini.20tb.m.1month_eft", "Ultra 20TB · 1 个月免费（gemini）");
        add("com.google.gemini.20tb.m.3month_ip_99p", "Ultra 20TB · 3 个月 0.1 折（gemini）");
        add("com.google.gemini.2tb.ai.m.12month_eft", "AI Pro 5TB · 12 个月免费（gemini）");
        add("com.google.gemini.2tb.ai.m.12month_ip_90p", "AI Pro 5TB · 12 个月 1 折（gemini）");
        add("com.google.gemini.2tb.ai.m.1month_eft", "AI Pro 5TB · 1 个月免费（gemini）");
        add("com.google.gemini.2tb.ai.m.1month_ip_99p", "AI Pro 5TB · 1 个月 0.1 折（gemini）");
        add("com.google.gemini.2tb.ai.m.3month_eft", "AI Pro 5TB · 3 个月免费（gemini）");
        add("com.google.gemini.2tb.ai.m.3month_ip_99p", "AI Pro 5TB · 3 个月 0.05 折（gemini）");
        add("com.google.gemini.30tb.ai.pro.m.1month_eft", "Ultra 30TB · 1 个月免费（gemini）");
        // ── AI Pro 5TB ──
        add("g1.2tb.ai.12months_eft", "AI Pro 5TB · 12 个月免费");
        add("g1.2tb.ai.15months_eft", "AI Pro 5TB · 15 个月免费");
        add("g1.2tb.ai.18months_eft", "AI Pro 5TB · 18 个月免费");
        add("g1.2tb.ai.1month_eft", "AI Pro 5TB · 1 个月免费");
        add("g1.2tb.ai.2months_eft", "AI Pro 5TB · 2 个月免费");
        add("g1.2tb.ai.3months_eft", "AI Pro 5TB · 3 个月免费");
        add("g1.2tb.ai.4months_eft", "AI Pro 5TB · 4 个月免费");
        add("g1.2tb.ai.6months_eft", "AI Pro 5TB · 6 个月免费");
        add("g1.2tb.ai.subs_variant", "AI Pro 5TB · 叠加档");
        add("g1.play.2tb.ai", "AI Pro 5TB · Play");
        add("g1.play.2tb.ai.student", "AI Pro 5TB · Play · 学生档");
        add("g1.play.helium", "AI Pro · Helium");
        add("g1.play.helium.student", "AI Pro · Helium · 学生档");
        add("g1.2tb.ai", "AI Pro 5TB · $19.99");
        add("g1.2tb.ai.12months_ip_75p", "AI Pro 5TB · 12 个月 75% OFF · $19.99");
        add("g1.2tb.ai.2months_ip_50p", "AI Pro 5TB · 2 个月 50% OFF · $19.99");
        add("g1.2tb.ai.3months_ip_50p", "AI Pro 5TB · 3 个月 50% OFF · $19.99");
        add("g1.2tb.ai.annual", "AI Pro 5TB · $199.99");
        add("g1.helium.12months_ip_50p", "AI Pro · Helium 折扣 · 12 个月 50% OFF · $4.99");
        add("g1.helium.12months_ip_75p", "AI Pro · Helium 折扣 · 12 个月 75% OFF · $4.99");
        add("g1.helium.12months_ip_90p", "AI Pro · Helium 折扣 · 12 个月 90% OFF · $4.99");
        add("g1.helium.1month_ip_75p.acq", "AI Pro · Helium 折扣 · 1 个月 75% OFF · $4.99");
        add("g1.helium.1month_ip_90p.acq", "AI Pro · Helium 折扣 · 1 个月 90% OFF · $4.99");
        add("g1.helium.1month_ip_95p", "AI Pro · Helium 折扣 · 1 个月 95% OFF · $4.99");
        add("g1.helium.2months_ip_50p", "AI Pro · Helium 折扣 · 2 个月 50% OFF · $4.99");
        add("g1.helium.3months_ip_75p.acq", "AI Pro · Helium 折扣 · 3 个月 75% OFF · $4.99");
        add("g1.helium.annual.12months_ip_10p.acq", "AI Pro · Helium 折扣 · 12 个月 10% OFF · $49.99");
        add("g1.helium.annual.12months_ip_25p.acq", "AI Pro · Helium 折扣 · 12 个月 25% OFF · $49.99");
        // ── Google One 2TB ──
        add("g1.2tb.1month_eft", "Google One 2TB · 1 个月免费");
        add("g1.2tb.1year_eft", "Google One 2TB · 1 年免费");
        add("g1.2tb.3months_eft", "Google One 2TB · 3 个月免费");
        add("g1.2tb.6months_eft", "Google One 2TB · 6 个月免费");
        add("g1.2tb.annual.1month_eft", "Google One 2TB · 1 个月免费");
        add("g1.2tb.annual.3months_eft", "Google One 2TB · 3 个月免费");
        add("g1.2tb.subs_variant", "Google One 2TB · 叠加档");
        add("g1.2tb", "Google One 2TB · $9.99");
        add("g1.2tb.12months_ip.4.99", "Google One 2TB · $9.99");
        add("g1.2tb.1month_ip.2.49", "Google One 2TB · $9.99");
        add("g1.2tb.1month_ip_50p", "Google One 2TB · 1 个月 50% OFF · $9.99");
        add("g1.2tb.1month_ip_80p", "Google One 2TB · 1 个月 80% OFF · $9.99");
        add("g1.2tb.1month_ip_85p.acq", "Google One 2TB · 1 个月 85% OFF · $9.99");
        add("g1.2tb.1month_ip_90p.acq", "Google One 2TB · 1 个月 90% OFF · $9.99");
        add("g1.2tb.1month_ip_95p.acq", "Google One 2TB · 1 个月 95% OFF · $9.99");
        add("g1.2tb.2months_ip_50p.acq", "Google One 2TB · 2 个月 50% OFF · $9.99");
        add("g1.2tb.2months_ip_75p", "Google One 2TB · 2 个月 75% OFF · $9.99");
        add("g1.2tb.2months_ip_80p", "Google One 2TB · 2 个月 80% OFF · $9.99");
        add("g1.2tb.3months_ip.2.49", "Google One 2TB · $9.99");
        add("g1.2tb.3months_ip_50p", "Google One 2TB · 3 个月 50% OFF · $9.99");
        add("g1.2tb.3months_ip_50p.acq", "Google One 2TB · 3 个月 50% OFF · $9.99");
        add("g1.2tb.3months_ip_80p", "Google One 2TB · 3 个月 80% OFF · $9.99");
        add("g1.2tb.3months_ip_85p.acq", "Google One 2TB · 3 个月 85% OFF · $9.99");
        add("g1.2tb.6months_ip.4.99", "Google One 2TB · $9.99");
        add("g1.2tb.annual", "Google One 2TB · $99.99");
        add("g1.2tb.annual.12months_ip_10p", "Google One 2TB · 12 个月 10% OFF · $99.99");
        add("g1.2tb.annual.12months_ip_15p", "Google One 2TB · 12 个月 15% OFF · $99.99");
        add("g1.2tb.annual.12months_ip_20p", "Google One 2TB · 12 个月 20% OFF · $99.99");
        add("g1.2tb.annual.12months_ip_25p.acq", "Google One 2TB · 12 个月 25% OFF · $99.99");
        add("g1.2tb.annual.12months_ip_40p", "Google One 2TB · 12 个月 40% OFF · $99.99");
        // ── Google One 5TB / 10TB ──
        add("g1.10tb", "Google One 5TB / 10TB · $49.99");
        add("g1.5tb", "Google One 5TB / 10TB · $19.99");
        add("g1.5tb.annual", "Google One 5TB / 10TB · $199.99");
        // ── Google One 100GB ──
        add("g1.100gb.1month_eft", "Google One 100GB · 1 个月免费");
        add("g1.100gb.1year_eft", "Google One 100GB · 1 年免费");
        add("g1.100gb.2months_eft", "Google One 100GB · 2 个月免费");
        add("g1.100gb.3months_eft", "Google One 100GB · 3 个月免费");
        add("g1.100gb.annual.1month_eft", "Google One 100GB · 1 个月免费");
        add("g1.100gb.annual.1year_eft", "Google One 100GB · 1 年免费");
        add("g1.100gb.annual.3months_eft", "Google One 100GB · 3 个月免费");
        add("g1.200gb.1month_eft", "Google One 200GB · 1 个月免费");
        add("g1.200gb.1year_eft", "Google One 200GB · 1 年免费");
        add("g1.200gb.3months_eft", "Google One 200GB · 3 个月免费");
        add("g1.200gb.annual.1month_eft", "Google One 200GB · 1 个月免费");
        add("g1.200gb.annual.3months_eft", "Google One 200GB · 3 个月免费");
        add("g1.100gb", "Google One 100GB · $1.99");
        add("g1.100gb.12months_ip.0.49", "Google One 100GB · $1.99");
        add("g1.100gb.12months_ip.0.99", "Google One 100GB · $1.99");
        add("g1.100gb.1month_ip.0.49", "Google One 100GB · $1.99");
        add("g1.100gb.1month_ip.0.99", "Google One 100GB · $1.99");
        add("g1.100gb.1month_ip_50p", "Google One 100GB · 1 个月 50% OFF · $1.99");
        add("g1.100gb.1month_ip_85p.acq", "Google One 100GB · 1 个月 85% OFF · $1.99");
        add("g1.100gb.1month_ip_95p.acq", "Google One 100GB · 1 个月 95% OFF · $1.99");
        add("g1.100gb.2months_ip_75p", "Google One 100GB · 2 个月 75% OFF · $1.99");
        add("g1.100gb.3months_ip_50p", "Google One 100GB · 3 个月 50% OFF · $1.99");
        add("g1.100gb.3months_ip_50p.acq", "Google One 100GB · 3 个月 50% OFF · $1.99");
        add("g1.100gb.3months_ip_85p.acq", "Google One 100GB · 3 个月 85% OFF · $1.99");
        add("g1.100gb.6months_ip.0.99", "Google One 100GB · $1.99");
        add("g1.100gb.6months_ip_75p", "Google One 100GB · 6 个月 75% OFF · $1.99");
        add("g1.100gb.annual", "Google One 100GB · $19.99");
        add("g1.100gb.annual.12months_ip_10p", "Google One 100GB · 12 个月 10% OFF · $19.99");
        add("g1.100gb.annual.12months_ip_15p", "Google One 100GB · 12 个月 15% OFF · $19.99");
        add("g1.100gb.annual.12months_ip_20p", "Google One 100GB · 12 个月 20% OFF · $19.99");
        add("g1.100gb.annual.12months_ip_40p", "Google One 100GB · 12 个月 40% OFF · $19.99");
        add("g1.200gb", "Google One 200GB · $2.99");
        add("g1.200gb.12months_ip.1.49", "Google One 200GB · $2.99");
        add("g1.200gb.1month_ip_50p", "Google One 200GB · 1 个月 50% OFF · $2.99");
        add("g1.200gb.1month_ip_85p.acq", "Google One 200GB · 1 个月 85% OFF · $2.99");
        add("g1.200gb.1month_ip_95p.acq", "Google One 200GB · 1 个月 95% OFF · $2.99");
        add("g1.200gb.2months_ip_75p", "Google One 200GB · 2 个月 75% OFF · $2.99");
        add("g1.200gb.3months_ip_50p", "Google One 200GB · 3 个月 50% OFF · $2.99");
        add("g1.200gb.3months_ip_50p.acq", "Google One 200GB · 3 个月 50% OFF · $2.99");
        add("g1.200gb.3months_ip_85p.acq", "Google One 200GB · 3 个月 85% OFF · $2.99");
        add("g1.200gb.6months_ip.1.49", "Google One 200GB · $2.99");
        add("g1.200gb.annual", "Google One 200GB · $29.99");
        add("g1.200gb.annual.12months_ip_10p", "Google One 200GB · 12 个月 10% OFF · $29.99");
        add("g1.200gb.annual.12months_ip_15p", "Google One 200GB · 12 个月 15% OFF · $29.99");
        add("g1.200gb.annual.12months_ip_20p", "Google One 200GB · 12 个月 20% OFF · $29.99");
        add("g1.200gb.annual.12months_ip_40p", "Google One 200GB · 12 个月 40% OFF · $29.99");
        // ── Google One 30GB ──
        add("g1.30gb.1month_eft", "Google One 30GB · 1 个月免费");
        add("g1.30gb.annual.1month_eft", "Google One 30GB · 1 个月免费");
        add("g1.30gb", "Google One 30GB · $0.89");
        add("g1.30gb.12month_ip_50p", "Google One 30GB · 12 个月 50% OFF · $0.89");
        add("g1.30gb.1month_ip_75p", "Google One 30GB · 1 个月 75% OFF · $0.89");
        add("g1.30gb.1month_ip_85p.acq", "Google One 30GB · 1 个月 85% OFF · $0.89");
        add("g1.30gb.1month_ip_95p.acq", "Google One 30GB · 1 个月 95% OFF · $0.89");
        add("g1.30gb.3month_ip_75p", "Google One 30GB · 3 个月 75% OFF · $0.89");
        add("g1.30gb.3months_ip_50p.acq", "Google One 30GB · 3 个月 50% OFF · $0.89");
        add("g1.30gb.3months_ip_85p.acq", "Google One 30GB · 3 个月 85% OFF · $0.89");
        add("g1.30gb.6month_ip_50p", "Google One 30GB · 6 个月 50% OFF · $0.89");
        add("g1.30gb.annual", "Google One 30GB · $8.99");
        add("g1.30gb.annual.1year_ip_10p", "Google One 30GB · 1 年 10% OFF · $8.99");
        add("g1.30gb.annual.1year_ip_15p", "Google One 30GB · 1 年 15% OFF · $8.99");
        add("g1.30gb.annual.1year_ip_20p", "Google One 30GB · 1 年 20% OFF · $8.99");
        add("g1.30gb.annual.1year_ip_40p", "Google One 30GB · 1 年 40% OFF · $8.99");
        // ── Ultra 20TB / 30TB ──
        add("g1.20tb", "Ultra 20TB 基础档");
        add("g1.20tb.1month_eft.acq", "Ultra 20TB · 1 个月免费（acq 遗留）");
        add("g1.30tb", "Ultra 30TB 基础档");
        add("g1.ai.ultra", "AI Ultra 基础档");
        add("g1.ai.ultra.1month_eft.acq", "Ultra 30TB · 1 个月免费（acq 遗留）");
        add("g1.20tb.v2", "Ultra 20TB / 30TB · $99.99");
        add("g1.ai.pro", "Ultra 20TB / 30TB · $199.99");
        add("g1.play.20tb", "Ultra 20TB / 30TB · $99.99");
        add("g1.play.ai.pro", "Ultra 20TB / 30TB · $199.99");
        // ── YouTube Premium ──
        add("g1.youtube_premium_individual_add_on", "YouTube Premium");
        // ── 其他 ──
        add("g1.ios.2tb.ai.m.12month_ip_90p", "AI Pro 12 个月 1 折（ios 命名空间）");
    }

    private static void add(String id, String label) { CATALOG.add(new Item(id, label)); }

    // ─────────────────────────── 一键方案 ───────────────────────────

    public static final List<Preset> PRESETS = new ArrayList<Preset>();
    static {
        // 1. Pro 12 个月免费
        PRESETS.add(new Preset("pro12", "① Pro 5TB · 12 个月 $0",
                "AI Pro 5TB 12 个月免费试用，到期 $19.99/月。首次开通点这个。",
                new Step("g1.2tb.ai.subs_variant", "12months-eft", "12 个月免费", false)));

        // 2. 叠加 6 个月
        PRESETS.add(new Preset("pro+6a", "② Pro · 叠加 +6 个月（第 1 段）",
                "订阅升级叠加，Play 把剩余试用期折算顺延，收银台仍为 $0.00。",
                new Step("g1.2tb.ai.subs_variant", "6months-eft", "+6 个月", true)));

        PRESETS.add(new Preset("pro+6b", "③ Pro · 叠加 +6 个月（第 2 段）",
                "第二段 6 个月，叠加后共 24 个月。",
                new Step("g1.2tb.ai.subs_variant", "6months-eft-2", "+6 个月", true)));

        PRESETS.add(new Preset("pro+6c", "④ Pro · 叠加 +6 个月（第 3 段）",
                "第三段 6 个月（实测 Play 侧常拒绝，留档备查）。",
                new Step("g1.2tb.ai.subs_variant", "6months-eft-ss-2026", "+6 个月", true)));

        // 3. 直开 18 / 15 / 12 个月独立 SKU
        PRESETS.add(new Preset("pro18", "⑤ Pro 5TB · 18 个月 $0（独立 SKU）",
                "g1.2tb.ai.18months_eft 自带 18 个月免费期，一步到位。",
                new Step("g1.2tb.ai.18months_eft", null, "18 个月免费", false)));

        PRESETS.add(new Preset("pro15", "⑥ Pro 5TB · 15 个月 $0（独立 SKU）",
                "g1.2tb.ai.15months_eft 自带 15 个月免费期。",
                new Step("g1.2tb.ai.15months_eft", null, "15 个月免费", false)));

        PRESETS.add(new Preset("pro12b", "⑦ Pro 5TB · 12 个月 $0（独立 SKU）",
                "g1.2tb.ai.12months_eft 自带 12 个月免费期。",
                new Step("g1.2tb.ai.12months_eft", null, "12 个月免费", false)));

        PRESETS.add(new Preset("student", "⑧ AI Pro 学生 · 12 个月 $0",
                "g1.play.2tb.ai.student，需账号通过学生验证。",
                new Step("g1.play.2tb.ai.student", "eft-12mo", "学生 12 个月", false)));

        // 4. Ultra
        PRESETS.add(new Preset("ultra20", "⑨ Ultra 20TB · 1 个月 $0",
                "真 Ultra 5x 20TB，$99.99/月档的 1 个月免费试用。",
                new Step("g1.play.20tb", null, "Ultra 20TB 免费 1 个月", false)));

        PRESETS.add(new Preset("ultra30", "⑩ Ultra 30TB · 1 个月 $0",
                "真 Ultra 20x 30TB，$199.99/月档的 1 个月免费试用。",
                new Step("g1.play.ai.pro", null, "Ultra 30TB 免费 1 个月", false)));

        // 5. 低价开 Pro
        PRESETS.add(new Preset("pro99p", "⑪ Pro 5TB · 3 个月 0.05 折",
                "com.google.gemini.2tb.ai.m.3month_ip_99p，美区实测 $0.29/月。",
                new Step("com.google.gemini.2tb.ai.m.3month_ip_99p", null, "前 3 个月 0.1 折", false)));

        // 6. 100GB 免费
        PRESETS.add(new Preset("g100", "⑫ 100GB · 12 个月 $0",
                "g1.100gb.annual.1year_eft，小容量免费档，用来验证链路。",
                new Step("g1.100gb.annual.1year_eft", null, "100GB 12 个月", false)));
    }

    public static Preset preset(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    /** 从 offerId 猜免费月数，用于排序和显示 */
    public static int months(String offerId) {
        if (offerId == null) return 0;
        String s = offerId.toLowerCase();
        try {
            int i = s.indexOf("months");
            if (i > 0) {
                int j = i;
                while (j > 0 && Character.isDigit(s.charAt(j - 1))) j--;
                if (j < i) return Integer.parseInt(s.substring(j, i));
            }
            if (s.contains("12mo")) return 12;
            if (s.contains("6mo")) return 6;
            if (s.contains("3mo")) return 3;
            if (s.contains("1mo")) return 1;
            if (s.contains("year")) return 12;
        } catch (Throwable ignored) { }
        return 0;
    }
}
