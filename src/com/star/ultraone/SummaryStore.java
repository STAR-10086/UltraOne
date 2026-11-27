package com.star.ultraone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 把汇总结果结构化存起来，供界面渲染成卡片（进程被杀也不丢） */
public final class SummaryStore {

    private static final String PREFS = "ultraone_summary";

    public static final class Row {
        public String tier = "";
        public String sku = "";
        public String offer = "";
        public int months;
    }

    private SummaryStore() {}

    public static void save(Context c, List<Row> rows) {
        try {
            JSONArray arr = new JSONArray();
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("tier", r.tier);
                o.put("sku", r.sku);
                o.put("offer", r.offer);
                o.put("months", r.months);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("rows", arr);
            root.put("at", System.currentTimeMillis());
            prefs(c).edit().putString("data", root.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static List<Row> load(Context c) {
        List<Row> out = new ArrayList<Row>();
        try {
            String s = prefs(c).getString("data", null);
            if (s == null) return out;
            JSONArray arr = new JSONObject(s).optJSONArray("rows");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Row r = new Row();
                r.tier = o.optString("tier", "");
                r.sku = o.optString("sku", "");
                r.offer = o.optString("offer", "");
                r.months = o.optInt("months", 0);
                out.add(r);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static long savedAt(Context c) {
        try {
            String s = prefs(c).getString("data", null);
            if (s == null) return 0;
            return new JSONObject(s).optLong("at", 0);
        } catch (Throwable t) { return 0; }
    }

    public static void clear(Context c) { prefs(c).edit().remove("data").apply(); }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
