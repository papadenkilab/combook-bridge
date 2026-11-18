package jp.papadenkilab.combookbridge;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing helpers for Lukumi's timeline feed (the home tab).
 * Each timeline card carries a header like "2026年11月18日 (水) 08:07"
 * and a title like "登園しました。" / "降園しました。". The scanner
 * pairs a title with the single date-time found in its card subtree
 * and merges the result here, keyed by ISO date.
 *
 * Merge semantics for duplicates on the same day: the earliest
 * 登園 and the latest 降園 win (a re-entry should not move 登園
 * later, and an early "trial" 降園 should not shorten the day).
 * Kept Android-free so it can be JVM-tested (TestTimeline).
 */
public class TimelineParser {

    private static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4})年(\\d{1,2})月(\\d{1,2})日[^0-9]*(\\d{1,2}):(\\d{2})");

    /** "2026年11月18日 (水) 08:07" -> {"2026-11-18", "08:07"}, or null. */
    public static String[] parseDateTime(String text) {
        if (text == null) return null;
        Matcher m = DATE_TIME.matcher(text);
        if (!m.find()) return null;
        String iso = String.format(Locale.JAPAN, "%04d-%02d-%02d",
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
        String time = String.format(Locale.JAPAN, "%02d:%02d",
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        return new String[]{iso, time};
    }

    public static boolean isToenTitle(String text) {
        return "登園しました".equals(normalizeTitle(text));
    }

    public static boolean isKoenTitle(String text) {
        return "降園しました".equals(normalizeTitle(text));
    }

    private static String normalizeTitle(String text) {
        if (text == null) return "";
        return text.trim().replace("。", "").replace("．", "").trim();
    }

    /** The newest (lexicographically largest) ISO date key, or null. */
    public static String newestDate(Map<String, String[]> map) {
        String best = null;
        for (String k : map.keySet()) {
            if (best == null || k.compareTo(best) > 0) best = k;
        }
        return best;
    }

    /** How many dates have both a 登園 and a 降園 time. */
    public static int completePairs(Map<String, String[]> map) {
        int n = 0;
        for (String[] v : map.values()) {
            if (v != null && v.length >= 2 && !v[0].isEmpty() && !v[1].isEmpty()) n++;
        }
        return n;
    }

    /** Merge one sighting into the map: dateIso -> {toenTime, koenTime}. */
    public static void merge(Map<String, String[]> map, String dateIso, String time, boolean toen) {
        if (dateIso == null || dateIso.isEmpty() || time == null || time.isEmpty()) return;
        String[] cur = map.get(dateIso);
        if (cur == null) { cur = new String[]{"", ""}; map.put(dateIso, cur); }
        if (toen) {
            if (cur[0].isEmpty() || time.compareTo(cur[0]) < 0) cur[0] = time;
        } else {
            if (cur[1].isEmpty() || time.compareTo(cur[1]) > 0) cur[1] = time;
        }
    }
}
