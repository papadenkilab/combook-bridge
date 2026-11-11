package jp.papadenkilab.combookbridge;

import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a scanned nursery report (SchoolDraft) into Papotto as
 * individual records on the report's date:
 *   登園 8:00 / 降園 18:00 (fixed), meals at the nursery's fixed times
 *   (朝おやつ 9:30 → おやつ, 昼 11:30 → ごはん・離乳食, おやつ 14:30 → おやつ,
 *   memos carry the completion ratings), sleeps as 寝る/起きる pairs,
 *   toilet → うんち, temperature/milk via the custom WheelView with a
 *   gesture-drag calibrate routine. Teacher comment + mood (as text) go
 *   into the 降園 memo; the meal memo goes to the record it refers to
 *   (a snack mention -> that snack, otherwise the lunch record).
 */
public class PapottoWriter {

    static final int K_TOEN = 0, K_KON = 1, K_SLEEP_START = 2, K_SLEEP_END = 3,
            K_SNACK = 4, K_MEAL = 5, K_MILK = 6, K_TOILET = 7, K_TEMP = 8, K_DIARY = 9;

    static class WOp {
        int kind;
        String typeName;   // panel cell label
        String time;       // HH:mm (start)
        String endTime;    // HH:mm or null
        String memo;       // memo text or null
        int ml;            // MILK
        double temp;       // TEMP
        String moodId;     // DIARY: radio button id suffix
        String startTime;  // SLEEP_END: span start (the 起きる record's start field)
        String cond;       // TOILET: Papotto poo-shape radio id suffix, or null
        String condText;   // TOILET: raw condition text read from Lukumi
        String label;      // for results
    }

    private static final Pattern HEADER_DATE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern FIELD_DT = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private final BridgeService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private boolean running = false;
    private SchoolDraft draft;
    private final ArrayDeque<WOp> queue = new ArrayDeque<WOp>();
    private WOp op;
    private int totalOps = 0, okCount = 0;
    private String curLabel = "";
    private final List<String> failNames = new ArrayList<String>();
    private final List<WOp> failedOps = new ArrayList<WOp>();
    private final List<WOp> writtenOps = new ArrayList<WOp>();
    private boolean verifyPhase = false;
    private int retryRound = 0;
    private int loopMs = 200;
    private int baseLoopMs = 200;
    private int diffCount = 0;
    private long startMs = 0;
    private int stage = 0, ticks = 0, opTicks = 0;
    private int cellPages = 0;
    private int cellReset = 0; // >=0: pager rewind attempts; -1: rewound
    private boolean wheelDumped = false;

    // stages
    private static final int ST_NAV = 0, ST_CELL = 1, ST_FORMWAIT = 2,
            ST_DT = 3, ST_MID = 4, ST_SAVE = 5, ST_NEXT = 6, ST_PRESCAN = 7, ST_MID2 = 8, ST_FORMCHECK = 9;
    // datetime sub-machine
    private int dtPhase = 0, dtTargetMin = 0, dtRetry = 0;
    private int[] dtTargets = null; // [month, day, hour, minute] for the audit phase
    private int dtAuditOk = 0;
    private String dtField = "";
    private int dtNextStage = ST_MID;
    private boolean dtCalibAfter = false;
    // wheel calibrator (temperature / milk)
    private int cStage = 0, cMode = 0, cDir = 0, cFireLeft = 0, cCycles = 0, cTicks = 0;
    private float cPxPerStep = 100f;
    private int cWheelCx = 0, cWheelCy = 0, cStepsAtOpen = 0;
    private int cDragLeft = 0, cStrokePx = 0, cSessionPx = 0;
    private boolean cStrokeUp = true;
    private int cBase = 0, cPerAction = 0;
    private boolean cInMid = false;

    public PapottoWriter(BridgeService svc) { this.svc = svc; }

    public boolean isRunning() { return running; }

    private void log(String s) {
        log.append(s).append('\n');
        android.util.Log.i("CombookBridge", s);
    }

    // Live progress for the status card and the floating chip, so the user
    // can follow the run while Papotto stays in front.
    private void progress() {
        int done = okCount + failNames.size() + skipCount;
        String t = "書込 " + done + "/" + totalOps
                + (failNames.isEmpty() ? "" : " ✗" + failNames.size())
                + (skipCount > 0 ? " スキップ" + skipCount : "")
                + (curLabel.isEmpty() ? "" : " ・ " + curLabel);
        Store.setProgress(svc, t);
        ProgressChip.show(svc, t);
    }

    private void progressEnd() {
        Store.setProgress(svc, "");
        ProgressChip.hide(svc);
    }

    // ---------------- ops ----------------

    static List<WOp> buildOps(SchoolDraft d) {
        List<WOp> ops = new ArrayList<WOp>();
        WOp toen = new WOp(); toen.kind = K_TOEN; toen.typeName = "登園"; toen.time = "08:00"; toen.label = "登園";
        ops.add(toen);
        List<int[]> sleepSpans = new ArrayList<int[]>();
        for (String s : d.sleeps) {
            String[] p = s.split("-");
            if (p.length != 2) continue;
            sleepSpans.add(new int[]{toMin(p[0]), toMin(p[1])});
            WOp b = new WOp(); b.kind = K_SLEEP_END; b.typeName = "起きる"; b.time = p[1];
            b.startTime = p[0]; b.label = "睡眠 " + p[0] + "〜" + p[1];
            ops.add(b);
        }
        WOp morningSnackOp = null, afternoonSnackOp = null, lunchOp = null;
        String lunchMemo = null;
        List<String> lunchParts = new ArrayList<String>();
        boolean lunchAllSame = true;
        String lunchFirst = null;
        for (String m : d.meals) {
            int eq = m.indexOf('=');
            if (eq < 0) continue;
            String cat = m.substring(0, eq), rating = m.substring(eq + 1);
            if (cat.equals("朝おやつ")) {
                String t = adjustMealTime("09:30", sleepSpans);
                WOp o = new WOp(); o.kind = K_SNACK; o.typeName = "おやつ"; o.time = t;
                o.endTime = plusMin(t, 30); o.memo = rating; o.label = "朝おやつ"; ops.add(o);
                morningSnackOp = o;
            } else if (cat.equals("おやつ") || cat.equals("午後おやつ") || cat.equals("夕方おやつ")) {
                // The nursery's afternoon snack: the report's category is the
                // plain "おやつ", at about 14:30 (spec). It used to
                // fall into the lunch bucket, so no snack record was written
                // and its rating leaked into the lunch memo.
                String t = adjustMealTime("14:30", sleepSpans);
                WOp o = new WOp(); o.kind = K_SNACK; o.typeName = "おやつ"; o.time = t;
                o.endTime = plusMin(t, 30); o.memo = rating; o.label = cat; ops.add(o);
                afternoonSnackOp = o;
            } else {
                String shortCat = cat.startsWith("昼") ? cat.substring(1) : cat;
                lunchParts.add(shortCat + "：" + rating);
                if (lunchFirst == null) lunchFirst = rating;
                else if (!lunchFirst.equals(rating)) lunchAllSame = false;
            }
        }
        if (!lunchParts.isEmpty()) {
            lunchMemo = lunchAllSame ? lunchFirst : joinList(lunchParts, "、");
            String t = adjustMealTime("11:30", sleepSpans);
            WOp o = new WOp(); o.kind = K_MEAL; o.typeName = "ごはん"; o.time = t;
            o.endTime = plusMin(t, 30); o.memo = lunchMemo; o.label = "昼ごはん"; ops.add(o);
            lunchOp = o;
        }
        for (String s : d.milks) {
            String[] p = s.split(" ");
            if (p.length != 2) continue;
            WOp o = new WOp(); o.kind = K_MILK; o.typeName = "哺乳瓶"; o.time = p[0];
            try { o.ml = Integer.parseInt(p[1]); } catch (Exception e) { continue; }
            o.endTime = plusMin(p[0], 15); o.label = "哺乳瓶 " + p[0]; ops.add(o);
        }
        for (String s : d.toilets) {
            String[] tp = s.split(" ", 2);
            WOp o = new WOp(); o.kind = K_TOILET; o.typeName = "うんち"; o.time = tp[0]; o.label = "うんち " + s;
            if (tp.length > 1 && !tp[1].isEmpty()) {
                o.condText = tp[1];
                o.cond = pooShapeRadioFor(tp[1]);
            }
            ops.add(o);
        }
        for (String s : d.temperatures) {
            String[] p = s.split(" ");
            if (p.length != 2) continue;
            WOp o = new WOp(); o.kind = K_TEMP; o.typeName = "体温"; o.time = p[0];
            try { o.temp = Double.parseDouble(p[1]); } catch (Exception e) { continue; }
            o.label = "体温 " + p[0]; ops.add(o);
        }
        WOp kon = new WOp(); kon.kind = K_KON; kon.typeName = "降園"; kon.time = "18:00"; kon.label = "降園";
        {
            // Teacher comment + mood (as text) go in the 降園 memo (user rules,
            // ). The diary record is no longer created.
            StringBuilder sb = new StringBuilder();
            if (!d.comment.isEmpty()) sb.append(d.comment);
            if (!d.mood.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("機嫌：").append(d.mood);
            }
            if (sb.length() > 0) kon.memo = sb.toString();
        }
        ops.add(kon);
        if (!d.mealMemo.isEmpty()) {
            // The meal memo goes to the record it refers to (user rule):
            // a snack mention -> that snack, otherwise the lunch record.
            WOp target = null;
            if (d.mealMemo.contains("朝おやつ")) target = morningSnackOp;
            if (target == null && d.mealMemo.contains("おやつ")) target = afternoonSnackOp;
            if (target == null) target = lunchOp;
            if (target != null) {
                target.memo = (target.memo == null || target.memo.isEmpty())
                        ? d.mealMemo : target.memo + "\n" + d.mealMemo;
            } else {
                kon.memo = (kon.memo == null || kon.memo.isEmpty())
                        ? d.mealMemo : kon.memo + "\n" + d.mealMemo;
            }
        }
        Collections.sort(ops, new Comparator<WOp>() {
            public int compare(WOp a, WOp b) { return a.time.compareTo(b.time); }
        });
        return ops;
    }

    static String moodButtonId(String mood) {
        if (mood == null || mood.isEmpty()) return null;
        if (mood.contains("ごきげん")) return "diaryModeVeryGoodButton";
        if (mood.contains("ふつう") || mood.contains("普通")) return "diaryModeNormalButton";
        if (mood.contains("良") || mood.contains("よい") || mood.contains("いい")) return "diaryModeGoodButton";
        if (mood.contains("悪") || mood.contains("わるい") || mood.contains("不機嫌")) return "diaryModeBadButton";
        return null;
    }

    static int toMin(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /**
     * Fixed meal times shift when the child is asleep at the default
     * time: the meal moves to 10 minutes after waking (user rule).
     * Re-checks after shifting in case spans are adjacent.
     */
    static String adjustMealTime(String hhmm, List<int[]> sleepSpans) {
        int t = toMin(hhmm);
        for (int guard = 0; guard < 8; guard++) {
            boolean shifted = false;
            for (int[] s : sleepSpans) {
                if (t >= s[0] && t < s[1]) { t = s[1] + 10; shifted = true; break; }
            }
            if (!shifted) break;
        }
        return String.format(Locale.JAPAN, "%02d:%02d", t / 60, t % 60);
    }

    static String plusMin(String hhmm, int add) {
        String[] p = hhmm.split(":");
        int m = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]) + add;
        return String.format(Locale.JAPAN, "%02d:%02d", (m / 60) % 24, m % 60);
    }

    /** Lukumi stool condition text -> Papotto poo-shape radio button.
     *  Lukumi: WATERY/SOFT/NORMAL/HARD. Papotto shapes: コロコロ(0)/バナナ(1)/
     *  ハンネリ(2)/泥状(3)/水状(4). Match liberally (Japanese or English labels). */
    private static String pooShapeRadioFor(String token) {
        if (token == null) return null;
        if (token.contains("水") || token.contains("Water") || token.contains("water")) return "pooShapeWaterRadioButton";
        if (token.contains("泥") || token.contains("Muddy") || token.contains("muddy")) return "pooShapeMuddyRadioButton";
        if (token.contains("やわらか") || token.contains("軟") || token.contains("Soft") || token.contains("soft")) return "pooShapeHanneriRadioButton";
        if (token.contains("かた") || token.contains("硬") || token.contains("Hard") || token.contains("hard") || token.contains("コロコロ")) return "pooShapeKorokoroRadioButton";
        if (token.contains("ふつう") || token.contains("普通") || token.contains("Normal") || token.contains("normal") || token.contains("バナナ")) return "pooShapeBananaRadioButton";
        return null;
    }

    private static String joinList(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(sep); sb.append(xs.get(i)); }
        return sb.toString();
    }

    // ---------------- main loop ----------------

    public void start() {
        draft = SchoolDraft.fromData(Store.getSchoolData(svc));
        if (draft.dateIso == null || draft.dateIso.isEmpty()) {
            Store.setWritePending(svc, false);
            Store.saveWriteResult(svc, false, "対象日が不明です（先に園の連絡帳を読み取ってください）");
            Toast.makeText(svc, "先に園の連絡帳を読み取ってください", Toast.LENGTH_LONG).show();
            openMain();
            return;
        }
        // sanity gate: never chase a date far from today (bad scans, year bugs)
        {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN);
            String todayIso = f.format(new java.util.Date());
            if (Math.abs(dayDiff(todayIso, draft.dateIso)) > 31) {
                Store.setWritePending(svc, false);
                Store.saveWriteResult(svc, false, "下書きの日付が異常です: " + draft.dateIso);
                Toast.makeText(svc, "下書きの日付が異常です: " + draft.dateIso + "。読み取り直してください", Toast.LENGTH_LONG).show();
                openMain();
                return;
            }
        }
        queue.clear();
        queue.addAll(buildOps(draft));
        totalOps = queue.size();
        okCount = 0;
        failNames.clear();
        failedOps.clear();
        writtenOps.clear();
        verifyPhase = false;
        retryRound = 0;
        baseLoopMs = Store.tickMs(svc);
        loopMs = baseLoopMs;
        diffCount = 0;
        running = true;
        stage = ST_NAV;
        ticks = 0;
        opTicks = 0;
        startMs = System.currentTimeMillis();
        navTaps = 0;
        awayTicks = 0;
        log.setLength(0);
        log("== 園→パパっと 書き込み開始 " + draft.dateIso + " " + totalOps + "件 ==");
        log("動作スピード: " + baseLoopMs + "ms間隔（設定画面のスピードが適用されます）");
        curLabel = "準備中";
        progress();
        Toast.makeText(svc, "パパっとへ書き込み中です。そのままお待ちください", Toast.LENGTH_LONG).show();
        handler.postDelayed(this::loop, 600);
    }

    private void loop() {
        if (!running) return;
        if (!Store.isWritePending(svc)) { stoppedByUser(); return; }
        try {
            step();
        } catch (Exception e) {
            log("例外: " + e);
            failOp("例外");
        }
        if (running) handler.postDelayed(this::loop, loopMs);
    }

    private int awayTicks = 0;
    private int navTaps = 0;

    private void step() {
        if (SchoolScanner.otherAppActive(svc, "jp.firstascent.papaikuji")) {
            if (++awayTicks == 1) log("前面が他アプリに切替: " + SchoolScanner.activePackage(svc));
            if (awayTicks * loopMs > 3000) { abortAll("別のアプリに切り替わったため中止しました"); return; }
            return;
        }
        awayTicks = 0;
        ticks++;
        opTicks++;
        if (stage != ST_NAV && op != null && opTicks > 900) { failOp("時間切れ"); return; }
        if (handleDialog()) return;
        switch (stage) {
            case ST_NAV: stepNav(); break;
            case ST_CELL: stepCell(); break;
            case ST_FORMWAIT: stepFormWait(); break;
            case ST_DT: stepDt(); break;
            case ST_MID: stepMid(); break;
            case ST_MID2: stepMid2(); break;
            case ST_FORMCHECK: stepFormCheck(); break;
            case ST_SAVE: stepSave(); break;
            case ST_NEXT: stepNext(); break;
            case ST_PRESCAN: stepPrescan(); break;
        }
    }

    // ---- date navigation on the timeline ----
    private void stepNav() {
        if (ticks > 300) { abortAll("パパっとのタイムラインが見つかりません"); return; }
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        if (SchoolScanner.findByIdSuffix(root, "actionEditSaveButton") != null) {
            // a leftover form from a failed op blocks the panel; discard it first
            discarding = true;
            AccessibilityNodeInfo del = SchoolScanner.findByIdSuffix(root, "actionEditDeleteButton");
            if (del != null) click(del);
            if (ticks % 25 == 1) log("残っていたフォームを破棄しています");
            return;
        }
        discarding = false;
        String header = findHeaderDate(root);
        if (header == null) return;
        if (header.equals(draft.dateIso)) {
            log("タイムライン日付OK: " + header);
            navOk();
            return;
        }
        int diff = dayDiff(header, draft.dateIso);
        if (diff == 0) { navOk(); return; }
        AccessibilityNodeInfo dateNode = findHeaderNode(root);
        if (dateNode == null) return;
        Rect dr = new Rect(); dateNode.getBoundsInScreen(dr);
        if (navTaps > 14) { abortAll("日付が大きくずれています（下書きの日付を確認してください）"); return; }
        AccessibilityNodeInfo chev = findChevron(root, dr, diff > 0);
        if (chev != null) {
            navTaps++;
            click(chev);
            log("日付移動 " + header + " → " + draft.dateIso + " (差 " + diff + ")");
        }
    }

    private static int dayDiff(String fromIso, String toIso) {
        try {
            String[] a = fromIso.split("-"), b = toIso.split("-");
            Calendar ca = Calendar.getInstance();
            ca.set(Integer.parseInt(a[0]), Integer.parseInt(a[1]) - 1, Integer.parseInt(a[2]), 0, 0, 0);
            Calendar cb = Calendar.getInstance();
            cb.set(Integer.parseInt(b[0]), Integer.parseInt(b[1]) - 1, Integer.parseInt(b[2]), 0, 0, 0);
            return (int) ((cb.getTimeInMillis() - ca.getTimeInMillis()) / 86400000L);
        } catch (Exception e) { return 0; }
    }

    private AccessibilityNodeInfo findHeaderNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && HEADER_DATE.matcher(t.toString()).find()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findHeaderNode(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private String findHeaderDate(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo n = findHeaderNode(root);
        if (n == null || n.getText() == null) return null;
        Matcher m = HEADER_DATE.matcher(n.getText().toString());
        if (!m.find()) return null;
        return String.format(Locale.JAPAN, "%04d-%02d-%02d",
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private AccessibilityNodeInfo findChevron(AccessibilityNodeInfo node, Rect dateRect, boolean next) {
        if (node == null) return null;
        AccessibilityNodeInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        List<AccessibilityNodeInfo> stack = new ArrayList<AccessibilityNodeInfo>();
        stack.add(node);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
            if (!n.isClickable()) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (Math.abs(r.centerY() - dateRect.centerY()) > 60) continue;
            if (next && r.centerX() <= dateRect.right) continue;
            if (!next && r.centerX() >= dateRect.left) continue;
            int d = Math.abs(r.centerX() - dateRect.centerX());
            if (d < bestDist) { bestDist = d; best = n; }
        }
        return best;
    }

    // ---- type cell ----
    private void stepCell() {
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        if (cellReset >= 0) {
            // The type panel remembers its page between runs; a run that ended on
            // the last page (e.g. after 日記) leaves every page-1 cell
            // unreachable for forward-only paging. Rewind to the first page
            // before searching. Backward scroll returns false on page 1.
            if (ticks % 3 == 0) {
                AccessibilityNodeInfo pager = SchoolScanner.findByIdSuffix(root, "actionButtonsViewPager");
                boolean more = pager != null
                        && pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                cellReset++;
                if (!more || cellReset >= 3) cellReset = -1;
            }
            return;
        }
        AccessibilityNodeInfo panel = SchoolScanner.findByIdSuffix(root, "actionButtonsViewPager");
        AccessibilityNodeInfo cell = findText(panel != null ? panel : root, op.typeName);
        if (cell != null) {
            click(cell);
            log(op.label + ": セル「" + op.typeName + "」をタップ");
            stage = ST_FORMWAIT;
            ticks = 0;
            return;
        }
        if (ticks % 3 != 0) return;
        if (cellPages >= 6) { failOp("セルが見つかりません"); return; }
        cellPages++;
        AccessibilityNodeInfo pager = SchoolScanner.findByIdSuffix(root, "actionButtonsViewPager");
        if (pager != null && pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            log(op.label + ": パネルを次ページへ (scroll action)");
            return;
        }
        Rect r = new Rect();
        if (pager != null) pager.getBoundsInScreen(r);
        else { r.set(0, 1500, 1080, 1900); }
        log(op.label + ": パネルを次ページへ (gesture)");
        swipe(r, true);
    }

    // ---- form wait ----
    private boolean formLooksFresh(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo f = SchoolScanner.findByIdSuffix(root, "timeStartActionTextView");
        if (f == null || f.getText() == null) return true; // cannot judge; let the DT engine decide
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})")
                .matcher(f.getText().toString());
        if (!m.find()) return true;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), 0);
        long diff = Math.abs(cal.getTimeInMillis() - System.currentTimeMillis());
        return diff <= 5 * 60 * 1000L;
    }

    private void stepFormWait() {
        if (ticks > 40) { failOp("入力画面が開きません"); return; }
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        if (SchoolScanner.findByIdSuffix(root, "actionEditSaveButton") != null) {
            log(op.label + ": 入力画面OK");
            if (op.kind != K_SLEEP_END && !formLooksFresh(root)) {
                // A fresh form initializes to the current time. Anything else
                // means an existing record's EDIT form opened (its row was
                // tapped instead of the panel cell) - saving would overwrite
                // that record, so abort this op instead.
                failOp("既存レコードの編集画面のため中止（上書き防止）");
                return;
            }
            if (op.kind == K_SLEEP_END) { stage = ST_MID; ticks = 0; }
            else beginDt("timeStartActionTextView", op.time, ST_MID);
        }
    }

    // ---- middle work after the start time is set ----
    /** Second datetime field of the single-op sleep entry: the finish time. */
    private void stepMid2() {
        beginDt("timeFinishActionTextView", op.time, ST_FORMCHECK);
    }

    /** Before saving the sleep record, re-read BOTH form fields: answering
     *  the pair dialog can re-initialize the form and silently change a field
     *  that was already set (both fields verified, yet no record
     *  was saved). A field that differs is set again instead of saving. */
    private void stepFormCheck() {
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        AccessibilityNodeInfo st = SchoolScanner.findByIdSuffix(root, "timeStartActionTextView");
        AccessibilityNodeInfo fn = SchoolScanner.findByIdSuffix(root, "timeFinishActionTextView");
        if (st == null || fn == null || st.getText() == null || fn.getText() == null) {
            if (ticks > 40) failOp("フォームの時刻欄が読めません");
            return;
        }
        String wantS = fieldText(draft.dateIso, op.startTime);
        String wantF = fieldText(draft.dateIso, op.time);
        String curS = st.getText().toString(), curF = fn.getText().toString();
        if (curS.equals(wantS) && curF.equals(wantF)) {
            stage = ST_SAVE; saveStage = 0; ticks = 0;
            return;
        }
        if (formCheckRounds >= 2) {
            failOp("フォームの時刻が合いません（開始=" + curS + " / 終了=" + curF + "）");
            return;
        }
        formCheckRounds++;
        if (!curS.equals(wantS)) {
            log(op.label + ": 保存前に開始欄を修整 " + curS + " -> " + wantS);
            beginDt("timeStartActionTextView", op.startTime, ST_FORMCHECK);
        } else {
            log(op.label + ": 保存前に終了欄を修整 " + curF + " -> " + wantF);
            beginDt("timeFinishActionTextView", op.time, ST_FORMCHECK);
        }
    }

    private static String fieldText(String iso, String hhmm) {
        String[] dp = iso.split("-");
        String[] tp = hhmm.split(":");
        return String.format(Locale.JAPAN, "%04d/%02d/%02d %02d:%02d",
                Integer.parseInt(dp[0]), Integer.parseInt(dp[1]), Integer.parseInt(dp[2]),
                Integer.parseInt(tp[0]), Integer.parseInt(tp[1]));
    }

    private void stepMid() {
        if (cInMid) { stepCalibrator(); return; }
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        switch (op.kind) {
            case K_SLEEP_START: {
                // Leave the finish at the form default. Papotto pairs the later
                // 起きる op with this still-open record and sets the finish there;
                // pinning finish=start closes the record and forces the
                // "change the registered 寝る?" dialog instead.
                stage = ST_SAVE;
                saveStage = 0;
                ticks = 0;
                break;
            }
            case K_TOILET: {
                if (op.cond != null) {
                    AccessibilityNodeInfo radio = SchoolScanner.findByIdSuffix(root, op.cond);
                    if (radio != null) {
                        click(radio);
                        AccessibilityNodeInfo stv = SchoolScanner.findByIdSuffix(root, "pooShapeTextView");
                        String sel = stv != null && stv.getText() != null ? stv.getText().toString() : "";
                        log(op.label + ": 形状「" + op.condText + "」を選択（checked=" + radio.isChecked() + " / 表示=" + sel + "）");
                    } else {
                        log(op.label + ": 形状ボタンが見つかりません（未設定のまま保存します）");
                    }
                } else if (op.condText != null) {
                    log(op.label + ": 形状「" + op.condText + "」の対応先が不明なため未設定で保存します");
                }
                setMemo(root);
                AccessibilityNodeInfo finT = SchoolScanner.findByIdSuffix(root, "timeFinishActionTextView");
                if (finT != null && finT.isVisibleToUser()) {
                    beginDt("timeFinishActionTextView", op.time, ST_SAVE);
                } else {
                    stage = ST_SAVE; ticks = 0;
                }
                break;
            }
            case K_TOEN:
            case K_KON: {
                setMemo(root);
                // finish area exists for some generic forms: pin it to the start
                AccessibilityNodeInfo fin = SchoolScanner.findByIdSuffix(root, "timeFinishActionTextView");
                if (fin != null && fin.isVisibleToUser()) {
                    beginDt("timeFinishActionTextView", op.time, ST_SAVE);
                } else {
                    stage = ST_SAVE; ticks = 0;
                }
                break;
            }
            case K_SLEEP_END: {
                // User rule: a sleep span is entered as ONE 起きる
                // record with BOTH fields set (start here, finish in stepMid2);
                // no 寝る record is created.
                beginDt("timeStartActionTextView", op.startTime, ST_MID2);
                break;
            }
            case K_SNACK:
            case K_MEAL: {
                if (op.kind == K_MEAL) ensureSegmentByText(root, "離乳食");
                setMemo(root);
                // meals/snacks: the finish time is left as the form default (user rule)
                stage = ST_SAVE;
                saveStage = 0;
                ticks = 0;
                break;
            }
            case K_MILK: {
                ensureSegmentByText(root, "ミルク");
                beginDtCalib("timeFinishActionTextView", op.endTime);
                break;
            }
            case K_TEMP:
                startCalibrator("bodyTemperatureTextView", (int) Math.round(op.temp * 10), 10);
                break;
            case K_DIARY: {
                setMemo(root);
                if (op.moodId != null) {
                    AccessibilityNodeInfo radio = SchoolScanner.findByIdSuffix(root, op.moodId);
                    if (radio != null) { click(radio); log(op.label + ": 機嫌を選択"); }
                }
                stage = ST_SAVE; ticks = 0;
                break;
            }
            default:
                stage = ST_SAVE; ticks = 0;
        }
    }

    private void ensureSegmentByText(AccessibilityNodeInfo root, String want) {
        AccessibilityNodeInfo s1 = SchoolScanner.findByIdSuffix(root, "actionEditSegment1");
        AccessibilityNodeInfo s2 = SchoolScanner.findByIdSuffix(root, "actionEditSegment2");
        log(op.label + ": セグメント seg1=" + segInfo(s1) + " seg2=" + segInfo(s2) + " 目標=" + want);
        AccessibilityNodeInfo[] segs = { s1, s2 };
        for (AccessibilityNodeInfo s : segs) {
            if (s == null) continue;
            String t = subtreeText(s);
            if (t != null && t.contains(want)) {
                if (!s.isChecked()) {
                    click(s);
                    log(op.label + ": セグメント「" + want + "」を選択");
                }
                return;
            }
        }
        log(op.label + ": セグメント「" + want + "」が見つかりません");
    }

    private String segInfo(AccessibilityNodeInfo s) {
        if (s == null) return "なし";
        return "[" + subtreeText(s) + (s.isChecked() ? " 選択中" : "") + "]";
    }

    private String subtreeText(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.getText() != null) return n.getText().toString();
        StringBuilder sb = new StringBuilder();
        gatherText(n, sb);
        return sb.length() == 0 ? null : sb.toString();
    }

    private void gatherText(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null) return;
        if (n.getText() != null) sb.append(n.getText());
        for (int i = 0; i < n.getChildCount(); i++) gatherText(n.getChild(i), sb);
    }

    private void setMemo(AccessibilityNodeInfo root) {
        if (op.memo == null || op.memo.isEmpty()) return;
        AccessibilityNodeInfo edit = SchoolScanner.findByIdSuffix(root, "actionEditMemoEditText");
        if (edit == null) return;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, op.memo);
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        log(op.label + ": メモを設定");
    }

    // ---- save ----
    private int saveStage = 0;
    private int formCheckRounds = 0;
    private void stepSave() {
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        if (saveStage == 0) {
            AccessibilityNodeInfo save = SchoolScanner.findByIdSuffix(root, "actionEditSaveButton");
            if (save == null) { // form already gone (e.g. DT return landed here after close)
                opOk();
                return;
            }
            click(save);
            log(op.label + ": 記録するをタップ");
            saveStage = 1;
            ticks = 0;
            return;
        }
        // Dialogs after save are handled globally by handleDialog (sleep-pair ->
        // "register as new", anything else -> Cancel). Nothing is tapped here:
        // 変更する would rewrite an existing record and OK could be a DELETE confirm.
        AccessibilityNodeInfo dlgBtn = null;
        boolean formGone = SchoolScanner.findByIdSuffix(root, "actionEditSaveButton") == null;
        if (dlgBtn != null && !formGone) {
            click(dlgBtn);
            log(op.label + ": 確認ダイアログを承認");
            ticks = 0;
            return;
        }
        if (formGone) { opOk(); return; }
        if (ticks > 50) { failOp("保存できません"); }
    }

    private void navOk() {
        if (!prescanDone) {
            curLabel = "既存レコードを確認中";
            progress();
            stage = ST_PRESCAN;
            ticks = 0;
            prescanIdle = 0;
            prescanTicks = 0;
            prescanPhase = 0;
            prescanScrolls = 0;
            prescanSawTarget = false;
            inTargetSection = false;
            sectionLeft = false;
            log("既存レコードを確認します");
        } else {
            stage = ST_NEXT;
            ticks = 0;
        }
    }

    private static final String[] TYPE_NAMES = { "登園", "降園", "寝る", "起きる", "ごはん", "おやつ", "哺乳瓶", "体温", "日記", "うんち" };
    private static final Pattern ROW_TIME = Pattern.compile("^\\d{1,2}:\\d{2}$");

    /** Scroll the day's timeline (up first, then down) collecting "HH:mm|種別" pairs so
     *  ops that are already registered (previous partial run, manual entry) are skipped
     *  instead of duplicated. */
    private int prescanScrolls = 0;
    private boolean inTargetSection = false;
    private boolean sectionLeft = false;

    private void stepPrescan() {
        prescanTicks++;
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root == null) return;
        int before = existing.size();
        collectExisting(root);
        if (existing.size() == before) prescanIdle++; else prescanIdle = 0;
        if (prescanPhase == 0 && prescanSawTarget && prescanIdle >= Math.max(2, ticksFor(400))) { prescanPhase = 1; prescanIdle = 0; }
        // The timeline is a continuous multi-day feed: stop as soon as the NEXT day's
        // section header passes (sectionLeft), with hard caps as a backstop.
        if (sectionLeft || (prescanPhase == 1 && prescanIdle >= Math.max(3, ticksFor(600)))
                || prescanScrolls > 40 || prescanTicks > 160 || existing.size() > 80) {
            prescanDone = true;
            log("既存レコード " + existing.size() + " 件を確認");
            stage = ST_NEXT;
            ticks = 0;
            return;
        }
        prescanScrolls++;
        AccessibilityNodeInfo list = findScrollableList(root);
        boolean scrolled = false;
        if (list != null) {
            scrolled = list.performAction(prescanPhase == 0
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        if (!scrolled) {
            int w = svc.getResources().getDisplayMetrics().widthPixels;
            int h = svc.getResources().getDisplayMetrics().heightPixels;
            if (prescanPhase == 0) swipeVertical(w / 2, (int) (h * 0.34), (int) (h * 0.62));
            else swipeVertical(w / 2, (int) (h * 0.62), (int) (h * 0.34));
        }
    }

    private static final Pattern DATE_HEADER = Pattern.compile("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    private void collectExisting(AccessibilityNodeInfo node) {
        if (node == null) return;
        CharSequence nt = node.getText();
        if (nt != null) {
            Matcher hm = DATE_HEADER.matcher(nt.toString().trim());
            if (hm.find()) {
                String iso = String.format(Locale.JAPAN, "%04d-%02d-%02d",
                        Integer.parseInt(hm.group(1)), Integer.parseInt(hm.group(2)), Integer.parseInt(hm.group(3)));
                boolean wasIn = inTargetSection;
                inTargetSection = iso.equals(draft.dateIso);
                if (inTargetSection) prescanSawTarget = true;
                // Only the downward pass may end the section: a snapshot taken
                // while scrolling up can span the section's lower boundary
                // (target rows plus the next day's header), which used to end
                // the scan after a single record (a verification run).
                if (wasIn && !inTargetSection && prescanPhase == 1) sectionLeft = true;
            }
        }
        if (inTargetSection) {
            String time = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null && c.getText() != null && ROW_TIME.matcher(c.getText().toString().trim()).matches()) {
                    time = c.getText().toString().trim();
                    break;
                }
            }
            if (time != null) {
                String type = findTypeIn(node, 0);
                if (type != null) {
                    String key = time + "|" + type;
                    existing.add(key);
                    if (!existingText.containsKey(key)) existingText.put(key, subtreeText(node));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collectExisting(node.getChild(i));
    }

    private String findTypeIn(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return null;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().trim();
            for (String tn : TYPE_NAMES) if (tn.equals(s)) return tn;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String r = findTypeIn(node.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollableList(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cn = node.getClassName();
        if (node.isScrollable() && cn != null && (cn.toString().contains("RecyclerView") || cn.toString().contains("ListView"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollableList(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private void swipeVertical(int x, int y1, int y2) {
        Path path = new Path();
        path.moveTo(x, y1);
        path.lineTo(x, y2);
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250));
        svc.dispatchGesture(b.build(), null, null);
    }

    /** When an existing record's VALUE differs from the draft (milk ml / body
     *  temperature), returns a note - the record is still skipped, never
     *  overwritten (same policy as the forward filler's single-value items). */
    /** Ticks needed to cover roughly ms of wall-clock time at the current speed. */
    private int ticksFor(int ms) {
        return Math.max(1, ms / Math.max(1, loopMs));
    }

    private String valueDiffNote(String key) {
        String rt = existingText.get(key);
        if (rt == null || op == null) return null;
        if (op.kind == K_MILK) {
            Matcher m = Pattern.compile("(\\d+)\\s*ml").matcher(rt);
            if (m.find() && Integer.parseInt(m.group(1)) != op.ml) {
                return "値が違います: 既存 " + m.group(1) + "ml / 下書き " + op.ml + "ml（上書きしません）";
            }
        } else if (op.kind == K_TEMP) {
            Matcher m = Pattern.compile("(\\d{2}\\.\\d)").matcher(rt);
            if (m.find() && Math.abs(Double.parseDouble(m.group(1)) - op.temp) > 0.049) {
                return "値が違います: 既存 " + m.group(1) + "℃ / 下書き " + op.temp + "℃（上書きしません）";
            }
        }
        return null;
    }

    private void stepNext() {
        op = queue.poll();
        saveStage = 0;
        formCheckRounds = 0;
        cellPages = 0;
        cellReset = 0;
        cInMid = false;
        opTicks = 0;
        if (op == null) {
            if (!failedOps.isEmpty() && retryRound < 2) {
                retryRound++;
                loopMs = retryRound == 1 ? (baseLoopMs < 300 ? 300 : 450) : 450;
                log("失敗した " + failedOps.size() + " 件を速度を落として再試行します（" + retryRound + " 回目）");
                for (WOp f : failedOps) queue.add(f);
                failedOps.clear();
                failNames.clear();
                // A failed op may actually have been saved; re-run the prescan
                // so those get skipped instead of duplicated.
                prescanDone = false;
                existing.clear();
                existingText.clear();
                stage = ST_NAV;
                ticks = 0;
                return;
            }
            if (!writtenOps.isEmpty() && !verifyPhase) {
                // Post-save verification (user request, ): re-scan the
                // timeline and confirm every "written" record is really there
                // before calling the run a success.
                verifyPhase = true;
                curLabel = "登録結果を確認中";
                progress();
                log("登録結果を確認します");
                prescanDone = false;
                existing.clear();
                existingText.clear();
                stage = ST_NAV;
                ticks = 0;
                return;
            }
            if (verifyPhase) verifyWritten();
            finishAll();
            return;
        }
        String key = displayTime(op.time) + "|" + op.typeName;
        if (existing.contains(key)) {
            skipCount++;
            String diff = valueDiffNote(key);
            if (diff != null) {
                diffCount++;
                log(op.label + ": 既に登録済みのためスキップ（" + diff + "）");
            } else {
                log(op.label + ": 既に登録済みのためスキップ");
            }
            curLabel = op.label + " スキップ";
            progress();
            op = null;
            stage = ST_NEXT;
            ticks = 0;
            return;
        }
        curLabel = op.label;
        progress();
        stage = ST_CELL;
        ticks = 0;
    }

    private static String displayTime(String hhmm) {
        return hhmm != null && hhmm.startsWith("0") ? hhmm.substring(1) : hhmm;
    }

    /** Papotto's delete confirmation ("育児メモを削除します") is modal: while it is up,
     *  the type panel behind it cannot be reached and every later op fails. While WE are
     *  discarding a form this writer created, OK confirms the discard; any other dialog
     *  is dismissed with Cancel -- a dialog is never confirmed otherwise. */
    private boolean handleDialog() {
        for (AccessibilityNodeInfo r : appRoots("jp.firstascent.papaikuji")) {
            // Papotto's sleep-pair dialog: changing the registered 寝る start would
            // corrupt an existing record, so always choose "register as new".
            AccessibilityNodeInfo asNew = findText(r, "新規に登録する");
            if (asNew != null && findText(r, "キャンセル") != null) {
                click(asNew);
                log("睡眠ペアの確認: 新規に登録するを選択");
                if (stage == ST_DT) {
                    // Answering can re-initialize the form; restart the datetime
                    // attempt so it re-reads the field instead of trusting
                    // wheel state from before the dialog.
                    dtPhase = 0;
                    dtInitVals = null;
                    dWheels.clear();
                    ticks = 0;
                    log("日時入力を最初からやり直します");
                }
                return true;
            }
            AccessibilityNodeInfo ok = findText(r, "OK");
            AccessibilityNodeInfo cancel = findText(r, "キャンセル");
            if (cancel != null && ok == null && findText(r, "変更する") != null) {
                click(cancel);
                log("確認ダイアログをキャンセルで閉じました");
                return true;
            }
            if (ok == null || cancel == null) continue;
            if (discarding) {
                click(ok);
                discarding = false;
                log("削除確認を承認（作成中の下書きを破棄）");
            } else {
                click(cancel);
                log("ダイアログをキャンセルで閉じました");
            }
            return true;
        }
        return false;
    }

    /** The milk/temperature "field" is a giant TextView (1080x930 in the device dump)
     *  whose tappable pill sits at its TOP; the bounds centre lands mid-form and
     *  does nothing. Tap the top strip instead. */
    private boolean tapFieldTop(Rect fr) {
        int strip = Math.min(90, Math.max(30, fr.height() / 8));
        return tapAt(new Rect(fr.left, fr.top, fr.right, fr.top + strip));
    }

    private boolean tapAt(Rect r) {
        Path path = new Path();
        path.moveTo(r.centerX(), r.centerY());
        path.lineTo(r.centerX() + 3, r.centerY());
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 120));
        return svc.dispatchGesture(b.build(), null, null);
    }

    private void dumpWindowsOnce() {
        if (cDumped) return;
        cDumped = true;
        try {
            List<AccessibilityWindowInfo> wins = svc.getWindows();
            StringBuilder sb = new StringBuilder("ウィンドウ診断:");
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    AccessibilityNodeInfo r = win.getRoot();
                    sb.append(" [").append(r == null ? "null" : String.valueOf(r.getPackageName()))
                            .append("/").append(r == null || r.getClassName() == null ? "?" : r.getClassName().toString())
                            .append(" wheel=").append(r != null && SchoolScanner.findByIdSuffix(r, "itemWheelView") != null)
                            .append("]");
                }
            }
            log(sb.toString());
        } catch (Exception e) {
            log("ウィンドウ診断失敗: " + e);
        }
    }

    private void opOk() {
        discarding = false;
        writtenOps.add(op);
        okCount++;
        log(op.label + ": ✓");
        curLabel = op.label + " ✓";
        progress();
        stage = ST_NEXT;
        ticks = 0;
    }

    private void failOp(String why) {
        if (op != null) {
            failNames.add(op.label);
            failedOps.add(op);
            log(op.label + ": ✗ " + why);
            curLabel = op.label + " ✗";
        }
        progress();
        // close an open picker first, then back out of the form via delete/cancel
        AccessibilityNodeInfo cancel = findIdInApp("pickerToolbarCancelButton");
        if (cancel != null) click(cancel);
        AccessibilityNodeInfo root = rootOf("jp.firstascent.papaikuji");
        if (root != null) {
            AccessibilityNodeInfo del = SchoolScanner.findByIdSuffix(root, "actionEditDeleteButton");
            if (del != null) {
                discarding = true;
                click(del);
            } else if (SchoolScanner.findByIdSuffix(root, "actionEditSaveButton") != null) {
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            }
        }
        stage = ST_NEXT;
        ticks = 0;
        cInMid = false;
    }

    private void stoppedByUser() {
        running = false;
        log("ユーザーにより中止");
        Store.saveLog(svc, log.toString());
        Store.saveWriteResult(svc, false, "中止しました ・ 所要" + Store.fmtDuration(System.currentTimeMillis() - startMs));
        Toast.makeText(svc, "書き込みを中止しました", Toast.LENGTH_LONG).show();
        openMain();
    }

    private void abortAll(String why) {
        running = false;
        Store.setWritePending(svc, false);
        Store.saveLog(svc, log.toString());
        Store.saveWriteResult(svc, false, why + " ・ 所要" + Store.fmtDuration(System.currentTimeMillis() - startMs));
        Toast.makeText(svc, "書き込みを中止しました: " + why, Toast.LENGTH_LONG).show();
        openMain();
    }

    /** Cross-check the ops counted as written against the re-scanned timeline.
     *  An op whose record is missing (or, for sleep, whose pair duration does
     *  not match the span) is moved to the failure list. */
    private void verifyWritten() {
        for (WOp o : writtenOps) {
            String key = displayTime(o.time) + "|" + o.typeName;
            boolean ok = existing.contains(key);
            if (ok && o.kind == K_SLEEP_END && o.startTime != null) {
                String rt = existingText.get(key);
                int span = toMin(o.time) - toMin(o.startTime);
                if (rt != null && span > 0 && !rt.contains(durationText(span))) ok = false;
            }
            if (!ok) {
                okCount--;
                failNames.add(o.label + "（登録を確認できませんでした）");
                log(o.label + ": 登録を確認できませんでした");
            }
        }
        writtenOps.clear();
    }

    private static String durationText(int min) {
        int h = min / 60, m = min % 60;
        if (h > 0 && m > 0) return h + "時間" + m + "分";
        if (h > 0) return h + "時間";
        return m + "分";
    }

    private void finishAll() {
        running = false;
        Store.setWritePending(svc, false);
        boolean allOk = failNames.isEmpty();
        String dur = Store.fmtDuration(System.currentTimeMillis() - startMs);
        String summary;
        if (allOk) {
            summary = okCount + "件" + (skipCount > 0 ? "（既存スキップ" + skipCount + "件" + (diffCount > 0 ? "・うち値違い" + diffCount + "件" : "") + "）" : "") + " ・ 所要" + dur;
            Store.markImported(svc, draft.dateIso);
        } else {
            summary = "成功" + okCount + "/" + totalOps + " ・ 失敗: " + joinList(failNames, "、") + (diffCount > 0 ? " ・ 値違い" + diffCount + "件" : "") + " ・ 所要" + dur;
        }
        log("== 完了 " + summary + " ==");
        Store.saveLog(svc, log.toString());
        Store.saveWriteResult(svc, allOk, summary);
        Toast.makeText(svc, allOk ? "パパっとへの書き込みが完了しました" : "一部の書き込みに失敗しました", Toast.LENGTH_LONG).show();
        openMain();
    }

    private void openMain() {
        progressEnd();
        android.content.Intent i = new android.content.Intent(svc, MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        svc.startActivity(i);
    }

    // ---------------- datetime sub-machine (parallel wheels) ----------------

    private static class DWheel {
        int col;            // 0=month 1=day 2=hour 3=minute (picker column order)
        int target;
        int wrap;
        int dirSign = 1;    // value change when firing SCROLL_FORWARD (+1/-1)
        int lastVal = -1;
        int pendingAction = 0; // +1 fired forward, -1 backward, 0 none yet
        int cool = 0;
        int noChange = 0;
        int blindLeft = 0;  // blind mode: signed steps still to fire
        boolean blind = false;
        boolean done = false;
    }

    private final List<DWheel> dWheels = new ArrayList<DWheel>();
    private int dtBlindDirSign = 1;
    private int[] dtInitVals = null;   // [month, day, hour, minute] from the form field
    private int dtDriveTicks = 0;
    private boolean dtFastTried = false;
    private boolean dtSequential = false; // retry strategy: one wheel at a time

    private void beginDt(String field, String time, int nextStage) {
        beginDtInternal(field, time, nextStage, false);
    }

    private void beginDtCalib(String field, String time) {
        beginDtInternal(field, time, ST_MID, true);
    }

    private void beginDtInternal(String field, String time, int nextStage, boolean calibAfter) {
        dtField = field;
        dtNextStage = nextStage;
        dtCalibAfter = calibAfter;
        String[] p = time.split(":");
        dtTargetMin = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        dtPhase = 0;
        dtRetry = 0;
        dtInitVals = null;
        dtFastTried = false;
        dtSequential = false;
        dtTargets = null;
        stage = ST_DT;
        ticks = 0;
    }

    private void stepDt() {
        switch (dtPhase) {
            case 0: { // read the field's current value, then open the picker
                AccessibilityNodeInfo f = findIdInApp(dtField);
                if (f == null) { if (ticks > 40) failOp("時刻欄が見つかりません"); return; }
                if (f.getText() != null) {
                    Matcher m = FIELD_DT.matcher(f.getText().toString());
                    if (m.find()) {
                        dtInitVals = new int[]{ Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)) };
                    }
                }
                if (!dtFastTried) {
                    dtFastTried = true;
                    String[] dp = draft.dateIso.split("-");
                    String targetText = String.format(Locale.JAPAN, "%04d/%02d/%02d %02d:%02d",
                            Integer.parseInt(dp[0]), Integer.parseInt(dp[1]), Integer.parseInt(dp[2]),
                            dtTargetMin / 60, dtTargetMin % 60);
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText);
                    boolean set = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    log(op.label + ": 直接書き込みを試行 (" + targetText + ") -> " + set);
                }
                click(f);
                dtPhase = 1;
                ticks = 0;
                break;
            }
            case 1: { // wait for the picker and discover its wheels
                AccessibilityNodeInfo proot = pickerRoot();
                if (proot == null) {
                    if (ticks > 40) { cancelPicker(); failOp("日時ピッカーが開きません"); }
                    return;
                }
                List<AccessibilityNodeInfo> wheels = pickerWheels(proot);
                if (wheels.size() != 4) {
                    if (ticks > 40) {
                        log(op.label + ": ピッカーのホイール数=" + wheels.size());
                        cancelPicker();
                        failOp("ピッカーのホイールを特定できません");
                    }
                    return;
                }
                if (dtFastTried && wheelsAtTarget(wheels)) {
                    log(op.label + ": 直接書き込みが反映されていました（ホイール確認済み）");
                    dWheels.clear();
                    dtPhase = 3;
                    ticks = 0;
                    break;
                }
                if (!buildDWheels(wheels)) return; // failOp already called
                // Drive order is smallest unit first (buildDWheels reverses the
                // list): Papotto's picker CARRIES - wrapping the minute wheel
                // 59->0 / 0->59 moves the hour by one (user report, ).
                // Setting the minute first and the hour after it overwrites any
                // carry drift; the same logic applies up the day/month columns.
                dtDriveTicks = 0;
                dtPhase = 2;
                ticks = 0;
                break;
            }
            case 2: { // drive the wheels. Default: all in parallel (fast). The
                      // picker's minute->hour carry can leave a wheel off target;
                      // the phase-4 verification then escalates the retry to
                      // sequential driving (smallest unit first, carry-proof).
                if (++dtDriveTicks > (dtSequential ? 600 : 300)) { cancelPicker(); failOp("時刻ホイールが時間切れ"); return; }
                AccessibilityNodeInfo proot = pickerRoot();
                if (proot == null) { dtPhase = 3; ticks = 0; break; }
                List<AccessibilityNodeInfo> wheels = pickerWheels(proot);
                if (wheels.size() != 4) return;
                boolean allDone = true;
                if (dtSequential) {
                    for (DWheel w : dWheels) {
                        if (w.blind) {
                            if (!w.done) { driveWheel(w, wheels.get(w.col)); allDone = false; break; }
                            continue;
                        }
                        int v = readWheelValue(wheels.get(w.col));
                        if (v < 0) { allDone = false; break; } // mid-fling: wait a tick
                        if (v != w.target) {
                            if (w.done) {
                                w.done = false;
                                w.lastVal = v;
                                w.pendingAction = 0;
                                log(op.label + ": ホイールのずれを検出 (col " + w.col + " = " + v + ")");
                            }
                            driveWheel(w, wheels.get(w.col));
                            allDone = false;
                            break; // larger units wait until this one is at target
                        }
                        w.done = true;
                    }
                } else {
                    for (DWheel w : dWheels) {
                        if (w.done && !w.blind) {
                            int v = readWheelValue(wheels.get(w.col));
                            if (v >= 0 && v != w.target) {
                                w.done = false;
                                w.lastVal = v;
                                w.pendingAction = 0;
                                log(op.label + ": ホイールのずれを検出 (col " + w.col + " = " + v + ")");
                            }
                        }
                        if (w.done) continue;
                        if (!driveWheel(w, wheels.get(w.col))) allDone = false;
                    }
                }
                if (allDone) { dtPhase = 5; dtAuditOk = 0; ticks = 0; }
                break;
            }
            case 5: { // audit BEFORE tapping Done (user request, ):
                      // re-read every wheel; fix only the wheels that are off
                      // target (in practice the hour, after a minute carry)
                if (ticks * loopMs > 6000 || dtTargets == null) { dtPhase = 3; ticks = 0; break; }
                AccessibilityNodeInfo proot = pickerRoot();
                if (proot == null) { dtPhase = 3; ticks = 0; break; }
                List<AccessibilityNodeInfo> wheels = pickerWheels(proot);
                if (wheels.size() != 4) return;
                int offCol = -1;
                boolean unreadable = false;
                for (int i = 0; i < 4; i++) {
                    int v = readWheelValue(wheels.get(i));
                    if (v < 0) { unreadable = true; continue; }
                    if (v != dtTargets[i]) { offCol = i; break; }
                }
                if (offCol < 0 && !unreadable) {
                    if (++dtAuditOk >= 2) {
                        log(op.label + ": ピッカー内で数値確認OK");
                        dtPhase = 3; ticks = 0;
                    }
                    break;
                }
                dtAuditOk = 0;
                if (offCol >= 0) {
                    DWheel w = null;
                    for (DWheel dw : dWheels) if (dw.col == offCol) { w = dw; break; }
                    if (w == null) {
                        w = new DWheel();
                        w.col = offCol;
                        w.target = dtTargets[offCol];
                        w.wrap = new int[]{12, 31, 24, 60}[offCol];
                        w.lastVal = readWheelValue(wheels.get(offCol));
                        dWheels.add(w);
                    }
                    w.done = false;
                    log(op.label + ": 完了前に修整します (col " + offCol + ")");
                    driveWheel(w, wheels.get(offCol));
                }
                break;
            }
            case 3: { // confirm
                AccessibilityNodeInfo done = findIdInApp("pickerToolbarDoneButton");
                if (done == null) done = findTextInApp("完了");
                if (done != null) click(done);
                dtPhase = 4;
                ticks = 0;
                break;
            }
            case 4: { // verify on the form field; a mismatch never proceeds to save
                if (pickerRoot() != null) { if (ticks > 40) failOp("ピッカーが閉じません"); return; }
                AccessibilityNodeInfo f = findIdInApp(dtField);
                if (f == null || f.getText() == null) {
                    if (ticks < 15) return; // a dialog may still be clearing
                    if (ticks > 40) failOp("時刻の読み戻しができません");
                    return;
                }
                Matcher m = FIELD_DT.matcher(f.getText().toString());
                if (!m.find()) { failOp("時刻の読み戻しができません: " + f.getText()); return; }
                int mo = Integer.parseInt(m.group(2)), dy = Integer.parseInt(m.group(3));
                int hh = Integer.parseInt(m.group(4)), mm = Integer.parseInt(m.group(5));
                String[] dp = draft.dateIso.split("-");
                boolean ok = mo == Integer.parseInt(dp[1]) && dy == Integer.parseInt(dp[2])
                        && hh * 60 + mm == dtTargetMin;
                if (ok) { log(op.label + ": 時刻OK " + f.getText()); dtProceed(); return; }
                log(op.label + ": 時刻不一致 " + f.getText());
                if (dtInitVals != null) {
                    int before = dtInitVals[2] * 60 + dtInitVals[3];
                    int actual = (hh * 60 + mm) - before;
                    int intended = dtTargetMin - before;
                    if (intended != 0 && actual != 0 && (intended > 0) != (actual > 0)) {
                        dtBlindDirSign = -dtBlindDirSign;
                        log(op.label + ": ホイール方向を反転学習");
                    }
                }
                if (dtRetry < 2) {
                    dtRetry++;
                    if (!dtSequential) {
                        dtSequential = true;
                        log(op.label + ": 並行駆動でずれたため、次は1本ずつ回して再試行します");
                    }
                    dtInitVals = null; dtPhase = 0; ticks = 0;
                }
                else failOp("時刻を設定できません");
                break;
            }
        }
    }

    /** True only when every picker column is readable AND already at the target. */
    private boolean wheelsAtTarget(List<AccessibilityNodeInfo> wheels) {
        String[] tp = draft.dateIso.split("-");
        int[] tgt = { Integer.parseInt(tp[1]), Integer.parseInt(tp[2]), dtTargetMin / 60, dtTargetMin % 60 };
        for (int i = 0; i < 4; i++) {
            int v = readWheelValue(wheels.get(i));
            if (v < 0 || v != tgt[i]) return false;
        }
        return true;
    }

    private boolean buildDWheels(List<AccessibilityNodeInfo> wheels) {
        dWheels.clear();
        String[] tp = draft.dateIso.split("-");
        int[] tgt = { Integer.parseInt(tp[1]), Integer.parseInt(tp[2]), dtTargetMin / 60, dtTargetMin % 60 };
        dtTargets = tgt;
        int[] wraps = { 12, 31, 24, 60 };
        for (int i = 0; i < 4; i++) {
            int cur = readWheelValue(wheels.get(i));
            int init = cur >= 0 ? cur : (dtInitVals != null ? dtInitVals[i] : -1);
            if (init == tgt[i]) continue;
            DWheel w = new DWheel();
            w.col = i;
            w.target = tgt[i];
            w.wrap = wraps[i];
            w.lastVal = cur;
            w.blind = cur < 0;
            if (w.blind) {
                if (init < 0) {
                    cancelPicker();
                    failOp("ホイールの現在値が読めません");
                    return false;
                }
                w.blindLeft = signedSteps(init, tgt[i], wraps[i]);
                w.dirSign = dtBlindDirSign;
            }
            dWheels.add(w);
        }
        Collections.reverse(dWheels); // smallest unit (minute) first
        log(op.label + ": ホイール計画 " + dWheels.size() + "本");
        return true;
    }

    /** One parallel step for one wheel. Returns true when this wheel is at target. */
    private boolean driveWheel(DWheel w, AccessibilityNodeInfo node) {
        if (w.cool > 0) { w.cool--; return false; }
        if (w.blind) {
            if (w.blindLeft == 0) { w.done = true; return true; }
            boolean forward = (w.blindLeft > 0) == (w.dirSign > 0);
            node.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            w.blindLeft += w.blindLeft > 0 ? -1 : 1;
            w.cool = 1;
            if (w.blindLeft == 0) { w.done = true; return true; }
            return false;
        }
        int val = readWheelValue(node);
        if (val < 0) return false;
        if (w.pendingAction != 0 && w.lastVal >= 0) {
            if (val != w.lastVal) {
                int delta = val - w.lastVal;
                if (Math.abs(delta) > w.wrap / 2) delta = delta > 0 ? delta - w.wrap : delta + w.wrap;
                int moved = delta > 0 ? 1 : -1;
                w.dirSign = moved * w.pendingAction;
                w.noChange = 0;
            } else {
                w.noChange++;
            }
            w.pendingAction = 0;
        }
        w.lastVal = val;
        if (val == w.target) { w.done = true; return true; }
        if (w.noChange > 4) {
            log(op.label + ": ホイールが動きません (col " + w.col + ")");
            w.done = true; // let the verify phase judge the result
            return true;
        }
        int steps = signedSteps(val, w.target, w.wrap);
        boolean forward = (steps > 0) == (w.dirSign > 0);
        if (!node.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            w.noChange++;
        }
        w.pendingAction = forward ? 1 : -1;
        w.cool = 1;
        return false;
    }

    private AccessibilityNodeInfo pickerRoot() {
        for (AccessibilityNodeInfo r : appRoots("jp.firstascent.papaikuji")) {
            if (SchoolScanner.findByIdSuffix(r, "pickerToolbarDoneButton") != null) return r;
            if (findText(r, "完了") != null && pickerWheels(r).size() >= 2) return r;
        }
        return null;
    }

    private void cancelPicker() {
        AccessibilityNodeInfo c = findIdInApp("pickerToolbarCancelButton");
        if (c == null) c = findTextInApp("キャンセル");
        if (c != null) click(c);
    }

    private AccessibilityNodeInfo findTextInApp(String text) {
        for (AccessibilityNodeInfo r : appRoots("jp.firstascent.papaikuji")) {
            AccessibilityNodeInfo n = findText(r, text);
            if (n != null) return n;
        }
        return null;
    }

    private static List<AccessibilityNodeInfo> pickerWheels(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        collectWheels(root, out);
        return out;
    }

    private static void collectWheels(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        CharSequence c = n.getClassName();
        if (c != null) {
            String s = c.toString();
            if (s.equals("android.widget.NumberPicker") || s.contains("WheelView")) out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectWheels(n.getChild(i), out);
    }

    private void dtProceed() {
        if (dtCalibAfter) {
            stage = ST_MID;
            ticks = 0;
            startCalibrator("milkValueTextView", op.ml / 10, 0.1);
            return;
        }
        stage = dtNextStage;
        if (stage == ST_SAVE) saveStage = 0;
        ticks = 0;
    }

    private static int signedSteps(int cur, int target, int wrap) {
        int d = target - cur;
        if (wrap > 0) {
            while (d > wrap / 2) d -= wrap;
            while (d < -wrap / 2) d += wrap;
        }
        return d;
    }

    private static void collectClass(AccessibilityNodeInfo node, String cls, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.getClassName() != null && cls.equals(node.getClassName().toString())) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectClass(node.getChild(i), cls, out);
    }

    private static int readWheelValue(AccessibilityNodeInfo np) {
        // the NumberPicker's centre EditText carries the displayed value
        List<AccessibilityNodeInfo> edits = new ArrayList<AccessibilityNodeInfo>();
        collectClass(np, "android.widget.EditText", edits);
        for (AccessibilityNodeInfo e : edits) {
            if (e.getText() != null) {
                try { return Integer.parseInt(e.getText().toString().trim()); }
                catch (Exception ignored) { }
            }
        }
        if (np.getText() != null) {
            try { return Integer.parseInt(np.getText().toString().trim()); }
            catch (Exception ignored) { }
        }
        return -1;
    }


    // ---------------- wheel calibrator (temperature / milk) ----------------

    private String cField = "";
    private int cTargetSteps = 0;
    private double cStepsPerUnit = 1.0;
    private boolean cVerify = false;

    private boolean cFastTried = false;
    private boolean discarding = false;
    private final java.util.Set<String> existing = new java.util.HashSet<String>();
    private final java.util.Map<String, String> existingText = new java.util.HashMap<String, String>();
    private boolean prescanDone = false;
    private int prescanIdle = 0, prescanTicks = 0, prescanPhase = 0, skipCount = 0;
    private boolean prescanSawTarget = false;
    private boolean cTapRetried = false;
    private boolean cDumped = false;
    private Rect cFieldBounds = null;
    private int cOpenMode = 0;
    private boolean cFieldDumped = false;

    /** The item picker may live in a window not attributed to the app package, and its
     *  wheel ID was only ever a static guess -- find the wheel by class in any window. */
    private AccessibilityNodeInfo findWheelAnywhere() {
        AccessibilityNodeInfo byId = findIdInApp("itemWheelView");
        if (byId != null) return byId;
        try {
            List<AccessibilityWindowInfo> wins = svc.getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    AccessibilityNodeInfo r = win.getRoot();
                    if (r == null) continue;
                    AccessibilityNodeInfo n = findByClass(r, "WheelView");
                    if (n != null) return n;
                }
            }
        } catch (Exception e) { /* fall through */ }
        return null;
    }

    static AccessibilityNodeInfo findByClass(AccessibilityNodeInfo node, String part) {
        if (node == null) return null;
        CharSequence cn = node.getClassName();
        if (cn != null && cn.toString().contains(part)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByClass(node.getChild(i), part);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo pickerDone() {
        AccessibilityNodeInfo d = findIdInApp("pickerToolbarDoneButton");
        if (d != null) return d;
        AccessibilityNodeInfo w = findWheelAnywhere();
        if (w == null) return null;
        AccessibilityNodeInfo r = w;
        while (r.getParent() != null) r = r.getParent();
        d = SchoolScanner.findByIdSuffix(r, "pickerToolbarDoneButton");
        if (d != null) return d;
        return findText(r, "完了");
    }

    private void dumpFieldOnce(AccessibilityNodeInfo f) {
        if (cFieldDumped) return;
        cFieldDumped = true;
        try {
            StringBuilder sb = new StringBuilder("ホイール欄の構造:");
            AccessibilityNodeInfo n = f;
            for (int i = 0; i < 7 && n != null; i++) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                sb.append(" [").append(n.getClassName() == null ? "?" : n.getClassName().toString())
                        .append(" id=").append(n.getViewIdResourceName())
                        .append(" clickable=").append(n.isClickable())
                        .append(" ").append(b.width()).append("x").append(b.height()).append("]");
                n = n.getParent();
            }
            log(sb.toString());
        } catch (Exception e) {
            log("ホイール欄の構造取得に失敗: " + e);
        }
    }

    private void logWinState(String tag) {
        try {
            StringBuilder sb = new StringBuilder("窓状態[").append(tag).append("]:");
            List<AccessibilityWindowInfo> wins = svc.getWindows();
            int n = 0;
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    AccessibilityNodeInfo r = win.getRoot();
                    if (r == null) continue;
                    n++;
                    sb.append(" [").append(r.getPackageName())
                            .append(" save=").append(SchoolScanner.findByIdSuffix(r, "actionEditSaveButton") != null)
                            .append(" wheel=").append(SchoolScanner.findByIdSuffix(r, "itemWheelView") != null)
                            .append(" done=").append(SchoolScanner.findByIdSuffix(r, "pickerToolbarDoneButton") != null)
                            .append(" field=").append(SchoolScanner.findByIdSuffix(r, cField) != null)
                            .append("]");
                }
            }
            sb.append(" 計").append(n);
            log(sb.toString());
        } catch (Exception e) {
            log("窓状態取得失敗: " + e);
        }
    }

    private void dumpTreeOnce() {
        if (cDumped) return;
        cDumped = true;
        for (AccessibilityNodeInfo r : appRoots("jp.firstascent.papaikuji")) {
            StringBuilder sb = new StringBuilder("ツリー:");
            int[] count = {0};
            dumpNode(r, 0, sb, count);
            log(sb.toString());
        }
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] > 220 || depth > 14) return;
        count[0]++;
        sb.append("\n");
        for (int i = 0; i < depth; i++) sb.append(" ");
        String cn = n.getClassName() == null ? "?" : n.getClassName().toString();
        int dot = cn.lastIndexOf('.');
        sb.append(dot >= 0 ? cn.substring(dot + 1) : cn);
        String id = n.getViewIdResourceName();
        if (id != null) {
            int s = id.lastIndexOf('/');
            sb.append("#").append(s >= 0 ? id.substring(s + 1) : id);
        }
        if (n.getText() != null) sb.append(" \"").append(n.getText()).append("\"");
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, sb, count);
    }

    private void startCalibrator(String fieldId, int targetSteps, double stepsPerUnit) {
        stage = ST_MID; // the calibrator is driven from stepMid
        cField = fieldId;
        cTargetSteps = targetSteps;
        cStepsPerUnit = stepsPerUnit;
        cStage = 0;
        cMode = 0;
        cCycles = 0;
        cVerify = false;
        cFastTried = false;
        cTapRetried = false;
        cDumped = false;
        cFieldBounds = null;
        cOpenMode = 0;
        cDir = 0;
        cCycles = 0;
        cPxPerStep = 100f;
        cStepsAtOpen = 0;
        cInMid = true;
        ticks = 0;
        log(op.label + ": ホイール調整開始 (目標ステップ " + targetSteps + ")");
    }

    private String replaceFirstNumber(String text) {
        Matcher m = NUM.matcher(text);
        if (!m.find()) return null;
        String num = cStepsPerUnit >= 1.0
                ? String.format(Locale.JAPAN, "%.1f", cTargetSteps / cStepsPerUnit)
                : Integer.toString((int) Math.round(cTargetSteps / cStepsPerUnit));
        return text.substring(0, m.start()) + num + text.substring(m.end());
    }

    private int readFieldSteps() {
        AccessibilityNodeInfo f = findIdInApp(cField);
        if (f == null || f.getText() == null) return Integer.MIN_VALUE;
        Matcher m = NUM.matcher(f.getText().toString());
        if (!m.find()) return Integer.MIN_VALUE;
        try {
            double v = Double.parseDouble(m.group(1));
            return (int) Math.round(v * cStepsPerUnit);
        } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    private void stepCalibrator() {
        // The item WheelView never appears in the accessibility tree (a plain
        // canvas-drawn view, pruned as not important for accessibility) - proven
        // by window-state logs: the picker DOES open on a field
        // click (pickerToolbarDoneButton appears, the form window is replaced),
        // but no wheel node exists anywhere. So the wheel is driven with
        // vertical drag gestures over its area, and the ONLY feedback is the
        // form field's value read back after tapping the picker's Done button.
        cTicks++;
        switch (cStage) {
            case 90: { // fast path after a successful direct write: confirm the picker
                AccessibilityNodeInfo done = pickerDone();
                if (done == null) { if (cTicks > 40) { cStage = 0; cTicks = 0; } return; }
                click(done);
                cStage = 91;
                cTicks = 0;
                break;
            }
            case 91: {
                if (pickerDone() != null) { if (cTicks > 40) { cStage = 0; cTicks = 0; } return; }
                int cur = readFieldSteps();
                if (cur == Integer.MIN_VALUE) { if (cTicks > 40) { cStage = 0; cTicks = 0; } return; }
                if (cur == cTargetSteps) {
                    log(op.label + ": 直接書き込みが反映されていました");
                    calibDone(true, "");
                } else {
                    cStage = 0;
                    cTicks = 0;
                }
                break;
            }
            case 0: { // one direct-write attempt, then open the picker
                if (!cFastTried) {
                    cFastTried = true;
                    AccessibilityNodeInfo f = findIdInApp(cField);
                    if (f != null && f.getText() != null) {
                        String targetText = replaceFirstNumber(f.getText().toString());
                        if (targetText != null) {
                            Bundle args = new Bundle();
                            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText);
                            boolean set = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                            log(op.label + ": 直接書き込みを試行 (" + targetText + ") -> " + set);
                            if (set) {
                                click(f);
                                cStage = 90;
                                cTicks = 0;
                                break;
                            }
                        }
                    }
                }
                int cur = readFieldSteps();
                if (cur == Integer.MIN_VALUE) { if (cTicks > 40) calibDone(false, "値が読めません"); return; }
                if (cur == cTargetSteps) { calibDone(true, ""); return; }
                cBase = cur;
                cStepsAtOpen = cur;
                AccessibilityNodeInfo f = findIdInApp(cField);
                if (f == null) { calibDone(false, "ホイール欄がありません"); return; }
                boolean clicked = click(f);
                log(op.label + ": ホイール欄クリック -> " + clicked);
                if (!clicked) { calibDone(false, "ホイールが開きません"); return; }
                cStage = 1;
                cTicks = 0;
                break;
            }
            case 1: { // picker open? plan this session's drags
                AccessibilityNodeInfo done = pickerDone();
                if (done == null) { if (cTicks > 40) calibDone(false, "ホイールが開きません"); return; }
                AccessibilityNodeInfo sheet = findIdInApp("design_bottom_sheet");
                if (sheet == null) { if (cTicks > 40) calibDone(false, "ホイールが開きません"); return; }
                Rect db = new Rect();
                done.getBoundsInScreen(db);
                Rect sb = new Rect();
                sheet.getBoundsInScreen(sb);
                int areaTop = db.bottom;
                int areaBottom = sb.bottom;
                if (areaBottom - areaTop < 60) { calibDone(false, "ホイール領域が取れません"); return; }
                cWheelCx = sb.centerX();
                cWheelCy = (areaTop + areaBottom) / 2;
                int remaining = cTargetSteps - cStepsAtOpen;
                if (remaining == 0) { cDragLeft = 0; cSessionPx = 0; cStage = 3; cTicks = 0; break; }
                int stepsThisSession;
                boolean up;
                if (cDir == 0) {
                    // probe: one intended step toward the target, dragging up
                    stepsThisSession = 1;
                    up = true;
                } else {
                    stepsThisSession = Math.abs(remaining);
                    up = (remaining > 0) == (cDir > 0);
                }
                int totalPx = Math.max(30, Math.round(stepsThisSession * cPxPerStep));
                int maxStroke = Math.max(60, areaBottom - areaTop - 16);
                int strokes = Math.max(1, (int) Math.ceil((double) totalPx / maxStroke));
                cStrokePx = totalPx / strokes;
                cStrokeUp = up;
                cDragLeft = strokes;
                cSessionPx = up ? totalPx : -totalPx;
                log(op.label + ": ホイールドラッグ計画 残" + remaining + " / " + strokes + "回 x " + cStrokePx + "px " + (up ? "上" : "下"));
                cStage = 2;
                cTicks = 0;
                break;
            }
            case 2: { // fire the planned drags, one per 2 ticks
                if (cDragLeft <= 0) { cStage = 3; cTicks = 0; break; }
                if (cTicks % 2 != 0) return;
                dragWheelStroke();
                cDragLeft--;
                if (cDragLeft <= 0) { cStage = 3; cTicks = 0; }
                break;
            }
            case 3: { // confirm with the picker's Done button
                if (cTicks < 3) return; // let the wheel settle/snap
                AccessibilityNodeInfo done = pickerDone();
                if (done == null) { cStage = 4; cTicks = 0; break; }
                click(done);
                cStage = 4;
                cTicks = 0;
                break;
            }
            case 4: { // read the field back: the only source of truth
                if (pickerDone() != null) { if (cTicks > 40) calibDone(false, "ピッカーが閉じません"); return; }
                int cur = readFieldSteps();
                if (cur == Integer.MIN_VALUE) { if (cTicks > 40) calibDone(false, "値が読めません"); return; }
                log(op.label + ": ホイール読み戻し " + cur + " (目標 " + cTargetSteps + ")");
                if (cur == cTargetSteps) { calibDone(true, ""); return; }
                int delta = cur - cStepsAtOpen;
                if (delta != 0 && cSessionPx != 0) {
                    // learn: what an UP drag does to the value, and px per step
                    cDir = (cSessionPx > 0) ? (delta > 0 ? 1 : -1) : (delta > 0 ? -1 : 1);
                    float learned = Math.abs(cSessionPx) / (float) Math.abs(delta);
                    if (learned >= 25f && learned <= 400f) cPxPerStep = learned;
                    log(op.label + ": ホイール学習 方向=" + cDir + " 1ステップ=" + Math.round(cPxPerStep) + "px");
                } else if (delta == 0) {
                    cPxPerStep = Math.min(400f, cPxPerStep * 1.6f);
                    log(op.label + ": ドラッグで動かず、距離を伸ばします (" + Math.round(cPxPerStep) + "px)");
                }
                cStepsAtOpen = cur;
                cBase = cur;
                cCycles++;
                if (cCycles > 5) { calibDone(false, "目標値に合いません (" + cur + ")"); return; }
                AccessibilityNodeInfo f = findIdInApp(cField);
                if (f == null) { calibDone(false, "ホイール欄がありません"); return; }
                click(f);
                cStage = 1;
                cTicks = 0;
                break;
            }
        }
    }

    private void dragWheelStroke() {
        try {
            int half = Math.max(15, cStrokePx / 2);
            int y1 = cStrokeUp ? cWheelCy + half : cWheelCy - half;
            int y2 = cWheelCy * 2 - y1;
            Path path = new Path();
            path.moveTo(cWheelCx, y1);
            path.lineTo(cWheelCx, y2);
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300));
            svc.dispatchGesture(b.build(), null, null);
        } catch (Exception e) { /* the read-back loop corrects any miss */ }
    }

    private void calibDone(boolean ok, String why) {
        cInMid = false;
        if (ok) {
            log(op.label + ": ホイール ✓");
            stage = ST_SAVE;
            saveStage = 0;
            ticks = 0;
        } else {
            failOp("ホイール: " + why);
        }
    }

    private void dumpWheel(AccessibilityNodeInfo w) {
        StringBuilder sb = new StringBuilder("WheelView dump: class=" + w.getClassName()
                + " text=" + w.getText() + " desc=" + w.getContentDescription()
                + " children=" + w.getChildCount() + " scrollable=" + w.isScrollable() + " actions=");
        try {
            for (AccessibilityNodeInfo.AccessibilityAction a : w.getActionList()) sb.append(a.getId()).append(',');
        } catch (Exception ignored) { }
        log(sb.toString());
        for (int i = 0; i < Math.min(6, w.getChildCount()); i++) {
            AccessibilityNodeInfo ch = w.getChild(i);
            if (ch != null) log("  child[" + i + "] class=" + ch.getClassName() + " text=" + ch.getText());
        }
    }

    // ---------------- window helpers ----------------

    private List<AccessibilityNodeInfo> appRoots(String pkg) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo win : wins) {
                AccessibilityNodeInfo r = win.getRoot();
                if (r != null && r.getPackageName() != null && pkg.equals(r.getPackageName().toString())) out.add(r);
            }
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo a = svc.getRootInActiveWindow();
            if (a != null) out.add(a);
        }
        return out;
    }

    private AccessibilityNodeInfo rootOf(String pkg) {
        List<AccessibilityNodeInfo> roots = appRoots(pkg);
        if (roots.isEmpty()) return null;
        for (AccessibilityNodeInfo r : roots) {
            if (SchoolScanner.findByIdSuffix(r, "actionEditSaveButton") != null) return r;
        }
        return roots.get(0);
    }

    private AccessibilityNodeInfo findIdInApp(String suffix) {
        for (AccessibilityNodeInfo r : appRoots("jp.firstascent.papaikuji")) {
            AccessibilityNodeInfo n = SchoolScanner.findByIdSuffix(r, suffix);
            if (n != null) return n;
        }
        return null;
    }

    static AccessibilityNodeInfo findText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && text.equals(t.toString().trim())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findText(node.getChild(i), text);
            if (r != null) return r;
        }
        return null;
    }

    static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 8 && n != null; i++) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    private void swipe(Rect r, boolean left) {
        int screenW = svc.getResources().getDisplayMetrics().widthPixels;
        float y = r.centerY();
        float x1 = Math.min(r.right - r.width() * 0.25f, screenW - 90);
        float x2 = Math.max(r.left + r.width() * 0.25f, 90);
        Path path = new Path();
        if (left) { path.moveTo(x1, y); path.lineTo(x2, y); }
        else { path.moveTo(x2, y); path.lineTo(x1, y); }
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200));
        svc.dispatchGesture(b.build(), null, null);
    }

    private void swipeVertical(Rect r, boolean up) {
        Path path = new Path();
        float x = r.centerX();
        float half = Math.max(30, r.height() / 4);
        if (up) { path.moveTo(x, r.centerY() + half); path.lineTo(x, r.centerY() - half); }
        else { path.moveTo(x, r.centerY() - half); path.lineTo(x, r.centerY() + half); }
        android.accessibilityservice.GestureDescription.Builder b =
                new android.accessibilityservice.GestureDescription.Builder();
        b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150));
        svc.dispatchGesture(b.build(), null, null);
    }
}
