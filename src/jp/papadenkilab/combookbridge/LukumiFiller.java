package jp.papadenkilab.combookbridge;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills Lukumi's 家庭から combook form from the aggregated draft.
 *
 * Lessons from device tests:
 *  - Lukumi's picker dialogs live in a SEPARATE accessibility window.
 *    getRootInActiveWindow() is unreliable here, so every phase explicitly
 *    uses either the form window (contains rvPresenceCombook) or the dialog
 *    window (contains the picker ids). Mixing them up made one op's picker
 *    engine drive another op's dialog.
 *  - Before each op, any leftover dialog is cancelled first.
 *  - Each dialog phase validates the dialog type (temperature dialog has
 *    npInteger, milk has npMilk, toilet has the hardness field, sleep has
 *    rgSleepType) before touching any wheel.
 *  - The picker engine re-reads after every action and learns when the
 *    movement direction is opposite to expectation.
 * The filler NEVER taps 送信確認.
 */
public class LukumiFiller {

    private static final String LUKUMI = "com.unifa_e.hiyoko.family";
    private static final Set<String> TITLES = new HashSet<String>(Arrays.asList(
            "登園", "欠席", "連絡欄", "体温", "便", "睡眠", "ミルク", "授乳",
            "夕食時刻", "夕食内容", "朝食時刻", "朝食内容", "機嫌", "投薬",
            "薬の内容", "通院内容", "お迎えする方", "お迎え時刻"));
    private static final String[] CONFIRM_TEXTS = {"決定", "登録", "保存", "追加", "完了", "OK", "はい"};

    private final BridgeService svc;
    private final Aggregator.Draft d;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private boolean running = false;
    private long emptyRootsSince = 0;

    private static class Op {
        final String name; final int kind;
        Op(String name, int kind) { this.name = name; this.kind = kind; }
    }
    private static final int K_TEMP = 1, K_STOOL = 2, K_SLEEP = 3, K_MILK = 4, K_BREAST = 5,
            K_DINNER_TIME = 6, K_DINNER_MEMO = 7, K_BREAKFAST_TIME = 8, K_BREAKFAST_MEMO = 9,
            K_MEDICINE = 10;
    private final ArrayDeque<Op> ops = new ArrayDeque<Op>();
    private int totalOps = 0;
    private int opNumber = 0;
    private int tickMs = 180;
    private int okCount = 0;
    private int skipCount = 0;
    private int skippedEntries = 0;
    private boolean opChecked = false;
    private boolean medWasOn = false;
    private final List<String> failNames = new ArrayList<String>();
private final List<Op> failedOps = new ArrayList<Op>();
private int retryCount = 0;
    private boolean abortedFlag = false;
private long fillStartMs = System.currentTimeMillis();
private boolean dateAsked = false;
private volatile String dateDecision = "";
private int dateSwitchStep = 0;
private boolean dayTapped = false;
private boolean dateDumpDone = false;
private int wrongDateTicks = 0;
private android.widget.LinearLayout confirmView;
private String dateFormDate = "";
private String dateWantDate = "";
    private Op op = null;
    private int stage = 0;
    private int ticks = 0;
    private int entryIndex = 0;

    private static class PickerTask {
        final int index; final String label; final int targetDisplay; final int step; final int wrapSteps;
        int method = 0; int noChange = 0; int actions = 0;
        int cooldown = 0; int beforeAction = Integer.MIN_VALUE; boolean expectForward = true;
        boolean announced = false;
        boolean setTextTried = false; boolean setTextFailed = false; boolean lastSetText = false;
        boolean dirVerified = false; int batchLeft = 0; boolean batchForward = false;
        boolean done = false; boolean invert = false;
        PickerTask(int index, String label, int target, int step, int wrap) {
            this.index = index; this.label = label; targetDisplay = target; this.step = step; wrapSteps = wrap;
        }
    }

    /** All NumberPicker nodes in DFS (layout) order: [hour, minute, extra...]. Ids are not exposed, order is. */
    static List<AccessibilityNodeInfo> findPickers(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        collectPickers(root, out);
        return out;
    }

    private static void collectPickers(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null && "android.widget.NumberPicker".contentEquals(cls)) { out.add(node); return; }
        for (int i = 0; i < node.getChildCount(); i++) collectPickers(node.getChild(i), out);
    }
    private final List<PickerTask> pickerTasks = new ArrayList<PickerTask>();
    private boolean pickerFailed = false;
    private boolean gestureUsedThisTick = false;

    public LukumiFiller(BridgeService svc, Aggregator.Draft d) {
        this.svc = svc;
        this.d = d;
    }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        emptyRootsSince = 0;
        tickMs = Store.tickMs(svc);
        okCount = 0;
        skipCount = 0;
        failNames.clear();
        abortedFlag = false;
        if (!d.temperature.isEmpty()) ops.add(new Op("体温", K_TEMP));
        ops.add(new Op("便", K_STOOL));
        if (!d.sleepStart.isEmpty() && !d.sleepEnd.isEmpty()) ops.add(new Op("睡眠", K_SLEEP));
        ops.add(new Op("ミルク", K_MILK));
        ops.add(new Op("授乳", K_BREAST));
        if (!d.dinnerTime.isEmpty()) ops.add(new Op("夕食時刻", K_DINNER_TIME));
        if (!d.dinnerMemo.isEmpty()) ops.add(new Op("夕食内容", K_DINNER_MEMO));
        if (!d.breakfastTime.isEmpty()) ops.add(new Op("朝食時刻", K_BREAKFAST_TIME));
        if (!d.breakfastMemo.isEmpty()) ops.add(new Op("朝食内容", K_BREAKFAST_MEMO));
        ops.add(new Op("投薬", K_MEDICINE));
        totalOps = ops.size();
        opNumber = 0;
        log("入力開始（登園日 " + d.targetDate + "）");
        toast("ルクミーの連絡帳（家庭から）画面を開いてください。検出したら自動入力します");
        showOverlay("\u9023\u7d61\u5e33\uff08\u5bb6\u5ead\u304b\u3089\uff09\u3092\u958b\u3044\u3066\u304f\u3060\u3055\u3044\u2026");
        stage = -1;
        ticks = 0;
        h.postDelayed(this::tick, 800);
        // (stage -3 resets the form scroll once the form is detected)
    }

    // ---------------- windows ----------------

    private List<AccessibilityNodeInfo> allRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> wins = svc.getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && r.getPackageName() != null
                            && LUKUMI.contentEquals(r.getPackageName().toString())) out.add(r);
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null && r.getPackageName() != null
                    && LUKUMI.contentEquals(r.getPackageName().toString())) out.add(r);
        }
        return out;
    }

    private AccessibilityNodeInfo formRoot(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) {
            if (findByIdSuffix(r, "rvPresenceCombook") != null) return r;
        }
        return null;
    }

    /** Root that currently holds a picker/editor, wherever it lives (own window or the form's). */
    private AccessibilityNodeInfo pickerRoot(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) {
            if (!findPickers(r).isEmpty()
                    || findByIdSuffix(r, "rgSleepType") != null || findByIdSuffix(r, "tilComment") != null) return r;
        }
        return null;
    }

    private boolean anyCancelVisible(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) if (findText(r, "キャンセル", true) != null) return true;
        return false;
    }

    /** Debug dump: which markers and texts each window root exposes. Logged on failures. */
    private String dumpWindows(List<AccessibilityNodeInfo> roots) {
        StringBuilder sb = new StringBuilder("窓構造:");
        String[] markers = {"rvPresenceCombook", "npHour", "npMinute", "npInteger", "npDecimal",
                "npMilk", "rgSleepType", "condition", "tilComment", "cpHave", "btnRemove"};
        for (int i = 0; i < roots.size(); i++) {
            AccessibilityNodeInfo r = roots.get(i);
            sb.append("\n [").append(i).append("] ");
            for (String m : markers) if (findByIdSuffix(r, m) != null) sb.append(m).append(" ");
            sb.append("pickers=").append(findPickers(r).size()).append(" ");
            List<String> texts = new ArrayList<String>();
            collectTexts(r, texts, 14);
            sb.append("| text: ");
            for (String t : texts) sb.append(t).append("/");
        }
        return sb.toString();
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, int max) {
        if (node == null || out.size() >= max) return;
        CharSequence t = node.getText();
        if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        for (int i = 0; i < node.getChildCount() && out.size() < max; i++) collectTexts(node.getChild(i), out, max);
    }

    // ---------------- main loop ----------------

    private void tick() {
        if (!running) return;
        if (!Store.isFillPending(svc)) {
            log("ユーザーにより中止");
            abortedFlag = true;
            finish();
            return;
        }
if (stage == -5) { // the date-confirmation wait does not need Lukumi windows
try { step(null, null); } catch (Exception e) { log("\u4f8b\u5916: " + e.getMessage()); }
if (running) h.postDelayed(this::tick, tickMs);
return;
}
List<AccessibilityNodeInfo> roots = allRoots();
        if (roots.isEmpty()) {
            if (SchoolScanner.otherAppActive(svc, LUKUMI)) {
                if (emptyRootsSince == 0) emptyRootsSince = System.currentTimeMillis();
                if (System.currentTimeMillis() - emptyRootsSince > 10000) {
                    log("別のアプリに切り替わったため中止");
                    abortedFlag = true;
                    finish();
                    return;
                }
            } else {
                emptyRootsSince = 0;
            }
            h.postDelayed(this::tick, tickMs + 120);
            return;
        }
        emptyRootsSince = 0;
        AccessibilityNodeInfo form = formRoot(roots);
        AccessibilityNodeInfo dlg = pickerRoot(roots);
        if (dlg == null && anyCancelVisible(roots)) {
            // a dialog without known markers: use the root that has キャンセル
            for (AccessibilityNodeInfo r : roots) if (findText(r, "キャンセル", true) != null) { dlg = r; break; }
        }
        try {
            step(form, dlg);
        } catch (Exception e) {
            log("例外: " + e.getMessage());
if (op != null) doneOp(false, "\u4f8b\u5916"); else nextOp();
        }
        if (running) h.postDelayed(this::tick, tickMs);
    }

    private void step(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg) {
        if (stage == -3) { // scroll the form back to the top before starting
            if (form == null) { stage = -1; ticks = 0; return; }
            AccessibilityNodeInfo list = findByIdSuffix(form, "rvPresenceCombook");
            if (list == null) list = findScrollable(form);
            boolean moved = false;
            if (list != null) {
                for (int i = 0; i < 3; i++) {
                    if (list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) moved = true;
                    else break;
                }
            }
            if (!moved || ++ticks > 24) {

                if (op == null) nextOp(); else toStage(0);
            }
            return;
        }
if (stage == -5) { // waiting for the date-confirmation dialog
String dec = dateDecision;
if ("switch".equals(dec)) {
log("\u30d5\u30a9\u30fc\u30e0\u306e\u65e5\u4ed8\u3092 " + dateWantDate + "\u306b\u5207\u308a\u66ff\u3048\u3066\u304b\u3089\u5165\u529b\u3057\u307e\u3059");
stage = -6;
dateSwitchStep = 0;
dayTapped = false;
ticks = 0;
} else if ("go".equals(dec)) {
log("\u65e5\u4ed8\u9055\u3044\u306e\u307e\u307e\u5165\u529b\u3092\u7d9a\u884c\u3057\u307e\u3059");
stage = -3;
ticks = 0;
} else if ("stop".equals(dec)) {
log("\u65e5\u4ed8\u9055\u3044\u306e\u305f\u3081\u4e2d\u6b62\u3057\u307e\u3057\u305f");
abortedFlag = true;
Store.saveFillResult(svc, false, "\u5165\u529b\u5148\u306e\u65e5\u4ed8\u9055\u3044\u3067\u4e2d\u6b62\uff08\u30d5\u30a9\u30fc\u30e0 " + dateFormDate + "\u3001\u5bfe\u8c61 " + dateWantDate + "\uff09" + elapsedSuffix());
finish();
} else if (++ticks > 900) { // about 3 minutes at the fastest tick: give up safely
log("\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u306e\u56de\u7b54\u304c\u306a\u3044\u305f\u3081\u4e2d\u6b62\u3057\u307e\u3057\u305f");
abortedFlag = true;
Store.saveFillResult(svc, false, "\u65e5\u4ed8\u78ba\u8a8d\u306e\u56de\u7b54\u306a\u3057\u3067\u4e2d\u6b62" + elapsedSuffix());
finish();
}
return;
}

if (stage == -6) { // switch the form date via Lukumi's own date picker, then fill
if (dateSwitchStep == 0) {
if (form == null) { if (++ticks > 60) abortSwitch("\u30d5\u30a9\u30fc\u30e0\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); return; }
AccessibilityNodeInfo dateNode = findDateTextNode(form);
if (dateNode == null) { abortSwitch("\u65e5\u4ed8\u8868\u793a\u304c\u30bf\u30c3\u30d7\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f"); return; }
if (!click(dateNode)) tapNodeCenter(dateNode);
log("\u65e5\u4ed8\u6b04\u3092\u30bf\u30c3\u30d7\u3057\u307e\u3057\u305f\u3002\u30ab\u30ec\u30f3\u30c0\u30fc\u3067 " + dateWantDate + " \u3092\u9078\u3073\u307e\u3059");
dateSwitchStep = 1;
ticks = 0;
return;
}
if (dateSwitchStep == 1) {
AccessibilityNodeInfo root = dlg != null ? dlg : form;
if (!dateDumpDone && root != null) { dateDumpDone = true; dumpDateDialog(root); }
if (root == null) { if (++ticks > 90) abortSwitch("\u30ab\u30ec\u30f3\u30c0\u30fc\u304c\u958b\u304d\u307e\u305b\u3093\u3067\u3057\u305f"); return; }
int[] target = targetYearMonthDay();
int[] ym = findPickerYearMonth(root);
if (ym != null && target != null && (ym[0] != target[0] || ym[1] != target[1])) {
int diff = (target[0] * 12 + target[1]) - (ym[0] * 12 + ym[1]);
AccessibilityNodeInfo nav = findMonthNav(root, diff > 0, ym);
if (nav == null) { abortSwitch("\u6708\u306e\u79fb\u52d5\u30dc\u30bf\u30f3\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093\u3067\u3057\u305f"); return; }
if (!click(nav)) tapNodeCenter(nav);
ticks = 0;
return;
}
if (!dayTapped) {
AccessibilityNodeInfo day = target == null ? null : findDayCell(root, target);
if (day != null) {
android.graphics.Rect db = new android.graphics.Rect();
day.getBoundsInScreen(db);
log("\u65e5\u4ed8\u30bb\u30eb\u3092\u30bf\u30c3\u30d7: text=" + day.getText() + " desc=" + day.getContentDescription() + " bounds=" + db);
if (!click(day)) tapNodeCenter(day);
dayTapped = true;
ticks = 0;
} else if (++ticks > 40) {
abortSwitch("\u65e5\u4ed8\u30bb\u30eb\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093\u3067\u3057\u305f");
}
return;
}
if (target != null && (headerShowsDate(root, target) || descShowsDate(root, target))) {
AccessibilityNodeInfo ok = findText(root, "OK", true);
if (ok != null) {
if (!click(ok)) tapNodeCenter(ok);
log("\u65e5\u4ed8\u3092 " + dateWantDate + " \u306b\u5207\u308a\u66ff\u3048\u307e\u3057\u305f");
dateSwitchStep = 2;
ticks = 0;
return;
}
}
if (++ticks > 12) { // selection state unreadable: press OK anyway; the reloaded form date is verified next
AccessibilityNodeInfo ok = findText(root, "OK", true);
if (ok != null) {
log("\u9078\u629e\u72b6\u614b\u3092\u8aad\u3081\u307e\u305b\u3093\u3067\u3057\u305f\u304cOK\u3092\u62bc\u3057\u307e\u3059\uff08\u5207\u308a\u66ff\u3048\u5f8c\u306e\u30d5\u30a9\u30fc\u30e0\u3067\u518d\u78ba\u8a8d\u3057\u307e\u3059\uff09");
if (!click(ok)) tapNodeCenter(ok);
dateSwitchStep = 2;
ticks = 0;
return;
}
abortSwitch("\u65e5\u4ed8\u306e\u78ba\u5b9a\u304c\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f");
}
return;
}
// dateSwitchStep == 2: wait for the reloaded form
if (form != null) {
String fd = findFormDate(form);
if (fd != null && !fd.equals(draftMonthDay())) {
if (++wrongDateTicks > 10) { abortSwitch("\u5225\u306e\u65e5\u4ed8\uff08" + fd + "\uff09\u306b\u5207\u308a\u66ff\u308f\u308a\u307e\u3057\u305f"); return; }
} else {
wrongDateTicks = 0;
}
if (fd != null && fd.equals(draftMonthDay())) {
log("\u5207\u308a\u66ff\u3048\u5f8c\u306e\u30d5\u30a9\u30fc\u30e0\u3092\u78ba\u8a8d: " + fd);
dateSwitchStep = 0;
dayTapped = false;
stage = -3;
ticks = 0;
return;
}
}
if (++ticks > 150) abortSwitch("\u5207\u308a\u66ff\u3048\u5f8c\u306e\u30d5\u30a9\u30fc\u30e0\u304c\u78ba\u8a8d\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f");
return;
}


if (stage == -2) { // date check: the opened form must be for the draft's day
if (form == null) { stage = -1; ticks = 0; return; }
String formDate = findFormDate(form);
String want = draftMonthDay();
if (formDate != null && want != null && !formDate.equals(want)) {
if (!dateAsked) {
dateAsked = true;
log("\u26a0 \u5165\u529b\u5148\u306e\u65e5\u4ed8\u304c\u9055\u3044\u307e\u3059: \u30d5\u30a9\u30fc\u30e0=" + formDate + " / \u5bfe\u8c61=" + want + "\u3002\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u3067\u78ba\u8a8d\u3057\u307e\u3059");
showOverlay("\u26a0 \u5165\u529b\u5148\u306e\u65e5\u4ed8\u304c\u9055\u3044\u307e\u3059\n\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u3067\u9078\u629e\u3057\u3066\u304f\u3060\u3055\u3044");
dateDecision = "";
showConfirmOverlay(formDate, want);
dateFormDate = formDate;
dateWantDate = want;
}
stage = -5;
ticks = 0;
return;
}
if (formDate == null && ++ticks <= 6) return; // give the header a moment to render
if (formDate == null) log("\uff08\u30d5\u30a9\u30fc\u30e0\u306e\u65e5\u4ed8\u8868\u793a\u3092\u78ba\u8a8d\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f\uff09");
else log("\u5165\u529b\u5148\u306e\u65e5\u4ed8OK: " + formDate);
stage = -3;
ticks = 0;
return;
}

        if (stage == -1) {
            if (form != null) {
                log("連絡帳フォームを検出");
                stage = -2;
                ticks = 0;
            } else if (++ticks > 220) {
                log("フォームが見つからずタイムアウト");
                finish();
            }
            return;
        }
        if (op == null) { finish(); return; }
        // stage 0 of every op: make sure no leftover dialog is open
        if (stage == 0 && dlg != null) {
            if (ticks == 0) log(op.name + "：開いていたダイアログを閉じます");
            cancelDialog(dlg);
            if (++ticks > 24) { doneOp(false, "ダイアログが閉じません"); }
            return;
        }
        ticks++;
        switch (op.kind) {
            case K_TEMP: stepTemp(form, dlg); break;
            case K_STOOL: stepEntries(form, dlg, d.stoolTimes, true, false); break;
            case K_MILK: stepEntries(form, dlg, d.milk, false, true); break;
            case K_BREAST: stepEntries(form, dlg, d.breast, false, false); break;
            case K_SLEEP: stepSleep(form, dlg); break;
            case K_DINNER_TIME: stepMealTime(form, dlg, d.dinnerTime); break;
            case K_BREAKFAST_TIME: stepMealTime(form, dlg, d.breakfastTime); break;
            case K_DINNER_MEMO: stepMemo(form, dlg, "夕食内容", d.dinnerMemo); break;
            case K_BREAKFAST_MEMO: stepMemo(form, dlg, "朝食内容", d.breakfastMemo); break;
            case K_MEDICINE: stepMedicine(form, dlg); break;
            default: nextOp(); break;
        }
    }

    private void nextOp() {
        op = ops.poll();
        entryIndex = 0;
        skippedEntries = 0;
        opChecked = false;
        medWasOn = false;
        pickerTasks.clear();
        ticks = 0;
        if (op == null) { finish(); return; }
        searchWrapped = false;
        bottomStreak = 0;
        stage = 0;
        opNumber++;
        log("\u25b6 " + op.name + " (" + opNumber + "/" + totalOps + ")");
        progressOverlay();
    }

    private void toStage(int s) { stage = s; ticks = 0; }

    private void doneOp(boolean ok, String note) {
        log((ok ? "\u2713 " : "\u2717 ") + op.name + (note == null || note.isEmpty() ? "" : "\uff1a" + note));
        lastResult = (ok ? "\u2713 " : "\u2717 ") + op.name + (note == null || note.isEmpty() ? "" : "\uff1a" + note);
if (ok) {
okCount++;
failNames.remove(op.name);
} else {
if (!failNames.contains(op.name)) failNames.add(op.name);
if (retryCount < 2) failedOps.add(op);
}
        nextOp();
    }

    /** Skip the current op because the form already holds the entry (user rule). */
    private void skipOp(String note) {
        log("- " + op.name + "：" + note + "（スキップ）");
        lastResult = "- " + op.name + "：" + note;
        skipCount++;
        nextOp();
    }

    /** All texts inside a row, joined - used to read back what the form already holds. */
    private String rowText(AccessibilityNodeInfo row) {
        if (row == null) return "";
        List<String> texts = new ArrayList<String>();
        collectTexts(row, texts, 12);
        StringBuilder sb = new StringBuilder();
        for (String t : texts) { if (sb.length() > 0) sb.append(" "); sb.append(t); }
        return sb.toString();
    }

    /** First HH:mm in a text, normalized to zero-padded 24h (午前/午後 aware), or null. */
    private String firstTimeIn(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        if (!m.find()) return null;
        int hh = Integer.parseInt(m.group(1));
        int mm = Integer.parseInt(m.group(2));
        if ((text.contains("午後") || text.contains("PM")) && hh < 12) hh += 12;
        if ((text.contains("午前") || text.contains("AM")) && hh == 12) hh = 0;
        return String.format(java.util.Locale.JAPAN, "%02d:%02d", hh, mm);
    }

    private void collectSpans(String text, Set<String> out) {
        if (text == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2}):(\\d{2})\\s*[〜~～]\\s*(\\d{1,2}):(\\d{2})").matcher(text);
        while (m.find()) {
            out.add(String.format(java.util.Locale.JAPAN, "%02d:%02d〜%02d:%02d",
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
    }

    private String entriesNote(int count) {
        return count + "件" + (skippedEntries > 0 ? "（入力済み" + skippedEntries + "件をスキップ）" : "");
    }

    /** True when an existing entry row already shows exactly this draft entry. */
    private boolean entryAlreadyMatches(AccessibilityNodeInfo row, String target, boolean isMilk, boolean isStool) {
        String rt = rowText(row);
        String shown = firstTimeIn(rt);
        if (shown == null) return false;
        String wantTime = Aggregator.floor5(target.split(" ")[0]);
        if (!shown.equals(wantTime)) return false;
        if (isMilk) {
            int ml = entryMl(target);
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)\\s*ml").matcher(rt);
            if (!mm.find() || Integer.parseInt(mm.group(1)) != ml) return false;
        }
        if (isStool) {
            String[] parts = target.split(" ");
            if (parts.length > 1) {
                String[] conds = {"ふつう", "普通", "やわらかい", "やわらかめ", "かたい", "かため", "水様", "下痢"};
                for (String c : conds) if (rt.contains(c)) return c.equals(parts[1]);
            }
        }
        return true;
    }

    private void finish() {
hideConfirmOverlay();
if (retryCount < 2 && !abortedFlag && !failedOps.isEmpty()) {
retryCount++;
int slower = retryCount == 1 ? (tickMs < 300 ? 300 : 450) : 450;
log("\u5931\u6557\u3057\u305f " + failedOps.size() + " \u9805\u76ee\u3092\u4e00\u6bb5\u9045\u3044\u901f\u5ea6\uff08" + slower + "ms\uff09\u3067\u518d\u8a66\u884c\u3057\u307e\u3059");
showOverlay("\u5931\u6557\u3057\u305f\u9805\u76ee\u3092\u9045\u3044\u901f\u5ea6\u3067\u3084\u308a\u76f4\u3057\u307e\u3059\u2026");
tickMs = slower;
for (Op f : failedOps) ops.add(f);
failedOps.clear();
op = null;
nextOp();
return;
}
        running = false;
        // safety: close any dialog we may have left open
        try {
            List<AccessibilityNodeInfo> roots = allRoots();
            AccessibilityNodeInfo dlg = pickerRoot(roots);
            if (dlg != null) cancelDialog(dlg);
        } catch (Exception ignored) { }
        Store.setFillPending(svc, false);
log("\u6240\u8981\u6642\u9593: " + Store.fmtDuration(System.currentTimeMillis() - fillStartMs));
        Store.saveLog(svc, log.toString());
        if (!abortedFlag) {
            if (failNames.isEmpty()) {
                Store.saveFillResult(svc, true, (skipCount > 0
                        ? "成功" + okCount + "・スキップ" + skipCount
                        : "\u5168" + totalOps + "\u9805\u76ee\u6210\u529f") + elapsedSuffix());
            } else {
                StringBuilder fs = new StringBuilder("\u5931\u6557\uff1a");
                for (int i = 0; i < failNames.size(); i++) {
                    if (i > 0) fs.append("\u3001");
                    fs.append(failNames.get(i));
                }
                fs.append("\uff08\u6210\u529f ").append(okCount).append("/").append(totalOps);
                if (skipCount > 0) fs.append("・スキップ").append(skipCount);
                fs.append("\uff09");
                Store.saveFillResult(svc, false, fs.toString() + elapsedSuffix());
            }
        }
        showOverlay("\u2705 \u81ea\u52d5\u5165\u529b\u304c\u7d42\u308f\u308a\u307e\u3057\u305f\uff08\u9001\u4fe1\u306f\u3057\u3066\u3044\u307e\u305b\u3093\uff09");
        hideOverlay(5000);
        toast("自動入力が終わりました。送信はしていません。内容を確認してください");
        android.content.Intent i = new android.content.Intent(svc, MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        svc.startActivity(i);
    }

    // ---------------- temperature ----------------

    private void stepTemp(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg) {
        switch (stage) {
            case 0: toStage(1); break; // stage 0 handled globally (dialog cleanup)
            case 1: {
                if (form == null) break;
                Section ts = findSection(form, "体温");
                if (ts != null && !ts.entries.isEmpty()) {
                    String cur = rowText(ts.entries.get(0));
                    boolean same = cur.contains(d.temperature + "℃");
                    skipOp("入力済み「" + cur + "」" + (same ? ""
                            : "（下書きは " + Aggregator.floor5(d.temperatureTime) + " " + d.temperature + "℃）"));
                    break;
                }
                AccessibilityNodeInfo add = findText(form, "体温を追加", false);
                if (add == null) { if (searchDone(form)) doneOp(false, "追加ボタンが見つかりません"); break; }
                click(add);
                toStage(2);
                break;
            }
            case 2: {
                if (dlg == null || findPickers(dlg).size() != 4) {
                    if (ticks > 30) { log(dumpWindows(allRoots())); doneOp(false, "体温ダイアログが出ません"); }
                    break;
                }
                double tv = Double.parseDouble(d.temperature);
                int ti = (int) tv;
                int td = (int) Math.round((tv - ti) * 10);
                PickerTask[] tt = timeTasks(d.temperatureTime);
                PickerTask[] all = new PickerTask[tt.length + 2];
                System.arraycopy(tt, 0, all, 0, tt.length);
                all[tt.length] = new PickerTask(2, "体温整数部", ti, 1, -1);
                all[tt.length + 1] = new PickerTask(3, "体温小数部", td, 1, -1);
                startPickers(all);
                toStage(3);
                break;
            }
            case 3:
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (runPickerEngine(dlg)) {
                    if (engineOkOrCancel(dlg)) toStage(4);
                    else doneOp(false, "ピッカーを設定できませんでした");
                } else if (ticks > 320) { cancelDialog(dlg); doneOp(false, "ピッカー設定タイムアウト"); }
                break;
            case 4:
                if (dlg == null) { doneOp(true, d.temperature + "℃"); break; }
                if (confirmDialog(dlg)) toStage(5);
                else if (ticks > 14) doneOp(false, "決定ボタンが見つかりません");
                break;
            case 5:
                if (dlg == null) doneOp(true, d.temperature + "℃");
                else if (ticks > 14) doneOp(false, "ダイアログが閉じません");
                break;
        }
    }

    // ---------------- entries (stool / milk / breast) ----------------

    private void stepEntries(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg,
                               List<String> values, boolean isStool, boolean isMilk) {
        int count = values.size();
        switch (stage) {
            case 0: toStage(1); break;
            case 1: { // chip
                if (form == null) break;
                Section s = findSection(form, op.name);
                if (s == null) { if (searchDone(form)) doneOp(false, "項目が見つかりません"); break; }
                if (s.headerRow != null) {
                    AccessibilityNodeInfo have0 = findByIdSuffix(s.headerRow, "cpHave");
                    AccessibilityNodeInfo none0 = findByIdSuffix(s.headerRow, "cpNone");
                    if (count == 0 && have0 != null && have0.isChecked()) {
                        skipOp("入力済み（明細あり）。下書きは「なし」");
                        break;
                    }
                    if (count == 0 && none0 != null && none0.isChecked()) {
                        skipOp("なし（選択済み）");
                        break;
                    }
                }
                AccessibilityNodeInfo chip = findByIdSuffix(s.headerRow, count > 0 ? "cpHave" : "cpNone");
                if (chip == null) { doneOp(false, "チップが見つかりません"); break; }
                if (!chip.isChecked()) click(chip);
                if (count == 0) doneOp(true, "なし");
                else toStage(2);
                break;
            }
            case 2: { // open entry #entryIndex
                if (form == null) break;
                Section s = findSection(form, op.name);
                if (s == null) { scrollForm(form); break; }
                if (entryIndex < s.entries.size()) {
                    if (entryAlreadyMatches(s.entries.get(entryIndex), values.get(entryIndex), isMilk, isStool)) {
                        log("  " + values.get(entryIndex) + " は入力済みのためスキップ");
                        skippedEntries++;
                        entryIndex++;
                        if (entryIndex >= count) doneOp(true, entriesNote(count));
                        else toStage(2);
                        break;
                    }
                    AccessibilityNodeInfo v = findByIdSuffix(s.entries.get(entryIndex), "tvValue");
                    click(v != null ? v : s.entries.get(entryIndex));
                    toStage(3);
                } else if (s.addRow != null) {
                    click(s.addRow);
                    toStage(25);
                } else if (ticks > 16) doneOp(false, "明細を追加できません");
                break;
            }
            case 25: { // after tapping add: a new entry appears, or its edit dialog opens directly
                if (dlg != null && !findPickers(dlg).isEmpty()) { toStage(3); break; }
                if (form == null) break;
                Section s = findSection(form, op.name);
                if (s != null && entryIndex < s.entries.size()) {
                    AccessibilityNodeInfo v = findByIdSuffix(s.entries.get(entryIndex), "tvValue");
                    click(v != null ? v : s.entries.get(entryIndex));
                    toStage(3);
                } else if (ticks > 14) doneOp(false, "追加した明細が見つかりません");
                break;
            }
            case 3: { // validate dialog type by picker count, then build picker tasks
                int pc = dlg == null ? -1 : findPickers(dlg).size();
                int expect = isMilk ? 3 : 2;
                if (pc != expect) {
                    if (ticks > 30) { log(dumpWindows(allRoots())); doneOp(false, "ダイアログが出ません"); }
                    break;
                }
                if (findByIdSuffix(dlg, "rgSleepType") != null) {
                    cancelDialog(dlg);
                    doneOp(false, "別のダイアログが開いていました");
                    break;
                }
                String t = values.get(entryIndex).split(" ")[0];
                PickerTask[] tt = timeTasks(t);
                if (isMilk) {
                    PickerTask[] all = new PickerTask[tt.length + 1];
                    System.arraycopy(tt, 0, all, 0, tt.length);
                    all[tt.length] = new PickerTask(2, "ml", entryMl(values.get(entryIndex)), 10, -1);
                    tt = all;
                }
                startPickers(tt);
                toStage(4);
                break;
            }
            case 4:
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (runPickerEngine(dlg)) {
                    if (engineOkOrCancel(dlg)) toStage(5);
                    else doneOp(false, "ピッカーを設定できませんでした");
                } else if (ticks > 320) { cancelDialog(dlg); doneOp(false, "ピッカー設定タイムアウト"); }
                break;
            case 5:
                if (dlg == null) { stage = 6; ticks = 0; break; }
                if (confirmDialog(dlg)) toStage(6);
                else if (ticks > 14) doneOp(false, "決定ボタンが見つかりません");
                break;
            case 6:
                if (dlg == null) {
                    entryIndex++;
                    if (entryIndex >= count) doneOp(true, entriesNote(count));
                    else toStage(2);
                } else if (ticks > 14) doneOp(false, "ダイアログが閉じません");
                break;
        }
    }

    // ---------------- sleep ----------------

    private void stepSleep(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg) {
        switch (stage) {
            case 0: toStage(1); break;
            case 1: { // delete ALL existing sleep entries (carried-over defaults), then add fresh
                if (!opChecked) {
                    opChecked = true;
                    if (form != null) {
                        Section s0 = findSection(form, "睡眠");
                        if (s0 != null) {
                            Set<String> spans = new HashSet<String>();
                            collectSpans(rowText(s0.headerRow), spans);
                            for (AccessibilityNodeInfo e : s0.entries) collectSpans(rowText(e), spans);
                            String want = Aggregator.floor5(d.sleepStart) + "〜" + Aggregator.floor5(d.sleepEnd);
                            if (spans.size() == 1 && spans.contains(want)) {
                                skipOp("入力済み " + want);
                                break;
                            }
                        }
                    }
                }
                if (dlg != null) { // a delete-confirmation dialog may appear
                    AccessibilityNodeInfo del = findText(dlg, "\u524a\u9664", true);
                    if (del != null) click(del);
                    else confirmDialog(dlg);
                    break;
                }
                if (form == null) break;
                Section s = findSection(form, "\u7761\u7720");
                if (s == null) { if (searchDone(form)) doneOp(false, "\u9805\u76ee\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); break; }
                AccessibilityNodeInfo rm = null;
                AccessibilityNodeInfo hv = findByIdSuffix(s.headerRow, "tvValue");
                AccessibilityNodeInfo hrm = findByIdSuffix(s.headerRow, "btnRemove");
                if (hrm != null && hv != null && hv.getText() != null && hv.getText().toString().contains(":")) rm = hrm;
                if (rm == null) {
                    for (AccessibilityNodeInfo e : s.entries) {
                        AccessibilityNodeInfo r = findByIdSuffix(e, "btnRemove");
                        if (r != null) { rm = r; break; }
                    }
                }
                if (rm != null) {
                    click(rm);
                    log("  \u65e2\u5b58\u306e\u7761\u7720\u660e\u7d30\u3092\u524a\u9664");
                    toStage(16);
                    break;
                }
                if (s.addRow != null) { click(s.addRow); toStage(2); }
                else if (!s.entries.isEmpty()) {
                    AccessibilityNodeInfo v = findByIdSuffix(s.entries.get(0), "tvValue");
                    click(v != null ? v : s.entries.get(0));
                    toStage(2);
                } else if (ticks > 16) doneOp(false, "\u7761\u7720\u306e\u660e\u7d30\u304c\u958b\u3051\u307e\u305b\u3093");
                break;
            }
            case 16: // wait for the deletion to settle, then re-check
                if (ticks >= 2) toStage(1);
                break;
            case 2: {
                if (dlg == null || findByIdSuffix(dlg, "rgSleepType") == null) {
                    if (ticks > 30) {
                        // tapping the value may not open an editor; try removing then adding
                        if (form != null) {
                            Section s = findSection(form, "睡眠");
                            if (s != null && !s.entries.isEmpty()) {
                                AccessibilityNodeInfo rm = findByIdSuffix(s.entries.get(0), "btnRemove");
                                if (rm != null) { click(rm); toStage(1); break; }
                            }
                        }
                        doneOp(false, "睡眠ダイアログが出ません");
                    }
                    break;
                }
                AccessibilityNodeInfo rb = findByIdSuffix(dlg, "rbSleepFrom");
                if (rb != null && !rb.isChecked()) { click(rb); break; }
                startPickers(timeTasks(d.sleepStart));
                toStage(3);
                break;
            }
            case 3:
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (runPickerEngine(dlg)) {
                    if (engineOkOrCancel(dlg)) toStage(4);
                    else doneOp(false, "開始時刻を設定できませんでした");
                } else if (ticks > 320) { cancelDialog(dlg); doneOp(false, "開始時刻タイムアウト"); }
                break;
            case 4: {
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                AccessibilityNodeInfo rb = findByIdSuffix(dlg, "rbSleepTo");
                if (rb != null && !rb.isChecked()) { click(rb); break; }
                startPickers(timeTasks(d.sleepEnd));
                toStage(5);
                break;
            }
            case 5:
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (runPickerEngine(dlg)) {
                    if (engineOkOrCancel(dlg)) toStage(6);
                    else doneOp(false, "終了時刻を設定できませんでした");
                } else if (ticks > 320) { cancelDialog(dlg); doneOp(false, "終了時刻タイムアウト"); }
                break;
            case 6:
                if (dlg == null) { doneOp(true, d.sleepStart + "〜" + d.sleepEnd); break; }
                if (confirmDialog(dlg)) toStage(7);
                else if (ticks > 14) doneOp(false, "決定ボタンが見つかりません");
                break;
            case 7:
                if (dlg == null) doneOp(true, d.sleepStart + "〜" + d.sleepEnd);
                else if (ticks > 14) doneOp(false, "ダイアログが閉じません");
                break;
        }
    }

    // ---------------- meal time / memo / medicine ----------------

    private int mealRetry = 0;
    private boolean mealMinBoxTapped = false;

    private void stepMealTime(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg, String time) {
        String f5 = Aggregator.floor5(time);
        int hour24 = Aggregator.hourOf(f5);
        int minute = Aggregator.minuteOf(f5);
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        String ampm = hour24 >= 12 ? "PM" : "AM";
        switch (stage) {
            case 0: mealRetry = 0; mealMinBoxTapped = false; toStage(1); break;
            case 1: {
                if (form == null) break;
                AccessibilityNodeInfo row = findRowByTitle(form, op.name);
                if (row == null) { if (searchDone(form)) doneOp(false, "項目が見つかりません"); break; }
                AccessibilityNodeInfo v = findByIdSuffix(row, "tvValue");
                String curMt = (v != null && v.getText() != null) ? v.getText().toString() : "";
                String shownMt = firstTimeIn(curMt);
                if (shownMt != null && shownMt.equals(f5)) { skipOp("入力済み " + curMt.trim()); break; }
                click(v != null ? v : row);
                toStage(2);
                break;
            }
            case 2: { // Material clock picker (or NumberPicker fallback)
                if (dlg == null) { if (ticks > 30) doneOp(false, "\u6642\u523b\u30c0\u30a4\u30a2\u30ed\u30b0\u304c\u51fa\u307e\u305b\u3093"); break; }
                if (findPickers(dlg).size() >= 2) { startPickers(timeTasks(time)); toStage(8); break; }
                if (findText(dlg, String.valueOf(hour12), true) != null) { toStage(3); break; }
                // minute dial already open: tap the header hour box to switch back to hour mode
                AccessibilityNodeInfo hhBox = findText(dlg, String.format(java.util.Locale.JAPAN, "%02d", hour12), true);
                if (hhBox != null && findText(dlg, "55", true) != null) { tapNodeCenter(hhBox); break; }
                // text-input mode leftover: switch back to the clock
                AccessibilityNodeInfo clockIcon = findByDesc(dlg, "\u6642\u8a08");
                if (clockIcon != null && ticks < 8) { click(clockIcon); break; }
                if (ticks > 24) { log(dumpWindows(allRoots())); cancelDialog(dlg); doneOp(false, "\u6642\u8a08\u76e4\u9762\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); }
                break;
            }
            case 3: { // tap the hour label on the dial
                if (dlg == null) { doneOp(false, "\u30c0\u30a4\u30a2\u30ed\u30b0\u304c\u6d88\u3048\u307e\u3057\u305f"); break; }
                if (ticks < 2) break;
                AccessibilityNodeInfo hn = findTextLowest(dlg, String.valueOf(hour12));
                if (hn == null) { if (ticks > 16) { log(dumpWindows(allRoots())); doneOp(false, "\u6642\u306e\u76ee\u76db\u308a\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); } break; }
                tapNodeCenter(hn);
                toStage(4);
                break;
            }
            case 4: { // tap the minute label on the dial (labels are zero-padded: 00, 05, ...)
                if (dlg == null) { doneOp(false, "\u30c0\u30a4\u30a2\u30ed\u30b0\u304c\u6d88\u3048\u307e\u3057\u305f"); break; }
                if (ticks < 3) break;
                String mmLabel = String.format(java.util.Locale.JAPAN, "%02d", minute);
                AccessibilityNodeInfo mn = findTextLowest(dlg, mmLabel);
                if (mn != null) { tapNodeCenter(mn); toStage(5); break; }
                // still in hour mode (the tapped hour was already selected): tap the header minute box
                if (!mealMinBoxTapped && ticks >= 4 && findText(dlg, "12", true) != null) {
                    AccessibilityNodeInfo box = headerMinuteBox(dlg);
                    if (box != null) { tapNodeCenter(box); mealMinBoxTapped = true; toStage(4); break; }
                }
                if (ticks > 20) { log(dumpWindows(allRoots())); doneOp(false, "\u5206\u306e\u76ee\u76db\u308a\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093"); }
                break;
            }
            case 5: { // AM/PM
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (ticks < 2) break;
                AccessibilityNodeInfo ap = findText(dlg, ampm, true);
                if (ap != null && !ap.isChecked() && !ap.isSelected()) click(ap);
                toStage(6);
                break;
            }
            case 6: { // verify the header shows the target, then OK
                if (dlg == null) { doneOp(true, f5); break; }
                if (ticks < 2) break;
                List<String> texts = new ArrayList<String>();
                collectTexts(dlg, texts, 20);
                String hh = String.format(java.util.Locale.JAPAN, "%02d", hour12);
                String mm = String.format(java.util.Locale.JAPAN, "%02d", minute);
                boolean okDisp = texts.contains(hh) && texts.contains(mm);
                if (!okDisp && mealRetry < 1) { mealRetry++; log("  盤面の表示が目標と違うため再タップします"); toStage(3); break; }
                if (!okDisp) { log(dumpWindows(allRoots())); cancelDialog(dlg); doneOp(false, "盤面で時刻を合わせられませんでした"); break; }
                if (confirmDialog(dlg)) toStage(7);
                else if (ticks > 14) doneOp(false, "OKボタンが見つかりません");
                break;
            }
            case 7:
                if (dlg == null) doneOp(true, f5);
                else if (ticks > 14) doneOp(false, "ダイアログが閉じません");
                break;
            case 8: // NumberPicker fallback: engine
                if (dlg == null) { doneOp(false, "ダイアログが消えました"); break; }
                if (runPickerEngine(dlg)) {
                    if (engineOkOrCancel(dlg)) toStage(9);
                    else doneOp(false, "ピッカーを設定できませんでした");
                } else if (ticks > 320) { cancelDialog(dlg); doneOp(false, "ピッカータイムアウト"); }
                break;
            case 9:
                if (dlg == null) { doneOp(true, f5); break; }
                if (confirmDialog(dlg)) toStage(7);
                else if (ticks > 14) doneOp(false, "決定ボタンが見つかりません");
                break;
        }
    }

    /** Deepest node whose subtree shows the dial numbers 12/3/6/9. */
    /** The header minute box: of the two topmost 2-digit labels, the right one. */
    static AccessibilityNodeInfo headerMinuteBox(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> hits = new ArrayList<AccessibilityNodeInfo>();
        collectDigitNodes(root, hits);
        AccessibilityNodeInfo best = null;
        int bestY = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : hits) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY() < bestY) { bestY = r.centerY(); best = n; }
        }
        if (best == null) return null;
        Rect br = new Rect();
        best.getBoundsInScreen(br);
        for (AccessibilityNodeInfo n : hits) {
            if (n == best) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (Math.abs(r.centerY() - br.centerY()) < 40 && r.centerX() > br.centerX()) return n;
        }
        return null;
    }

    static void collectDigitNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.toString().trim().matches("\\d{2}")) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectDigitNodes(node.getChild(i), out);
    }


    static AccessibilityNodeInfo findTextLowest(AccessibilityNodeInfo root, String exact) {
        List<AccessibilityNodeInfo> hits = new ArrayList<AccessibilityNodeInfo>();
        collectTextHits(root, exact, hits);
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo n : hits) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY() > bestY) { bestY = r.centerY(); best = n; }
        }
        return best;
    }

    static void collectTextHits(AccessibilityNodeInfo node, String exact, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && exact.equals(t.toString().trim())) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectTextHits(node.getChild(i), exact, out);
    }

    private void tapNodeCenter(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() == 0 && r.height() == 0) return;
        tapAt(r.centerX(), r.centerY());
    }


/** The form header date, normalized like "11\u670810\u65e5"; null when not found. */
private String elapsedSuffix() {
return "\u30fb\u6240\u8981" + Store.fmtDuration(System.currentTimeMillis() - fillStartMs);
}

private void abortSwitch(String why) {
log("\u65e5\u4ed8\u306e\u5207\u308a\u66ff\u3048\u306b\u5931\u6557: " + why);
abortedFlag = true;
Store.saveFillResult(svc, false, "\u65e5\u4ed8\u306e\u5207\u308a\u66ff\u3048\u306b\u5931\u6557\uff08" + why + "\uff09" + elapsedSuffix());
finish();
}

private int[] targetYearMonthDay() {
try {
String[] p = d.targetDate.split("-");
return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
} catch (Exception e) {
return null;
}
}

/** The node showing the form's date text (tappable to open the date picker). */
static AccessibilityNodeInfo findDateTextNode(AccessibilityNodeInfo root) {
List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
collectNodesStatic(root, nodes, 400);
java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\d{1,2}\u6708\\d{1,2}\u65e5");
for (AccessibilityNodeInfo n : nodes) {
CharSequence t = n.getText();
if (t != null && p.matcher(t.toString()).find()) return n;
}
return null;
}

/** The calendar's displayed month from its exact "yyyy\u5e74M\u6708" label; null when absent. */
static int[] findPickerYearMonth(AccessibilityNodeInfo root) {
List<String> texts = new ArrayList<String>();
collectTextsStatic(root, texts, 80);
java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(\\d{4})\u5e74(\\d{1,2})\u6708$");
for (String t : texts) {
java.util.regex.Matcher m = p.matcher(t.trim());
if (m.matches()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
}
return null;
}

static boolean headerShowsDate(AccessibilityNodeInfo root, int[] ymd) {
List<String> texts = new ArrayList<String>();
collectTextsStatic(root, texts, 80);
String needle = ymd[0] + "\u5e74" + ymd[1] + "\u6708" + ymd[2] + "\u65e5";
for (String t : texts) if (t.contains(needle)) return true;
return false;
}

/** Month navigation button in the date picker: by content description, else by position next to the month label. */
static AccessibilityNodeInfo findMonthNav(AccessibilityNodeInfo root, boolean forward, int[] ymUnused) {
AccessibilityNodeInfo byDesc = findByDesc(root, forward ? "Next" : "Previous");
if (byDesc == null) byDesc = findByDesc(root, forward ? "\u7fcc\u6708" : "\u524d\u6708");
if (byDesc != null) return byDesc;
List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
collectNodesStatic(root, nodes, 400);
AccessibilityNodeInfo label = null;
java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\d{4}\u5e74\\d{1,2}\u6708$");
for (AccessibilityNodeInfo n : nodes) {
CharSequence t = n.getText();
if (t != null && p.matcher(t.toString().trim()).matches()) { label = n; break; }
}
if (label == null) return null;
android.graphics.Rect lb = new android.graphics.Rect();
label.getBoundsInScreen(lb);
List<AccessibilityNodeInfo> cands = new ArrayList<AccessibilityNodeInfo>();
for (AccessibilityNodeInfo n : nodes) {
if (!n.isClickable()) continue;
android.graphics.Rect b = new android.graphics.Rect();
n.getBoundsInScreen(b);
if (Math.abs(b.centerY() - lb.centerY()) < 60 && b.centerX() > lb.centerX() && b.width() < lb.width()) cands.add(n);
}
if (cands.isEmpty()) return null;
cands.sort((a, b) -> {
android.graphics.Rect ra = new android.graphics.Rect(); a.getBoundsInScreen(ra);
android.graphics.Rect rb = new android.graphics.Rect(); b.getBoundsInScreen(rb);
return Integer.compare(ra.centerX(), rb.centerX());
});
return forward ? cands.get(cands.size() - 1) : cands.get(0);
}


/** Day cell for the target date: prefer a content-description match (full date), else the day number below the month label. */
static AccessibilityNodeInfo findDayCell(AccessibilityNodeInfo root, int[] ymd) {
String needle = ymd[0] + "\u5e74" + ymd[1] + "\u6708" + ymd[2] + "\u65e5";
List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
collectNodesStatic(root, nodes, 500);
AccessibilityNodeInfo best = null;
long bestArea = Long.MAX_VALUE;
for (AccessibilityNodeInfo n : nodes) {
CharSequence cd = n.getContentDescription();
if (cd != null && cd.toString().contains(needle)) {
android.graphics.Rect b = new android.graphics.Rect();
n.getBoundsInScreen(b);
long area = (long) b.width() * b.height();
if (area > 0 && area < bestArea) { best = n; bestArea = area; }
}
}
if (best != null) return best;
AccessibilityNodeInfo label = null;
java.util.regex.Pattern lp = java.util.regex.Pattern.compile("^\\d{4}\u5e74\\d{1,2}\u6708$");
for (AccessibilityNodeInfo n : nodes) {
CharSequence t = n.getText();
if (t != null && lp.matcher(t.toString().trim()).matches()) { label = n; break; }
}
int labelBottom = label == null ? 0 : labelBoundsBottom(label);
bestArea = Long.MAX_VALUE;
for (AccessibilityNodeInfo n : nodes) {
CharSequence t = n.getText();
if (t == null || !t.toString().trim().equals(String.valueOf(ymd[2]))) continue;
android.graphics.Rect b = new android.graphics.Rect();
n.getBoundsInScreen(b);
if (b.top < labelBottom - 4) continue;
long area = (long) b.width() * b.height();
if (area > 0 && area < bestArea) { best = n; bestArea = area; }
}
return best;
}

static int labelBoundsBottom(AccessibilityNodeInfo n) {
android.graphics.Rect b = new android.graphics.Rect();
n.getBoundsInScreen(b);
return b.bottom;
}

static boolean descShowsDate(AccessibilityNodeInfo root, int[] ymd) {
String needle = ymd[0] + "\u5e74" + ymd[1] + "\u6708" + ymd[2] + "\u65e5";
List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
collectNodesStatic(root, nodes, 300);
for (AccessibilityNodeInfo n : nodes) {
CharSequence cd = n.getContentDescription();
if (cd != null && cd.toString().contains(needle)) return true;
}
return false;
}

private void dumpDateDialog(AccessibilityNodeInfo root) {
try {
List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
collectNodesStatic(root, nodes, 120);
StringBuilder sb = new StringBuilder("\u30ab\u30ec\u30f3\u30c0\u30fc\u69cb\u9020:");
int shown = 0;
for (AccessibilityNodeInfo n : nodes) {
CharSequence t = n.getText();
CharSequence cd = n.getContentDescription();
if ((t == null || t.toString().trim().isEmpty()) && (cd == null || cd.toString().trim().isEmpty())) continue;
android.graphics.Rect b = new android.graphics.Rect();
n.getBoundsInScreen(b);
sb.append("\n[").append(shown++).append("] t='").append(t).append("' d='").append(cd)
.append("' clk=").append(n.isClickable()).append(" sel=").append(n.isSelected())
.append(" ").append(b.left).append(",").append(b.top).append(",").append(b.width()).append("x").append(b.height());
if (shown >= 45) break;
}
log(sb.toString());
} catch (Exception ignored) { }
}


static String findFormDate(AccessibilityNodeInfo root) {
List<String> texts = new ArrayList<String>();
collectTextsStatic(root, texts, 60);
java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d{1,2})\u6708(\\d{1,2})\u65e5");
for (String t : texts) {
java.util.regex.Matcher m = p.matcher(t);
if (m.find()) {
return Integer.parseInt(m.group(1)) + "\u6708" + Integer.parseInt(m.group(2)) + "\u65e5";
}
}
return null;
}

private String draftMonthDay() {
try {
String[] parts = d.targetDate.split("-");
return Integer.parseInt(parts[1]) + "\u6708" + Integer.parseInt(parts[2]) + "\u65e5";
} catch (Exception e) {
return null;
}
}


    static AccessibilityNodeInfo findClockContainer(AccessibilityNodeInfo node) {
        if (node == null) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findClockContainer(node.getChild(i));
            if (r != null) return r;
        }
        List<String> texts = new ArrayList<String>();
        collectTextsStatic(node, texts, 40);
        if (texts.contains("12") && texts.contains("3") && texts.contains("6") && texts.contains("9")) return node;
        return null;
    }

static void collectNodesStatic(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int max) {
if (node == null || out.size() >= max) return;
out.add(node);
for (int k = 0; k < node.getChildCount() && out.size() < max; k++) collectNodesStatic(node.getChild(k), out, max);
}


    static void collectTextsStatic(AccessibilityNodeInfo node, List<String> out, int max) {
        if (node == null || out.size() >= max) return;
        CharSequence t = node.getText();
        if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        for (int i = 0; i < node.getChildCount() && out.size() < max; i++) collectTextsStatic(node.getChild(i), out, max);
    }

    private void tapDial(AccessibilityNodeInfo clock, int posIndex) {
        Rect r = new Rect();
        clock.getBoundsInScreen(r);
        if (r.width() == 0 || r.height() == 0) return;
        double radius = Math.min(r.width(), r.height()) / 2.0 * 0.78;
        double theta = Math.toRadians(posIndex * 30 - 90);
        float x = (float) (r.centerX() + radius * Math.cos(theta));
        float y = (float) (r.centerY() + radius * Math.sin(theta));
        tapAt(x, y);
    }

    private void tapAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 70))
                .build();
        svc.dispatchGesture(g, null, null);
    }

    static boolean containsClass(AccessibilityNodeInfo node, String needle) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains(needle)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (containsClass(node.getChild(i), needle)) return true;
        return false;
    }

    static AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().contains(needle)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByDesc(node.getChild(i), needle);
            if (r != null) return r;
        }
        return null;
    }

    static void collectEditTexts(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText")) { out.add(node); return; }
        for (int i = 0; i < node.getChildCount(); i++) collectEditTexts(node.getChild(i), out);
    }

    private void stepMemo(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg, String title, String memo) {
        switch (stage) {
            case 0: toStage(1); break;
            case 1: {
                if (form == null) break;
                AccessibilityNodeInfo row = findRowByTitle(form, title);
                if (row == null) { if (searchDone(form)) doneOp(false, "項目が見つかりません"); break; }
                AccessibilityNodeInfo v = findByIdSuffix(row, "tvValue");
                String curMemo = (v != null && v.getText() != null) ? v.getText().toString().trim() : "";
                if (!curMemo.isEmpty() && !curMemo.contains("未入力") && !curMemo.contains("タップ")) {
                    String shortMemo = curMemo.length() > 24 ? curMemo.substring(0, 24) + "…" : curMemo;
                    skipOp("入力済み「" + shortMemo + "」" + (memo.startsWith(curMemo) ? "" : "（下書きと異なります）"));
                    break;
                }
                click(v != null ? v : row);
                toStage(2);
                break;
            }
            case 2: { // editor window/screen with an EditText
                AccessibilityNodeInfo et = dlg != null ? findEditText(dlg) : null;
                if (et == null && form != null && dlg == null) et = null; // editor replaces form content: handled below
                if (et == null) {
                    // the editor may be in the "form" window slot (fullscreen fragment)
                    List<AccessibilityNodeInfo> roots = allRoots();
                    for (AccessibilityNodeInfo r : roots) {
                        AccessibilityNodeInfo cand = findEditText(r);
                        if (cand != null) { et = cand; break; }
                    }
                }
                if (et == null) { if (ticks > 30) doneOp(false, "編集画面が出ません"); break; }
                setText(et, memo);
                toStage(3);
                break;
            }
            case 3: {
                List<AccessibilityNodeInfo> roots = allRoots();
                boolean saved = false;
                for (AccessibilityNodeInfo r : roots) {
                    AccessibilityNodeInfo save = findText(r, "保存", true);
                    if (save == null) save = findText(r, "完了", true);
                    if (save != null) { click(save); saved = true; break; }
                }
                if (!saved) svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                toStage(4);
                break;
            }
            case 4: {
                List<AccessibilityNodeInfo> roots = allRoots();
                boolean editorLeft = false;
                for (AccessibilityNodeInfo r : roots) if (findEditText(r) != null) { editorLeft = true; break; }
                if (!editorLeft) doneOp(true, "");
                else if (ticks > 14) doneOp(false, "編集画面が閉じません（内容は入力済みの可能性あり）");
                break;
            }
        }
    }

    private void stepMedicine(AccessibilityNodeInfo form, AccessibilityNodeInfo dlg) {
        boolean has = !d.medicine.isEmpty();
        switch (stage) {
            case 0: toStage(1); break;
            case 1: {
                if (form == null) break;
                Section s = findSection(form, "投薬");
                if (s == null) { if (searchDone(form)) doneOp(false, "項目が見つかりません"); break; }
                if (s.headerRow != null) {
                    AccessibilityNodeInfo haveM = findByIdSuffix(s.headerRow, "cpHave");
                    AccessibilityNodeInfo noneM = findByIdSuffix(s.headerRow, "cpNone");
                    if (!has && haveM != null && haveM.isChecked()) {
                        skipOp("入力済み（あり）。下書きは「なし」");
                        break;
                    }
                    if (!has && noneM != null && noneM.isChecked()) {
                        skipOp("なし（選択済み）");
                        break;
                    }
                    if (has && haveM != null && haveM.isChecked()) medWasOn = true;
                }
                AccessibilityNodeInfo chip = findByIdSuffix(s.headerRow, has ? "cpHave" : "cpNone");
                if (chip == null) { doneOp(false, "チップが見つかりません"); break; }
                if (!chip.isChecked()) click(chip);
                if (!has) doneOp(true, "なし");
                else toStage(2);
                break;
            }
            case 2: {
                if (form == null) break;
                AccessibilityNodeInfo row = findRowByTitle(form, "薬の内容");
                if (row == null) { if (ticks > 20) doneOp(true, "あり（内容欄が見つからず未入力）"); break; }
                AccessibilityNodeInfo v = findByIdSuffix(row, "tvValue");
                String curMed = (v != null && v.getText() != null) ? v.getText().toString().trim() : "";
                if (medWasOn && !curMed.isEmpty() && !curMed.contains("未入力")) {
                    skipOp("入力済み「" + curMed + "」" + (curMed.equals(medicineContent()) ? "" : "（下書きと異なります）"));
                    break;
                }
                click(v != null ? v : row);
                toStage(3);
                break;
            }
            case 3: {
                AccessibilityNodeInfo et = null;
                List<AccessibilityNodeInfo> roots = allRoots();
                for (AccessibilityNodeInfo r : roots) {
                    AccessibilityNodeInfo cand = findEditText(r);
                    if (cand != null) { et = cand; break; }
                }
                if (et == null) { if (ticks > 30) doneOp(false, "内容の編集画面が出ません"); break; }
                setText(et, medicineContent());
                toStage(4);
                break;
            }
            case 4: {
                List<AccessibilityNodeInfo> roots = allRoots();
                boolean saved = false;
                for (AccessibilityNodeInfo r : roots) {
                    AccessibilityNodeInfo save = findText(r, "保存", true);
                    if (save == null) save = findText(r, "完了", true);
                    if (save != null) { click(save); saved = true; break; }
                }
                if (!saved) svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                toStage(5);
                break;
            }
            case 5: {
                List<AccessibilityNodeInfo> roots = allRoots();
                boolean editorLeft = false;
                for (AccessibilityNodeInfo r : roots) if (findEditText(r) != null) { editorLeft = true; break; }
                if (!editorLeft) doneOp(true, "あり：" + medicineContent());
                else if (ticks > 14) doneOp(false, "編集画面が閉じません");
                break;
            }
        }
    }

    private String medicineContent() {
        StringBuilder sb = new StringBuilder();
        for (String m : d.medicine) {
            String content = m.replaceFirst("^\\d{1,2}:\\d{2}\\s*", "");
            if (sb.length() > 0) sb.append("、");
            sb.append(content.isEmpty() ? m : content);
        }
        return sb.toString();
    }

    // ---------------- picker engine ----------------

    private PickerTask[] timeTasks(String hhmm) {
        String f = Aggregator.floor5(hhmm);
        return new PickerTask[]{
                new PickerTask(0, "時", Aggregator.hourOf(f), 1, 24),
                new PickerTask(1, "分", Aggregator.minuteOf(f), 5, 12)};
    }

    private void startPickers(PickerTask... tasks) {
        pickerTasks.clear();
        for (PickerTask t : tasks) pickerTasks.add(t);
        pickerFailed = false;
    }

    private boolean engineOkOrCancel(AccessibilityNodeInfo dlg) {
        if (!pickerFailed) return true;
        cancelDialog(dlg);
        return false;
    }

    // All wheels of a dialog are driven in parallel: every tick, each unfinished
    // wheel is read once and stepped once. A dialog therefore takes about as long
    // as its slowest wheel instead of the sum of all wheels.
    private boolean runPickerEngine(AccessibilityNodeInfo dlg) {
        if (pickerFailed) return false;
        if (pickerTasks.isEmpty()) return true;
        List<AccessibilityNodeInfo> pickers = findPickers(dlg);
        gestureUsedThisTick = false;
        boolean allDone = true;
        for (PickerTask t : pickerTasks) {
            if (t.done) continue;
            if (!stepOneWheel(t, pickers)) allDone = false;
            if (pickerFailed) return false;
        }
        return allDone;
    }

    /** One tick of work for one wheel. Returns true when the wheel is at its target. */
    private boolean stepOneWheel(PickerTask t, List<AccessibilityNodeInfo> pickers) {
        if (t.cooldown > 0) { t.cooldown--; return false; }
        AccessibilityNodeInfo node = t.index < pickers.size() ? pickers.get(t.index) : null;
        if (node == null) {
            if (++t.noChange > 24) {
                log(dumpWindows(allRoots()));
                failPicker("ピッカー[" + t.label + "] が見つかりません");
            }
            return false;
        }
        int cur = readPickerDisplay(node);
        if (cur == Integer.MIN_VALUE) {
            if (++t.noChange > 24) failPicker("ピッカー[" + t.label + "] の現在値を読めません");
            return false;
        }
        if (!t.announced) {
            t.announced = true;
            log("  " + t.label + ": " + cur + " → " + t.targetDisplay);
        }
        if (cur == t.targetDisplay) {
            log("  " + t.label + " = " + cur + " ✓");
            t.done = true;
            return true;
        }
        if (t.beforeAction != Integer.MIN_VALUE && cur != t.beforeAction) {
            int sd = stepDelta(cur, t.beforeAction, t);
            boolean movedForward = t.expectForward ? sd > 0 : sd < 0;
            if (!movedForward && Math.abs(sd) <= 2 && !t.invert) {
                t.invert = true;
                log("  方向が逆と判明、反転します");
            }
            if (movedForward && Math.abs(sd) == 1) t.dirVerified = true;
            t.beforeAction = Integer.MIN_VALUE;
            t.noChange = 0;
        } else if (t.beforeAction != Integer.MIN_VALUE) {
            t.noChange++;
            if (t.noChange > 2) {
                // cap at method 1: method 2 (direct text set) is banned — on the
                // ml wheel it only rewrites the centre label and never commits.
                t.method = Math.min(1, t.method + 1);
                t.noChange = 0;
                log("  " + t.label + " 手法を " + t.method + " に切替（現在 " + cur + "）");
            }
            t.beforeAction = Integer.MIN_VALUE;
        }
        if (t.actions > 44) {
            failPicker("ピッカー[" + t.label + "] を " + t.targetDisplay + " にできません（現在 " + cur + "）");
            return false;
        }
        if (t.batchLeft > 0) {
            t.batchLeft--;
            t.actions++;
            t.cooldown = 0;
            node.performAction(t.batchForward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            return false;
        }
        int diffSteps = (t.targetDisplay - cur) / t.step;
        boolean forward;
        if (t.wrapSteps > 0) {
            int mod = ((diffSteps % t.wrapSteps) + t.wrapSteps) % t.wrapSteps;
            forward = mod <= t.wrapSteps / 2;
        } else {
            forward = diffSteps > 0;
        }
        boolean effectiveForward = t.invert ? !forward : forward;
        if (t.method == 0 && t.dirVerified) {
            int remaining = Math.abs(stepDelta(t.targetDisplay, cur, t));
            if (remaining >= 3) {
                t.batchLeft = remaining - 2;
                t.batchForward = effectiveForward;
            }
        }
        if (t.method == 1 && gestureUsedThisTick) {
            // gesture taps share one channel: only one wheel may tap per tick
            t.cooldown = 1;
            return false;
        }
        t.beforeAction = cur;
        t.expectForward = forward;
        t.actions++;
        t.cooldown = 1;
        if (t.method == 0) {
            node.performAction(effectiveForward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } else {
            tapPicker(node, effectiveForward);
            gestureUsedThisTick = true;
        }
        return false;
    }

    private void failPicker(String why) {
        log("  " + why);
        pickerFailed = true;
        pickerTasks.clear();
    }


    private int stepDelta(int to, int from, PickerTask t) {
        int dd = (to - from) / t.step;
        if (t.wrapSteps > 0) {
            int mod = ((dd % t.wrapSteps) + t.wrapSteps) % t.wrapSteps;
            if (mod > t.wrapSteps / 2) mod -= t.wrapSteps;
            return mod;
        }
        return dd;
    }

    private int readPickerDisplay(AccessibilityNodeInfo pickerNode) {
        AccessibilityNodeInfo et = findEditText(pickerNode);
        CharSequence t = et != null ? et.getText() : pickerNode.getText();
        if (t == null) return Integer.MIN_VALUE;
        String s = t.toString().replaceAll("[^0-9]", "");
        if (s.isEmpty()) return Integer.MIN_VALUE;
        try { return Integer.parseInt(s); } catch (Exception e) { return Integer.MIN_VALUE; }
    }

    private void tapPicker(AccessibilityNodeInfo node, boolean increase) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() == 0 || r.height() == 0) return;
        float x = r.centerX();
        float y = increase ? r.top + r.height() * 0.85f : r.top + r.height() * 0.15f;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        svc.dispatchGesture(g, null, null);
    }

    // ---------------- dialogs ----------------

    private boolean confirmDialog(AccessibilityNodeInfo dlg) {
        List<AccessibilityNodeInfo> buttons = new ArrayList<AccessibilityNodeInfo>();
        collectButtons(dlg, buttons);
        for (String want : CONFIRM_TEXTS) {
            for (AccessibilityNodeInfo b : buttons) {
                CharSequence t = b.getText();
                if (t != null && t.toString().trim().equals(want)) return click(b);
            }
        }
        return false;
    }

    private void cancelDialog(AccessibilityNodeInfo dlg) {
        if (dlg == null) return;
        AccessibilityNodeInfo c = findText(dlg, "キャンセル", true);
        if (c != null) { click(c); return; }
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    }

    private void collectButtons(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("Button") && node.getText() != null
                && !"キャンセル".equals(node.getText().toString().trim())) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectButtons(node.getChild(i), out);
    }

    // ---------------- form structure ----------------

    private static class Section {
        AccessibilityNodeInfo headerRow;
        final List<AccessibilityNodeInfo> entries = new ArrayList<AccessibilityNodeInfo>();
        AccessibilityNodeInfo addRow = null;
    }

    private Section findSection(AccessibilityNodeInfo form, String title) {
        AccessibilityNodeInfo list = findByIdSuffix(form, "rvPresenceCombook");
        if (list == null) list = findScrollable(form);
        if (list == null) return null;
        Section s = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null) continue;
            String tv = titleOf(row);
            if (s == null) {
                if (title.equals(tv)) { s = new Section(); s.headerRow = row; }
            } else {
                if (tv != null && TITLES.contains(tv) && !tv.equals(title)) break;
                if (containsText(row, "を追加")) { s.addRow = row; continue; }
                AccessibilityNodeInfo vv = findByIdSuffix(row, "tvValue");
                String vt = (vv != null && vv.getText() != null) ? vv.getText().toString() : "";
                if (findByIdSuffix(row, "btnRemove") != null || vt.contains(":")) s.entries.add(row);
            }
        }
        if (s == null) {
            // no title row (e.g. sleep after all entries were deleted): the add row alone is the section
            for (int i2 = 0; i2 < list.getChildCount(); i2++) {
                AccessibilityNodeInfo row = list.getChild(i2);
                if (row != null && containsText(row, title + "\u3092\u8ffd\u52a0")) {
                    Section s2 = new Section();
                    s2.addRow = row;
                    return s2;
                }
            }
        }
        return s;
    }

    private AccessibilityNodeInfo findRowByTitle(AccessibilityNodeInfo form, String title) {
        AccessibilityNodeInfo list = findByIdSuffix(form, "rvPresenceCombook");
        if (list == null) list = findScrollable(form);
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row != null && title.equals(titleOf(row))) return row;
        }
        return null;
    }

    private String titleOf(AccessibilityNodeInfo row) {
        AccessibilityNodeInfo tv = findByIdSuffix(row, "tvTitle");
        if (tv == null || tv.getText() == null) return null;
        return tv.getText().toString().trim();
    }

private boolean searchWrapped = false;
private int bottomStreak = 0;

// Section search for an op's first stage: look at the current viewport first, then
// scroll down; only when the bottom is reached without finding it, wrap once to
// the top (stage -3 restarts this op at stage 0) and search down again.
private boolean searchDone(AccessibilityNodeInfo form) {
if (scrollForm(form)) { bottomStreak = 0; return false; }
if (++bottomStreak < 3) return false;
if (!searchWrapped) {
searchWrapped = true;
bottomStreak = 0;
log("  \u4e0b\u307e\u3067\u63a2\u3057\u3066\u898b\u3064\u304b\u3089\u306a\u3044\u305f\u3081\u3001\u5148\u982d\u306b\u623b\u3063\u3066\u63a2\u3057\u76f4\u3057\u307e\u3059");
toStage(-3);
return false;
}
return ticks > 24;
}

private long lastSwipeMs = 0;
private String lastScrollSig = "";
private int scrollStuck = 0;

private boolean scrollForm(AccessibilityNodeInfo form) {
AccessibilityNodeInfo list = findByIdSuffix(form, "rvPresenceCombook");
if (list == null) list = findScrollable(form);
if (list == null) return false;
if (tickMs > 110) {
// one page per call: batching two scrolls jumped over small sections (sleep / breast)
return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
}
// Fastest modes: the accessibility scroll action compounds while the list is still
// animating and leaps over small sections. Use small paced gesture swipes instead
// (~35% of the viewport every ~220ms) and watch the visible rows pass by.
long now = System.currentTimeMillis();
if (now - lastSwipeMs < 220) return true; // previous swipe still settling
String sig = scrollSignature(list);
if (sig.equals(lastScrollSig)) {
if (++scrollStuck >= 3) return false; // bottom reached
} else {
scrollStuck = 0;
lastScrollSig = sig;
}
lastSwipeMs = now;
return swipeListUp(list);
}

private String scrollSignature(AccessibilityNodeInfo list) {
StringBuilder sb = new StringBuilder();
for (int i = 0; i < list.getChildCount(); i++) {
AccessibilityNodeInfo row = list.getChild(i);
if (row == null) continue;
String t = titleOf(row);
sb.append(t == null ? "?" : t).append('|');
}
return sb.toString();
}

private boolean swipeListUp(AccessibilityNodeInfo list) {
Rect r = new Rect();
list.getBoundsInScreen(r);
if (r.width() == 0 || r.height() == 0) return false;
float x = r.centerX();
float y1 = r.bottom - r.height() * 0.12f;
float y2 = y1 - r.height() * 0.35f;
Path p = new Path();
p.moveTo(x, y1);
p.lineTo(x, y2);
GestureDescription g = new GestureDescription.Builder()
.addStroke(new GestureDescription.StrokeDescription(p, 0, 180))
.build();
return svc.dispatchGesture(g, null, null);
}

    // ---------------- generic helpers ----------------

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

    static AccessibilityNodeInfo findText(AccessibilityNodeInfo node, String text, boolean exact) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString();
            if (exact ? s.trim().equals(text) : s.contains(text)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findText(node.getChild(i), text, exact);
            if (r != null) return r;
        }
        return null;
    }

    static boolean containsText(AccessibilityNodeInfo node, String text) {
        return findText(node, text, false) != null;
    }

    static AccessibilityNodeInfo findEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditText(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable() && node.getChildCount() > 2) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 6 && n != null; i++) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    static void setText(AccessibilityNodeInfo et, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        et.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private int entryMl(String entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*ml").matcher(entry);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 100;
    }

    private void log(String s) { log.append(s).append("\n"); }


// ---------------- date-confirmation overlay (touchable; replaces the old Activity dialog) ----------------

private void showConfirmOverlay(final String formDate, final String want) {
new Handler(Looper.getMainLooper()).post(() -> {
try {
if (wm == null) wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
hideConfirmOverlayNow();
float den = svc.getResources().getDisplayMetrics().density;
android.widget.LinearLayout panel = new android.widget.LinearLayout(svc);
panel.setOrientation(android.widget.LinearLayout.VERTICAL);
int pad = (int) (20 * den);
panel.setPadding(pad, pad, pad, pad);
GradientDrawable bg = new GradientDrawable();
bg.setColor(0xFFFFFFFF);
bg.setCornerRadius(16 * den);
panel.setBackground(bg);
android.widget.TextView title = new android.widget.TextView(svc);
title.setText("\u26a0 \u5165\u529b\u5148\u304c\u5f53\u65e5\u3067\u306f\u3042\u308a\u307e\u305b\u3093");
title.setTextSize(18);
title.setTextColor(0xFFC62828);
panel.addView(title);
android.widget.TextView body = new android.widget.TextView(svc);
body.setTextSize(16);
body.setPadding(0, (int) (10 * den), 0, (int) (16 * den));
body.setText("\u958b\u3044\u3066\u3044\u308b\u30d5\u30a9\u30fc\u30e0: " + formDate + "\n\u8ee2\u8a18\u3059\u308b\u30c7\u30fc\u30bf: " + want + " \u306e\u5206\n\n\u3053\u306e\u307e\u307e " + formDate + " \u306e\u30d5\u30a9\u30fc\u30e0\u3078\u5165\u529b\u3057\u307e\u3059\u304b\uff1f");
panel.addView(body);
android.widget.Button btnSwitch = new android.widget.Button(svc);
btnSwitch.setText("\u4eca\u65e5\u306e\u65e5\u4ed8\u306b\u5165\u529b\u3059\u308b");
btnSwitch.setTextSize(17);
btnSwitch.setAllCaps(false);
btnSwitch.setTextColor(0xFFFFFFFF);
GradientDrawable swBg = new GradientDrawable();
swBg.setColor(0xFF2E7D32);
swBg.setCornerRadius(10 * den);
btnSwitch.setBackground(swBg);
btnSwitch.setOnClickListener(v -> { dateDecision = "switch"; hideConfirmOverlay(); });
android.widget.LinearLayout.LayoutParams swLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (58 * den));
swLp.setMargins(0, 0, 0, (int) (10 * den));
panel.addView(btnSwitch, swLp);

android.widget.Button btnGo = new android.widget.Button(svc);
btnGo.setText("\u3053\u306e\u307e\u307e\u5165\u529b\u3059\u308b");
btnGo.setTextSize(17);
btnGo.setAllCaps(false);
btnGo.setTextColor(0xFFFFFFFF);
GradientDrawable goBg = new GradientDrawable();
goBg.setColor(0xFF1565C0);
goBg.setCornerRadius(10 * den);
btnGo.setBackground(goBg);
btnGo.setOnClickListener(v -> { dateDecision = "go"; hideConfirmOverlay(); });
panel.addView(btnGo, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (58 * den)));
android.widget.Button btnStop = new android.widget.Button(svc);
btnStop.setText("\u3084\u3081\u308b");
btnStop.setTextSize(16);
btnStop.setAllCaps(false);
btnStop.setTextColor(0xFFC62828);
btnStop.setOnClickListener(v -> { dateDecision = "stop"; hideConfirmOverlay(); });
android.widget.LinearLayout.LayoutParams sLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * den));
sLp.setMargins(0, (int) (8 * den), 0, 0);
panel.addView(btnStop, sLp);
WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
WindowManager.LayoutParams.WRAP_CONTENT,
WindowManager.LayoutParams.WRAP_CONTENT,
WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
PixelFormat.TRANSLUCENT);
lp.gravity = Gravity.CENTER;
lp.width = (int) (320 * den);
confirmView = panel;
wm.addView(confirmView, lp);
} catch (Exception ignored) { }
});
}

private void hideConfirmOverlayNow() {
try {
if (wm != null && confirmView != null) { wm.removeView(confirmView); confirmView = null; }
} catch (Exception ignored) { }
}

private void hideConfirmOverlay() {
new Handler(Looper.getMainLooper()).post(this::hideConfirmOverlayNow);
}


    // ---------------- progress overlay (toasts were being dropped) ----------------

    private WindowManager wm;
    private TextView overlayView;
    private String lastResult = "";

    private void showOverlay(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (wm == null) wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
                if (overlayView == null) {
                    float den = svc.getResources().getDisplayMetrics().density;
                    overlayView = new TextView(svc);
                    overlayView.setTextColor(0xFFFFFFFF);
                    overlayView.setTextSize(14);
                    overlayView.setPadding((int) (14 * den), (int) (8 * den), (int) (14 * den), (int) (8 * den));
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xD91F1F1F);
                    bg.setCornerRadius(18 * den);
                    overlayView.setBackground(bg);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = (int) (96 * den);
                    wm.addView(overlayView, lp);
                }
                overlayView.setText(text);
            } catch (Exception ignored) { }
        });
    }

    private void hideOverlay(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (wm != null && overlayView != null) { wm.removeView(overlayView); overlayView = null; }
            } catch (Exception ignored) { }
        }, delayMs);
    }

    private void progressOverlay() {
        String head = "\u81ea\u52d5\u5165\u529b " + opNumber + "/" + totalOps + "\uff1a" + (op == null ? "" : op.name);
        showOverlay(lastResult.isEmpty() ? head : head + "\n" + lastResult);
    }

    private void toast(String s) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { Toast.makeText(svc, s, Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
        });
    }

    private void toastShort(String s) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { Toast.makeText(svc, s, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { }
        });
    }
}
