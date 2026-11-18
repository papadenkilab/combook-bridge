import jp.papadenkilab.combookbridge.TimelineParser;

import java.util.LinkedHashMap;
import java.util.Map;

/** JVM tests for the Lukumi timeline parsing (登園/降園 times). */
public class TestTimeline {
    static int fails = 0;

    static void eq(String name, Object got, Object want) {
        boolean ok = (got == null && want == null) || (got != null && got.equals(want));
        System.out.println((ok ? "ok   " : "FAIL ") + name + " -> " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        String[] dt = TimelineParser.parseDateTime("2026年11月18日 (水) 08:07");
        eq("date iso", dt == null ? null : dt[0], "2026-11-18");
        eq("time padded", dt == null ? null : dt[1], "08:07");
        dt = TimelineParser.parseDateTime("2026年11月18日（水）17:51");
        eq("fullwidth paren time", dt == null ? null : dt[1], "17:51");
        dt = TimelineParser.parseDateTime("2026年1月5日 (月) 8:07");
        eq("single digits", dt == null ? null : dt[0] + " " + dt[1], "2026-01-05 08:07");
        eq("no datetime", TimelineParser.parseDateTime("園から連絡帳が届きました。"), null);
        eq("date only", TimelineParser.parseDateTime("令和8年11月18日"), null);

        eq("toen title", TimelineParser.isToenTitle("登園しました。"), true);
        eq("toen title no maru", TimelineParser.isToenTitle("登園しました"), true);
        eq("koen title", TimelineParser.isKoenTitle("降園しました。"), true);
        eq("not toen", TimelineParser.isToenTitle("降園しました。"), false);
        eq("not koen", TimelineParser.isKoenTitle("登園しました。"), false);

        Map<String, String[]> map = new LinkedHashMap<String, String[]>();
        TimelineParser.merge(map, "2026-11-18", "08:07", true);
        TimelineParser.merge(map, "2026-11-18", "17:51", false);
        eq("basic toen", map.get("2026-11-18")[0], "08:07");
        eq("basic koen", map.get("2026-11-18")[1], "17:51");
        // duplicates: earliest 登園 / latest 降園 win
        TimelineParser.merge(map, "2026-11-18", "09:30", true);
        TimelineParser.merge(map, "2026-11-18", "16:00", false);
        eq("earliest toen kept", map.get("2026-11-18")[0], "08:07");
        eq("latest koen kept", map.get("2026-11-18")[1], "17:51");
        TimelineParser.merge(map, "2026-11-18", "07:55", true);
        eq("earlier toen replaces", map.get("2026-11-18")[0], "07:55");
        // other date is independent
        TimelineParser.merge(map, "2026-11-17", "08:20", true);
        eq("other date toen", map.get("2026-11-17")[0], "08:20");
        eq("other date koen empty", map.get("2026-11-17")[1], "");

        eq("newest date", TimelineParser.newestDate(map), "2026-11-18");
        eq("newest of empty", TimelineParser.newestDate(new LinkedHashMap<String, String[]>()), null);
        eq("complete pairs 1", TimelineParser.completePairs(map), 1);
        TimelineParser.merge(map, "2026-11-17", "18:05", false);
        eq("complete pairs 2", TimelineParser.completePairs(map), 2);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
