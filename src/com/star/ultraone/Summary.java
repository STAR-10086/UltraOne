package com.star.ultraone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把查询结果汇总成「这个账号现在能白拿什么」。
 *
 * 不按 SKU 罗列，按档位归并：一个档位下面可能挂着十几个 SKU，
 * 对用户来说只关心「Pro 5TB 最长能免费多久」。
 */
public final class Summary {

    private Summary() {}

    /** 一条可用的免费 offer */
    public static final class Free {
        public final String sku;
        public final String offer;
        public final String tier;      // 归并后的档位名
        public final int months;       // 免费月数
        public final String rawPeriod; // P1Y / P6M
        public Free(String sku, String offer, String tier, int months, String rawPeriod) {
            this.sku = sku; this.offer = offer; this.tier = tier;
            this.months = months; this.rawPeriod = rawPeriod;
        }
    }

    /** 一个档位的汇总 */
    public static final class TierGroup {
        public final String tier;
        public final List<Free> offers = new ArrayList<Free>();
        public int bestMonths;
        public String bestSku;
        public String bestOffer;
        public TierGroup(String tier) { this.tier = tier; }
    }

    /** 把 tier() 的原始输出归一成展示用的档位名 */
    public static String normalize(String tier) {
        if (tier == null) return "其他";
        int cut = tier.indexOf(" · SKU 自带");
        return cut > 0 ? tier.substring(0, cut) : tier;
    }

    /** 档位排序权重：AI Pro > Ultra > 大容量 > 小容量 */
    static int rank(String tier) {
        if (tier.contains("AI Pro")) return 0;
        if (tier.contains("Ultra")) return 1;
        if (tier.contains("2TB")) return 2;
        if (tier.contains("200GB")) return 3;
        if (tier.contains("100GB")) return 4;
        if (tier.contains("30GB")) return 5;
        return 6;
    }

    /**
     * 生成汇总报告。
     *
     * @param all     所有查到的 (sku|offer -> free) 记录
     * @param tierOf  sku -> 档位名
     * @param periodOf sku|offer -> 首期时长（P1Y 等）
     */
    public static List<String> report(Map<String, Boolean> all,
                                      Map<String, String> tierOf,
                                      Map<String, String> periodOf) {
        // 1. 归并到档位
        TreeMap<String, TierGroup> groups = new TreeMap<String, TierGroup>();
        for (Map.Entry<String, Boolean> e : all.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;   // 只看免费
            String key = e.getKey();
            int bar = key.indexOf('|');
            if (bar < 0) continue;
            String sku = key.substring(0, bar);
            String offer = key.substring(bar + 1);
            String tier = normalize(tierOf.get(sku));
            TierGroup g = groups.get(tier);
            if (g == null) { g = new TierGroup(tier); groups.put(tier, g); }
            int m = monthsOf(sku, offer, periodOf.get(key));
            g.offers.add(new Free(sku, offer, tier, m, periodOf.get(key)));
            if (m > g.bestMonths) {
                g.bestMonths = m;
                g.bestSku = sku;
                g.bestOffer = offer;
            }
        }

        // 2. 排序
        List<TierGroup> list = new ArrayList<TierGroup>(groups.values());
        Collections.sort(list, new Comparator<TierGroup>() {
            public int compare(TierGroup a, TierGroup b) {
                int ra = rank(a.tier), rb = rank(b.tier);
                if (ra != rb) return ra - rb;
                return b.bestMonths - a.bestMonths;
            }
        });

        // 3. 输出
        List<String> out = new ArrayList<String>();
        int totalFree = 0;
        for (TierGroup g : list) totalFree += g.offers.size();

        out.add("╔══════════════════════════════════");
        out.add("║ 这个账号现在能拿到的免费订阅");
        out.add("║ 共 " + list.size() + " 个档位 · " + totalFree + " 个免费 offer");
        out.add("╚══════════════════════════════════");

        for (TierGroup g : list) {
            out.add("");
            out.add("【" + g.tier + "】");
            // 最优那条排最前
            List<Free> sorted = new ArrayList<Free>(g.offers);
            Collections.sort(sorted, new Comparator<Free>() {
                public int compare(Free a, Free b) {
                    if (a.months != b.months) return b.months - a.months;
                    return a.sku.compareTo(b.sku);
                }
            });
            // 同一 SKU 只显示最好的那条，避免刷屏
            Map<String, Free> bySku = new LinkedHashMap<String, Free>();
            for (Free f : sorted) if (!bySku.containsKey(f.sku)) bySku.put(f.sku, f);
            int shown = 0;
            for (Free f : bySku.values()) {
                if (shown++ >= 4) { out.add("   …还有 " + (bySku.size() - 4) + " 个 SKU"); break; }
                out.add("   " + pad(f.months) + "  " + shortSku(f.sku) + "  [" + f.offer + "]");
            }
            if (g.bestMonths > 0) {
                out.add("   ▶ 最长 " + g.bestMonths + " 个月 · 点方案或「开这个」");
            }
        }

        // 4. 一句话结论
        out.add("");
        out.add("────── 结论 ──────");
        TierGroup best = null;
        for (TierGroup g : list) {
            if (g.bestMonths <= 0) continue;
            if (best == null || g.bestMonths > best.bestMonths) best = g;
        }
        if (best == null) {
            out.add("这个账号没有可用的免费档（可能已订阅过或区域不支持）");
        } else {
            out.add("最划算：" + best.tier + " · " + best.bestMonths + " 个月 $0");
            out.add("   SKU   " + best.bestSku);
            out.add("   offer " + best.bestOffer);
            out.add("");
            int total = 0;
            for (TierGroup g : list) if (g.tier.equals(best.tier)) total = g.bestMonths;
            if (best.tier.contains("AI Pro") && total >= 12) {
                out.add("提示：AI Pro 5TB 可以叠加续期，12 + 6 + 6 = 24 个月，");
                out.add("      每段之间点「② 叠加 +6 个月」即可。");
            }
        }
        return out;
    }

    /** 结构化版本：给界面渲染卡片用（每个档位只留最优的几条） */
    public static List<Row> rows(Map<String, Boolean> all,
                                 Map<String, String> tierOf,
                                 Map<String, String> periodOf) {
        List<Row> out = new ArrayList<Row>();
        TreeMap<String, List<Free>> byTier = new TreeMap<String, List<Free>>();
        for (Map.Entry<String, Boolean> e : all.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            String key = e.getKey();
            int bar = key.indexOf('|');
            if (bar < 0) continue;
            String sku = key.substring(0, bar);
            String offer = key.substring(bar + 1);
            String tier = normalize(tierOf.get(sku));
            List<Free> l = byTier.get(tier);
            if (l == null) { l = new ArrayList<Free>(); byTier.put(tier, l); }
            l.add(new Free(sku, offer, tier, monthsOf(sku, offer, periodOf.get(key)), periodOf.get(key)));
        }
        List<String> tiers = new ArrayList<String>(byTier.keySet());
        Collections.sort(tiers, new Comparator<String>() {
            public int compare(String a, String b) {
                int ra = rank(a), rb = rank(b);
                return ra != rb ? ra - rb : a.compareTo(b);
            }
        });
        for (String tier : tiers) {
            List<Free> l = byTier.get(tier);
            Collections.sort(l, new Comparator<Free>() {
                public int compare(Free a, Free b) {
                    if (a.months != b.months) return b.months - a.months;
                    return a.sku.compareTo(b.sku);
                }
            });
            Map<String, Free> seen = new LinkedHashMap<String, Free>();
            for (Free f : l) if (!seen.containsKey(f.sku)) seen.put(f.sku, f);
            int n = 0;
            for (Free f : seen.values()) {
                if (n++ >= 3) break;
                Row r = new Row();
                r.tier = tier; r.sku = f.sku; r.offer = f.offer; r.months = f.months;
                out.add(r);
            }
        }
        return out;
    }

    /** 界面用的扁平行 */
    public static final class Row {
        public String tier;
        public String sku;
        public String offer;
        public int months;
    }

    private static String pad(int months) {
        if (months <= 0) return "  ?  ";
        String s = months + " 个月";
        while (s.length() < 7) s = " " + s;
        return s;
    }

    /** g1.2tb.ai.subs_variant → 2tb.ai.subs_variant，省地方 */
    private static String shortSku(String sku) {
        if (sku == null) return "?";
        String s = sku;
        if (s.startsWith("g1.")) s = s.substring(3);
        if (s.length() > 34) s = s.substring(0, 31) + "…";
        return s;
    }

    /**
     * 推断免费月数，优先级：
     *   1) offerId 自带数字（eft-12months / 6months-eft）
     *   2) SKU 名自带数字（g1.2tb.ai.18months_eft / g1.2tb.1year_eft）
     *   3) billingPeriod（P1Y / P6M）
     * freetrial / 基础档这类没数字的 offer，月数只能从 SKU 名看。
     */
    static int monthsOf(String sku, String offerId, String period) {
        int m = Skus.months(offerId);
        if (m > 0) return m;
        m = Skus.months(sku);
        if (m > 0) return m;
        if (period == null) return 0;
        try {
            if ("P1Y".equals(period)) return 12;
            if (period.startsWith("P") && period.endsWith("M")) {
                return Integer.parseInt(period.substring(1, period.length() - 1));
            }
            if (period.startsWith("P") && period.endsWith("W")) {
                int w = Integer.parseInt(period.substring(1, period.length() - 1));
                return w >= 52 ? 12 : (w >= 26 ? 6 : (w >= 13 ? 3 : Math.max(1, w / 4)));
            }
            if (period.startsWith("P") && period.endsWith("D")) {
                int d = Integer.parseInt(period.substring(1, period.length() - 1));
                return d >= 365 ? 12 : Math.max(1, d / 30);
            }
        } catch (Throwable ignored) { }
        return 0;
    }
}
