package jp.papadenkilab.combookbridge;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Aggregates Papotto timeline rows into a Lukumi combook draft.
 * Pure Java (no Android deps) so it can be unit-tested on the JVM.
 *
 * Period rule (spec): ALL records from 18:00 on the previous
 * day onward are transcribed. Meal times use the START time.
 * Medication: no Papotto 薬 record in period -> 投薬なし; otherwise
 * 投薬あり and the record content is entered.
 */
public class Aggregator {

    public static class Draft {
        public String targetDate = "";
        public String temperature = "";      // e.g. 36.3
        public String temperatureTime = "";  // e.g. 7:11
        public List<String> stoolTimes = new ArrayList<String>();
        public String sleepStart = "";       // HH:mm on previous day
        public String sleepEnd = "";         // HH:mm on target day
        public List<String> milk = new ArrayList<String>();   // "18:52 140ml"
        public List<String> breast = new ArrayList<String>(); // times
        public List<String> medicine = new ArrayList<String>(); // "time memo/value"
        public String dinnerTime = "";
        public String dinnerMemo = "";
        public boolean dinnerMemoTruncated = false;
        public String breakfastTime = "";
        public String breakfastMemo = "";
        public boolean breakfastMemoTruncated = false;
        public List<String> warnings = new ArrayList<String>();

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("登園日: ").append(targetDate).append("\n");
            sb.append("体温: ").append(temperature.isEmpty() ? "(なし)" : temperature + "℃")
              .append(temperatureTime.isEmpty() ? "" : "（" + temperatureTime + "）").append("\n");
            sb.append("便: ").append(stoolTimes.isEmpty() ? "なし" : "あり " + join(stoolTimes, "、")).append("\n");
            sb.append("睡眠: ").append(sleepStart.isEmpty() || sleepEnd.isEmpty()
                ? "(ペアが見つかりません)" : sleepStart + " 〜 " + sleepEnd).append("\n");
            sb.append("ミルク: ").append(milk.isEmpty() ? "なし" : "あり " + join(milk, "、")).append("\n");
            sb.append("授乳: ").append(breast.isEmpty() ? "なし" : "あり " + join(breast, "、")).append("\n");
            sb.append("投薬: ").append(medicine.isEmpty() ? "なし" : "あり " + join(medicine, "、")).append("\n");
            sb.append("夕食: ").append(dinnerTime.isEmpty() ? "(なし)" : dinnerTime)
              .append(dinnerMemo.isEmpty() ? "" : " ／ " + dinnerMemo)
              .append(dinnerMemoTruncated ? "（一覧で省略あり・詳細確認が必要）" : "").append("\n");
            sb.append("朝食: ").append(breakfastTime.isEmpty() ? "(なし)" : breakfastTime)
              .append(breakfastMemo.isEmpty() ? "" : " ／ " + breakfastMemo)
              .append(breakfastMemoTruncated ? "（一覧で省略あり・詳細確認が必要）" : "").append("\n");
            sb.append("機嫌: ふつう（固定） / お迎え: ルクミー初期値のまま");
            for (String w : warnings) sb.append("\n⚠ ").append(w);
            return sb.toString();
        }
    }

    public static Draft aggregate(List<RecordItem> items) {
        Draft d = new Draft();
        if (items == null || items.isEmpty()) {
            d.warnings.add("記録が0件でした");
            return d;
        }
        for (RecordItem r : items) r.time = padTime(r.time);
        // target date = newest date present
        String target = "";
        for (RecordItem r : items) if (r.dateIso.compareTo(target) > 0) target = r.dateIso;
        d.targetDate = target;
        String prev = shiftDate(target, -1);

        List<RecordItem> period = new ArrayList<RecordItem>();
        for (RecordItem r : items) {
            if (r.dateIso.equals(target)) period.add(r);
            else if (r.dateIso.equals(prev) && r.time.compareTo("18:00") >= 0) period.add(r);
        }

        // sleep: earliest 起きる on target day + latest 寝る on previous day
        RecordItem wake = null, sleep = null;
        for (RecordItem r : period) {
            if (r.name.equals("起きる") && r.dateIso.equals(target)) {
                if (wake == null || r.time.compareTo(wake.time) < 0) wake = r;
            }
            if (r.name.equals("寝る") && r.dateIso.equals(prev)) {
                if (sleep == null || r.time.compareTo(sleep.time) > 0) sleep = r;
            }
        }
        if (wake != null) d.sleepEnd = wake.time;
        if (sleep != null) d.sleepStart = sleep.time;
        if (wake == null || sleep == null) d.warnings.add("睡眠のペア（寝る/起きる）が揃いませんでした");

        // temperature: latest 体温 on target day; value like "36.3℃"
        RecordItem temp = null;
        for (RecordItem r : period) {
            if (r.name.equals("体温") && r.dateIso.equals(target)) {
                if (temp == null || r.time.compareTo(temp.time) > 0) temp = r;
            }
        }
        if (temp != null) {
            d.temperatureTime = temp.time;
            d.temperature = temp.value.replace("℃", "").replace("°C", "").trim();
            if (d.temperature.isEmpty() && !temp.memo.isEmpty()) d.temperature = temp.memo.trim();
        } else {
            d.warnings.add("当日の体温記録が見つかりませんでした");
        }

        // stool / milk / breast, chronological within the period
        List<RecordItem> chrono = new ArrayList<RecordItem>(period);
        sortChrono(chrono);
        for (RecordItem r : chrono) {
            if (r.name.equals("うんち")) d.stoolTimes.add(r.time);
            else if (r.name.equals("哺乳瓶")) {
                String ml = r.value.replace("ミルク", "").trim(); // "200ml"
                d.milk.add(r.time + (ml.isEmpty() ? "" : " " + ml));
            } else if (r.name.equals("母乳")) {
                d.breast.add(r.time);
            } else if (r.name.equals("薬")) {
                String detail = !r.memo.isEmpty() ? r.memo : r.value;
                d.medicine.add(r.time + (detail.isEmpty() ? "" : " " + detail));
            }
        }

        // dinner: latest ごはん on previous day (period: >=18:00); breakfast: earliest ごはん on target day
        RecordItem dinner = null, breakfast = null;
        for (RecordItem r : period) {
            if (!r.name.equals("ごはん")) continue;
            if (r.dateIso.equals(prev)) {
                if (dinner == null || r.time.compareTo(dinner.time) > 0) dinner = r;
            } else if (r.dateIso.equals(target)) {
                if (breakfast == null || r.time.compareTo(breakfast.time) < 0) breakfast = r;
            }
        }
        if (dinner != null) {
            d.dinnerTime = dinner.time;
            d.dinnerMemo = flatten(dinner.memo);
            d.dinnerMemoTruncated = isTruncated(dinner.memo);
        } else d.warnings.add("前夜の夕食（ごはん）が見つかりませんでした");
        if (breakfast != null) {
            d.breakfastTime = breakfast.time;
            d.breakfastMemo = flatten(breakfast.memo);
            d.breakfastMemoTruncated = isTruncated(breakfast.memo);
        } else d.warnings.add("今朝の朝食（ごはん）が見つかりませんでした");

        return d;
    }

    static String padTime(String t) {
        if (t == null) return "";
        int i = t.indexOf(':');
        if (i == 1) return "0" + t;
        return t;
    }

    static boolean isTruncated(String memo) {
        if (memo == null) return false;
        String m = memo.trim();
        return m.endsWith("…") || m.endsWith("...") || m.endsWith("‥");
    }

    static String flatten(String memo) {
        return memo == null ? "" : memo.replace("\n", "、").trim();
    }

    static String shiftDate(String iso, int days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(iso));
            c.add(Calendar.DATE, days);
            return f.format(c.getTime());
        } catch (ParseException e) {
            return "";
        }
    }

    static void sortChrono(List<RecordItem> list) {
        // simple stable insertion sort by (date,time); lists are tiny
        for (int i = 1; i < list.size(); i++) {
            RecordItem x = list.get(i);
            int j = i - 1;
            while (j >= 0 && (list.get(j).dateIso + list.get(j).time).compareTo(x.dateIso + x.time) > 0) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, x);
        }
    }

    static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /** Lukumi time pickers only offer 5-minute steps; minutes are floored (PickerValueMapper). */
    public static String floor5(String hhmm) {
        String t = padTime(hhmm);
        int c = t.indexOf(':');
        if (c < 0) return t;
        int h = Integer.parseInt(t.substring(0, c));
        int m = Integer.parseInt(t.substring(c + 1));
        m = (m / 5) * 5;
        return String.format(Locale.JAPAN, "%02d:%02d", h, m);
    }

    public static int hourOf(String hhmm) {
        String t = padTime(hhmm);
        int c = t.indexOf(':');
        return c < 0 ? 0 : Integer.parseInt(t.substring(0, c));
    }

    public static int minuteOf(String hhmm) {
        String t = padTime(hhmm);
        int c = t.indexOf(':');
        return c < 0 ? 0 : Integer.parseInt(t.substring(c + 1));
    }

    // ---- structured serialization for the Lukumi filler (unit separator = \u0001) ----

    public static String toData(Draft d) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "target", d.targetDate);
        kv(sb, "temp", d.temperature);
        kv(sb, "tempTime", d.temperatureTime);
        kv(sb, "stool", join(d.stoolTimes, "\u0001"));
        kv(sb, "sleepStart", d.sleepStart);
        kv(sb, "sleepEnd", d.sleepEnd);
        kv(sb, "milk", join(d.milk, "\u0001"));
        kv(sb, "breast", join(d.breast, "\u0001"));
        kv(sb, "medicine", join(d.medicine, "\u0001"));
        kv(sb, "dinnerTime", d.dinnerTime);
        kv(sb, "dinnerMemo", d.dinnerMemo);
        kv(sb, "breakfastTime", d.breakfastTime);
        kv(sb, "breakfastMemo", d.breakfastMemo);
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        if (v == null) v = "";
        sb.append(k).append('=').append(v.replace('\n', ' ')).append('\n');
    }

    public static Draft fromData(String s) {
        Draft d = new Draft();
        if (s == null) return d;
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            switch (k) {
                case "target": d.targetDate = v; break;
                case "temp": d.temperature = v; break;
                case "tempTime": d.temperatureTime = v; break;
                case "stool": d.stoolTimes = splitList(v); break;
                case "sleepStart": d.sleepStart = v; break;
                case "sleepEnd": d.sleepEnd = v; break;
                case "milk": d.milk = splitList(v); break;
                case "breast": d.breast = splitList(v); break;
                case "medicine": d.medicine = splitList(v); break;
                case "dinnerTime": d.dinnerTime = v; break;
                case "dinnerMemo": d.dinnerMemo = v; break;
                case "breakfastTime": d.breakfastTime = v; break;
                case "breakfastMemo": d.breakfastMemo = v; break;
                default: break;
            }
        }
        return d;
    }

    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<String>();
        if (v == null || v.isEmpty()) return out;
        for (String x : v.split("\u0001")) if (!x.isEmpty()) out.add(x);
        return out;
    }
}
