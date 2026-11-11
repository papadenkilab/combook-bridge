package jp.papadenkilab.combookbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a scan is pending and Papotto comes to the foreground:
 *   phase TOP  - scroll back to the very top first (Papotto may resume mid-list,
 *                and rows above the first visible date header would be dateless)
 *   phase SCAN - collect rows while scrolling down until we pass the previous day.
 */
public class BridgeService extends AccessibilityService {

    private static final String PAPATTO = "jp.firstascent.papaikuji";
    private static final String LUKUMI = "com.unifa_e.hiyoko.family";
    private LukumiFiller filler = null;
    private SchoolScanner schoolScanner = null;
    private PapottoWriter writer = null;
    private static final Pattern DATE_HEADER = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int phase = 0; // 0 idle, 1 to-top, 2 scan
    private int topSteps = 0;
    private int topIdle = 0;
    private int scanSteps = 0;
    private int scanIdle = 0;
    private String lastSignature = "";
    private String currentDate = null;
    private String targetIso = null;
    private String stopIso = null;
    private final Map<String, RecordItem> collected = new LinkedHashMap<String, RecordItem>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (LUKUMI.contentEquals(event.getPackageName())) {
            if (Store.isSchoolPending(this) && (schoolScanner == null || !schoolScanner.isRunning())) {
                schoolScanner = new SchoolScanner(this);
                schoolScanner.start();
                return;
            }
            if (Store.isFillPending(this) && (filler == null || !filler.isRunning())) {
                Aggregator.Draft d = Aggregator.fromData(Store.getData(this));
                filler = new LukumiFiller(this, d);
                filler.start();
            }
            return;
        }
        if (!PAPATTO.contentEquals(event.getPackageName())) return;
        if (Store.isWritePending(this) && (writer == null || !writer.isRunning())) {
            writer = new PapottoWriter(this);
            writer.start();
            return;
        }
        if (running) return;
        if (!Store.isPending(this)) return;
        begin();
    }

    private int topDelay = 250;
private long scanStartMs = 0;
    private int scanDelay = 400;

    private void begin() {
scanStartMs = System.currentTimeMillis();
        switch (Store.getSpeedLevel(this)) {
case 5: topDelay = 110; scanDelay = 200; break;
case 4: topDelay = 140; scanDelay = 240; break;
case 3: topDelay = 180; scanDelay = 300; break;
            case 1: topDelay = 350; scanDelay = 550; break;
            case 2: topDelay = 500; scanDelay = 800; break;
            default: topDelay = 250; scanDelay = 400; break;
        }
        running = true;
        phase = 1;
        topSteps = 0;
        topIdle = 0;
        lastSignature = "";
        collected.clear();
        currentDate = null;
        Toast.makeText(this, "読み取り開始：一覧の先頭へ移動します", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::stepTop, 600);
    }

    private AccessibilityNodeInfo findList(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(PAPATTO + ":id/actionsRecyclerView");
        if (found != null && !found.isEmpty()) return found.get(0);
        return findScrollable(root);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable() && node.getChildCount() > 2) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo r = findScrollable(c);
            if (r != null) return r;
        }
        return null;
    }

    /** phase TOP: scroll backward until the visible rows stop changing. */
    private void stepTop() {
        if (!running || phase != 1) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo list = findList(root);
        if (list == null) { handler.postDelayed(this::stepTop, 700); return; }
        String sig = signature(list);
        boolean moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        topSteps++;
        if (!moved || sig.equals(lastSignature)) topIdle++; else topIdle = 0;
        lastSignature = sig;
        if (topIdle >= 2 || topSteps > 40) {
            phase = 2;
            scanSteps = 0;
            scanIdle = 0;
            lastSignature = "";
            handler.postDelayed(this::stepScan, 350);
            return;
        }
        handler.postDelayed(this::stepTop, topDelay);
    }

    /** phase SCAN: collect visible rows, then scroll forward. */
    private void stepScan() {
        if (!running || phase != 2) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo list = findList(root);
        if (list == null) { finish(false); return; }

        collectVisible(list);

        if (targetIso == null && currentDate != null) {
            targetIso = currentDate;
            stopIso = shiftDate(targetIso, -1);
        }
        if (currentDate != null && stopIso != null && currentDate.compareTo(stopIso) < 0) {
            finish(true);
            return;
        }

        String sig = signature(list);
        if (sig.equals(lastSignature)) scanIdle++; else scanIdle = 0;
        lastSignature = sig;

        boolean moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        scanSteps++;
        if ((!moved && scanIdle >= 1) || scanIdle >= 3 || scanSteps > 30) {
            finish(true);
            return;
        }
        handler.postDelayed(this::stepScan, scanDelay);
    }

    private String signature(AccessibilityNodeInfo list) {
        StringBuilder sb = new StringBuilder();
        collectText(list, sb, 0);
        return Integer.toString(sb.toString().hashCode());
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 8) return;
        CharSequence t = node.getText();
        if (t != null) sb.append(t).append('|');
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), sb, depth + 1);
    }

    private void collectVisible(AccessibilityNodeInfo list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo row = list.getChild(i);
            if (row == null) continue;
            List<AccessibilityNodeInfo> texts = new ArrayList<AccessibilityNodeInfo>();
            gatherTextNodes(row, texts);
            for (AccessibilityNodeInfo tn : texts) {
                CharSequence tx = tn.getText();
                if (tx == null) continue;
                Matcher m = DATE_HEADER.matcher(tx.toString());
                if (m.find()) {
                    currentDate = String.format(Locale.JAPAN, "%04d-%02d-%02d",
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                }
            }
            String time = textByIdSuffix(row, "actionItemTimeTextView");
            String name = textByIdSuffix(row, "actionItemNameTextView");
            if (time == null || name == null || currentDate == null) continue;
            String memo = textByIdSuffix(row, "actionItemMemoTextView");
            String value = textByIdSuffix(row, "actionItemValueTextView");
            RecordItem r = new RecordItem(currentDate, time.trim(), name.trim(),
                    memo == null ? "" : memo, value == null ? "" : value);
            String key = r.dateIso + "|" + Aggregator.padTime(r.time) + "|" + r.name + "|" + r.memo + "|" + r.value;
            collected.put(key, r);
        }
    }

    private void gatherTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.getText() != null) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) gatherTextNodes(node.getChild(i), out);
    }

    private String textByIdSuffix(AccessibilityNodeInfo node, String suffix) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith(suffix)) {
            CharSequence t = node.getText();
            return t == null ? null : t.toString();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String r = textByIdSuffix(node.getChild(i), suffix);
            if (r != null) return r;
        }
        return null;
    }

    private void finish(boolean ok) {
        running = false;
        phase = 0;
        Store.setPending(this, false);
        List<RecordItem> records = new ArrayList<RecordItem>(collected.values());

        StringBuilder raw = new StringBuilder();
        String minD = null, maxD = null;
        for (RecordItem r : records) {
            raw.append(r.dateIso).append('|').append(r.time).append('|').append(r.name)
               .append('|').append(r.memo.replace('\n', ' ')).append('|').append(r.value).append('\n');
            if (minD == null || r.dateIso.compareTo(minD) < 0) minD = r.dateIso;
            if (maxD == null || r.dateIso.compareTo(maxD) > 0) maxD = r.dateIso;
        }
        String text;
        if (ok && !records.isEmpty()) {
            Aggregator.Draft d = Aggregator.aggregate(records);
            Store.saveData(this, Aggregator.toData(d));
            Store.saveLog(this, "");
            text = d.format()
                    + "\n―――\n読み取り診断: " + records.size() + "件（" + minD + " 〜 " + maxD + "）";
        } else {
            text = "読み取りに失敗しました（記録0件）。パパっと育児の一覧画面で再度お試しください。";
        }
        Store.saveDraft(this, text, raw.toString());
        if (ok && !records.isEmpty()) {
            Aggregator.Draft dd = Aggregator.aggregate(records);
            java.util.List<String> missing = new java.util.ArrayList<String>();
            if (Store.getRequired(this, 0) && dd.temperature.isEmpty()) missing.add("\u4f53\u6e29");
            if (Store.getRequired(this, 1) && dd.stoolTimes.isEmpty()) missing.add("\u4fbf");
            if (Store.getRequired(this, 2) && (dd.sleepStart.isEmpty() || dd.sleepEnd.isEmpty())) missing.add("\u7761\u7720");
            if (Store.getRequired(this, 3) && dd.milk.isEmpty()) missing.add("\u30df\u30eb\u30af");
            if (Store.getRequired(this, 4) && dd.breast.isEmpty()) missing.add("\u6388\u4e73");
            if (Store.getRequired(this, 5) && dd.dinnerTime.isEmpty()) missing.add("\u5915\u98df");
            if (Store.getRequired(this, 6) && dd.breakfastTime.isEmpty()) missing.add("\u671d\u98df");
            if (Store.getRequired(this, 7) && dd.medicine.isEmpty()) missing.add("\u85ac");
            String dur = Store.fmtDuration(System.currentTimeMillis() - scanStartMs);
            if (missing.isEmpty()) {
                Store.saveScanResult(this, true, "\u6240\u8981" + dur);
            } else {
                StringBuilder mb = new StringBuilder("\u4e0d\u8db3\uff1a");
                for (int m = 0; m < missing.size(); m++) { if (m > 0) mb.append("\u3001"); mb.append(missing.get(m)); }
                mb.append("\u30fb\u6240\u8981").append(dur);
                Store.saveScanResult(this, false, mb.toString());
            }
        } else {
Store.saveScanResult(this, false, "\u8a18\u93320\u4ef6\u30fb\u6240\u8981" + Store.fmtDuration(System.currentTimeMillis() - scanStartMs));
        }

        Toast.makeText(this, "読み取り完了: " + records.size() + "件", Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private static String shiftDate(String iso, int days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(iso));
            c.add(Calendar.DATE, days);
            return f.format(c.getTime());
        } catch (Exception e) {
            return iso;
        }
    }

    @Override
    public void onInterrupt() {
        running = false;
        phase = 0;
    }
}
