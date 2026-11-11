package jp.papadenkilab.combookbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * One day of the nursery's Lukumi report (園・施設から), as scanned from
 * SchoolCombookFragment. Values are kept close to the on-screen text;
 * interpretation for Papotto happens in the writer.
 */
public class SchoolDraft {
    public String dateIso = "";      // yyyy-MM-dd ("" if the date could not be read)
    public String dateLabel = "";    // raw date-button text, e.g. 11月09日（月）
    public String comment = "";      // teacher comment card text (labels stripped)
    public final List<String> temperatures = new ArrayList<String>(); // "12:31 36.0"
    public final List<String> toilets = new ArrayList<String>();      // "12:05 ふつう"
    public final List<String> sleeps = new ArrayList<String>();       // "08:45-09:20"
    public final List<String> milks = new ArrayList<String>();        // "12:00 180"
    public final List<String> meals = new ArrayList<String>();        // "朝おやつ=完食"
    public String mealMemo = "";
    public String mood = "";

    public int itemCount() {
        return temperatures.size() + toilets.size() + sleeps.size() + milks.size()
                + meals.size() + (mealMemo.isEmpty() ? 0 : 1) + (mood.isEmpty() ? 0 : 1)
                + (comment.isEmpty() ? 0 : 1);
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("園の連絡帳: ").append(dateLabel.isEmpty() ? "(日付不明)" : dateLabel)
          .append(dateIso.isEmpty() ? "" : " / " + dateIso).append("\n");
        sb.append("先生コメント: ").append(comment.isEmpty() ? "(なし)" : comment).append("\n");
        sb.append("体温: ").append(temperatures.isEmpty() ? "(なし)" : join(temperatures, "、")).append("\n");
        sb.append("トイレ: ").append(toilets.isEmpty() ? "(なし)" : join(toilets, "、")).append("\n");
        sb.append("睡眠: ").append(sleeps.isEmpty() ? "(なし)" : join(sleeps, "、")).append("\n");
        sb.append("ミルク: ").append(milks.isEmpty() ? "(なし)" : join(milks, "、")).append("\n");
        sb.append("食事: ").append(meals.isEmpty() ? "(なし)" : join(meals, "、")).append("\n");
        sb.append("食事メモ: ").append(mealMemo.isEmpty() ? "(なし)" : mealMemo).append("\n");
        sb.append("機嫌: ").append(mood.isEmpty() ? "(なし)" : mood).append("\n");
        return sb.toString();
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(sep); sb.append(xs.get(i)); }
        return sb.toString();
    }

    private static String san(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ').replace(',', ' ').trim();
    }

    public String toData() {
        StringBuilder sb = new StringBuilder("v1");
        sb.append('|').append(san(dateIso));
        sb.append('|').append(san(dateLabel));
        sb.append('|').append(san(comment));
        sb.append('|').append(san(mealMemo));
        sb.append('|').append(san(mood));
        sb.append('|').append(joinSan(temperatures));
        sb.append('|').append(joinSan(toilets));
        sb.append('|').append(joinSan(sleeps));
        sb.append('|').append(joinSan(milks));
        sb.append('|').append(joinSan(meals));
        return sb.toString();
    }

    private static String joinSan(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(','); sb.append(san(xs.get(i))); }
        return sb.toString();
    }

    public static SchoolDraft fromData(String data) {
        SchoolDraft d = new SchoolDraft();
        if (data == null || data.isEmpty()) return d;
        String[] p = data.split("\\|", -1);
        if (p.length < 11) return d;
        d.dateIso = p[1]; d.dateLabel = p[2]; d.comment = p[3];
        d.mealMemo = p[4]; d.mood = p[5];
        addAll(d.temperatures, p[6]);
        addAll(d.toilets, p[7]);
        addAll(d.sleeps, p[8]);
        addAll(d.milks, p[9]);
        addAll(d.meals, p[10]);
        return d;
    }

    private static void addAll(List<String> out, String csv) {
        if (csv == null || csv.isEmpty()) return;
        for (String s : csv.split(",", -1)) if (!s.isEmpty()) out.add(s);
    }
}
