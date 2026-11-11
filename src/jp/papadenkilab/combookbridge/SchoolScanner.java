package jp.papadenkilab.combookbridge;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans Lukumi's nursery-side daily report (SchoolCombookFragment):
 * waits for the rv_details list, reads the date button and the teacher
 * comment card, then scroll-collects every detail row and parses them
 * into a SchoolDraft. Read-only: never taps anything on this screen
 * except scrolling the list.
 */
public class SchoolScanner {

    private static final Pattern DATE_BTN = Pattern.compile("(\\d{1,2})[/月](\\d{1,2})");
    private static final Pattern P_TEMP = Pattern.compile("(\\d{1,2}:\\d{2})\\s+(\\d+\\.\\d+)");
    private static final Pattern P_SLEEP = Pattern.compile("(\\d{1,2}:\\d{2})\\s*〜\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern P_MILK = Pattern.compile("(\\d{1,2}:\\d{2})\\s+(\\d+)\\s*ml");
    private static final Pattern P_TIME = Pattern.compile("(\\d{1,2}:\\d{2})\\s*(.*)");

    private final BridgeService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int stage = 0; // 0 wait for the school screen, 1 collect
    private int waitTicks = 0;
    private int awayTicks = 0;
    private int steps = 0;
    private int idle = 0;
    private String lastSig = "";
    private long startMs = 0;
    private String dateIso = "";
    private String dateLabel = "";
    private String comment = "";
    private final Map<String, String[]> rows = new LinkedHashMap<String, String[]>();

    public SchoolScanner(BridgeService svc) { this.svc = svc; }

    public boolean isRunning() { return running; }

    public void start() {
        running = true;
        stage = 0;
        waitTicks = 0;
        steps = 0;
        idle = 0;
        lastSig = "";
        rows.clear();
        dateIso = "";
        dateLabel = "";
        comment = "";
        startMs = System.currentTimeMillis();
        Toast.makeText(svc, "園・施設からの画面を探しています", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::loop, 500);
    }

    private void loop() {
        if (!running) return;
        if (!Store.isSchoolPending(svc)) { stopCancelled(); return; }
        try {
            step();
        } catch (Exception e) {
            finish(false, "例外: " + e.getMessage());
            return;
        }
        if (running) handler.postDelayed(this::loop, 250);
    }

    private void step() {
        AccessibilityNodeInfo root = rootWithDetails();
        if (stage == 0) {
            if (root == null) {
                if (++waitTicks > 240) { finish(false, "園・施設からの画面が見つかりません"); }
                return;
            }
            readHeader(root);
            stage = 1;
            return;
        }
        if (root == null) {
            if (SchoolScanner.otherAppActive(svc, "com.unifa_e.hiyoko.family")) {
                if (++awayTicks > 40) { finish(false, "別のアプリに切り替わったため中止しました"); return; }
            } else awayTicks = 0;
            return;
        }
        awayTicks = 0;
        String c2 = extractComment(root);
        if (!c2.isEmpty()) comment = c2;
        AccessibilityNodeInfo list = findByIdSuffix(root, "rv_details");
        if (list == null) return;
        collectRows(list);
        String sig = signature(list);
        if (sig.equals(lastSig)) idle++; else idle = 0;
        lastSig = sig;
        boolean moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        steps++;
        if ((!moved && idle >= 1) || idle >= 3 || steps > 40) {
            finish(rows.size() > 0, rows.size() > 0 ? "" : "項目が見つかりません");
        }
    }

    /** The window root that contains rv_details, or null. */
    private AccessibilityNodeInfo rootWithDetails() {
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && findByIdSuffix(r, "rv_details") != null) return r;
            }
        }
        AccessibilityNodeInfo active = svc.getRootInActiveWindow();
        if (active != null && findByIdSuffix(active, "rv_details") != null) return active;
        return null;
    }

    private static long dayStartMs(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, d, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void readHeader(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo btn = findByIdSuffix(root, "btnDatePicker");
        if (btn != null && btn.getText() != null) {
            dateLabel = btn.getText().toString().trim();
            Matcher m = DATE_BTN.matcher(dateLabel);
            if (m.find()) {
                int month = Integer.parseInt(m.group(1));
                int day = Integer.parseInt(m.group(2));
                // the report's year is the one that puts the date closest
                // to today (reports can be today, yesterday, or tomorrow)
                Calendar now = Calendar.getInstance();
                int ty = now.get(Calendar.YEAR);
                long todayMs = dayStartMs(ty, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
                int year = ty;
                long best = Long.MAX_VALUE;
                for (int cand = ty - 1; cand <= ty + 1; cand++) {
                    long diff = Math.abs(dayStartMs(cand, month, day) - todayMs);
                    if (diff < best) { best = diff; year = cand; }
                }
                dateIso = String.format(Locale.JAPAN, "%04d-%02d-%02d", year, month, day);
            }
        }
        String c = extractComment(root);
        if (!c.isEmpty()) comment = c;
    }

    /** The comment is the longest non-label text near the comment card / 連絡欄 label. */
    private String extractComment(AccessibilityNodeInfo root) {
        String c = commentFromSubtree(findByIdSuffix(root, "cardComment"));
        if (!c.isEmpty()) return c;
        AccessibilityNodeInfo label = findTextExact(root, "連絡欄");
        AccessibilityNodeInfo n = label;
        for (int i = 0; i < 3 && n != null; i++) {
            n = n.getParent();
            if (n == null) break;
            c = commentFromSubtree(n);
            if (!c.isEmpty()) return c;
        }
        return "";
    }

    private String commentFromSubtree(AccessibilityNodeInfo node) {
        if (node == null) return "";
        List<String> texts = new ArrayList<String>();
        gatherTexts(node, texts);
        String best = "";
        for (String t : texts) {
            String s = t.trim();
            if (s.isEmpty() || s.equals("*") || s.equals("\uff0a")) continue;
            if (s.equals("記入者") || s.equals("連絡欄")) continue;
            if (s.contains("コメント") && s.length() <= 12) continue;
            if (s.endsWith("先生")) continue; // the author line
            if (s.length() > best.length()) best = s;
        }
        return best;
    }

    static AccessibilityNodeInfo findTextExact(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && text.equals(t.toString().trim())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findTextExact(node.getChild(i), text);
            if (r != null) return r;
        }
        return null;
    }

    private void collectRows(AccessibilityNodeInfo list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null) continue;
            String title = textByIdSuffix(row, "tvTitle");
            String value = textByIdSuffix(row, "tvValue");
            String eaten = textByIdSuffix(row, "tvEaten");
            if (title == null && value == null && eaten == null) continue;
            String key = (title == null ? "" : title) + "|" + (value == null ? "" : value)
                    + "|" + (eaten == null ? "" : eaten);
            if (!rows.containsKey(key)) {
                rows.put(key, new String[]{title == null ? "" : title.trim(),
                        value == null ? "" : value.trim(), eaten == null ? "" : eaten.trim()});
            }
        }
    }

    private SchoolDraft parse() {
        SchoolDraft d = new SchoolDraft();
        d.dateIso = dateIso;
        d.dateLabel = dateLabel;
        d.comment = comment;
        for (String[] r : rows.values()) {
            String title = r[0], value = r[1], eaten = r[2];
            if (!eaten.isEmpty()) { d.meals.add(title + "=" + eaten); continue; }
            if (value.isEmpty()) continue;
            Matcher m;
            if (title.equals("体温")) {
                m = P_TEMP.matcher(value);
                if (m.find()) d.temperatures.add(Aggregator.padTime(m.group(1)) + " " + m.group(2));
            } else if (title.equals("トイレ")) {
                m = P_TIME.matcher(value);
                if (m.find()) d.toilets.add(Aggregator.padTime(m.group(1)) + (m.group(2).isEmpty() ? "" : " " + m.group(2)));
            } else if (title.equals("睡眠")) {
                m = P_SLEEP.matcher(value);
                if (m.find()) d.sleeps.add(Aggregator.padTime(m.group(1)) + "-" + Aggregator.padTime(m.group(2)));
            } else if (title.equals("ミルク")) {
                m = P_MILK.matcher(value);
                if (m.find()) d.milks.add(Aggregator.padTime(m.group(1)) + " " + m.group(2));
            } else if (title.equals("機嫌")) {
                d.mood = value;
            } else if (title.equals("連絡欄")) {
                if (d.comment.isEmpty()) d.comment = value;
            } else if (title.equals("食事メモ")) {
                d.mealMemo = value;
            }
        }
        return d;
    }

    private void stopCancelled() {
        running = false;
        Store.saveSchoolResult(svc, false, "中止しました");
        Toast.makeText(svc, "園の連絡帳の読み取りを中止しました", Toast.LENGTH_LONG).show();
        Intent i = new Intent(svc, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        svc.startActivity(i);
    }

    private void finish(boolean ok, String note) {
        running = false;
        Store.setSchoolPending(svc, false);
        String dur = Store.fmtDuration(System.currentTimeMillis() - startMs);
        if (ok) {
            SchoolDraft d = parse();
            Store.saveSchoolData(svc, d.toData());
            Store.saveSchoolDraftText(svc, d.format());
            String label = dateLabel.isEmpty() ? "日付不明" : dateLabel;
            Store.saveSchoolResult(svc, true, label + " ・ 所要" + dur);
            Toast.makeText(svc, "園の連絡帳を読み取りました", Toast.LENGTH_LONG).show();
        } else {
            Store.saveSchoolResult(svc, false, (note == null || note.isEmpty() ? "取得失敗" : note) + " ・ 所要" + dur);
            Toast.makeText(svc, "園の連絡帳を読み取れませんでした", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(svc, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        svc.startActivity(i);
    }

    // ---------------- helpers ----------------

    private String signature(AccessibilityNodeInfo list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null) continue;
            String t = textByIdSuffix(row, "tvTitle");
            String v = textByIdSuffix(row, "tvValue");
            sb.append(t == null ? "" : t).append('=').append(v == null ? "" : v).append('|');
        }
        return Integer.toString(sb.toString().hashCode());
    }

    private void gatherTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null) out.add(t.toString());
        for (int i = 0; i < node.getChildCount(); i++) gatherTexts(node.getChild(i), out);
    }

    /** Package of the currently active window, or "" if unknown. */
    public static String activePackage(android.accessibilityservice.AccessibilityService svc) {
        try {
            AccessibilityNodeInfo a = svc.getRootInActiveWindow();
            if (a != null && a.getPackageName() != null) return a.getPackageName().toString();
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * True when a different real app is in front. System UI (notification
     * shade, screen-recording prompts), system dialogs and this app
     * itself do not count as "another app".
     */
    public static boolean otherAppActive(android.accessibilityservice.AccessibilityService svc, String pkg) {
        String p = activePackage(svc);
        if (p.isEmpty() || p.equals(pkg)) return false;
        if (p.equals("com.android.systemui") || p.equals("android")) return false;
        if (p.equals("jp.papadenkilab.combookbridge")) return false;
        if (p.contains("permissioncontroller")) return false;
        return true;
    }

    static AccessibilityNodeInfo findByIdSuffix(AccessibilityNodeInfo node, String suffix) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith(suffix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByIdSuffix(node.getChild(i), suffix);
            if (r != null) return r;
        }
        return null;
    }

    static String textByIdSuffix(AccessibilityNodeInfo node, String suffix) {
        AccessibilityNodeInfo n = findByIdSuffix(node, suffix);
        if (n == null || n.getText() == null) return null;
        return n.getText().toString();
    }
}
