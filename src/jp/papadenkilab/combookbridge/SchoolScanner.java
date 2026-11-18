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
    // Timeline sightings: dateIso -> {toenTime, koenTime} (see TimelineParser).
    private final Map<String, String[]> timeline = new LinkedHashMap<String, String[]>();
    private boolean tlSeen = false;
    private int tlScrollSteps = 0;
    private int tlScrollIdle = 0;
    private String tlLastVisibleSig = "";
    private long tlLastScrollMs = 0;
    private boolean tlPhaseDone = false;
    private int tlPhaseTicks = 0;
    private int tlLastTapTick = -100;
    private int st0LastTapTick = -100;
    private int tapTries = 0;
    private int tapOks = 0;
    private String tapLast = "";
    private long subTapLastMs = 0;

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
        timeline.clear();
        tlSeen = false;
        tlScrollSteps = 0;
        tlScrollIdle = 0;
        tlLastVisibleSig = "";
        tlLastScrollMs = 0;
        tlPhaseDone = false;
        tlPhaseTicks = 0;
        tlLastTapTick = -100;
        st0LastTapTick = -100;
        tapTries = 0;
        tapOks = 0;
        tapLast = "";
        subTapLastMs = 0;
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
        if (stage == 2) {
            // Post-report timeline phase: Lukumi often resumes straight
            // into the report (no timeline pass), so after collecting
            // the report we switch to the timeline tab ourselves (user
            // request: the "open the timeline" toast was hidden
            // behind the "open the report" toast) and collect the day's
            // stamps. Falls back to waiting for a manual switch.
            AccessibilityNodeInfo lr = lukumiRoot();
            if (lr != null) {
                collectTimeline(lr);
                scrollTimeline(lr, dateIso);
                if (Store.isAutoTabSwitch(svc)
                        && timelineIncompleteFor(dateIso) && !containsTimelineTitle(lr)
                        && tlPhaseTicks - tlLastTapTick >= 8) {
                    if (tapTimelineTab(lr)) tlLastTapTick = tlPhaseTicks;
                }
            }
            String[] tt = timeline.get(dateIso);
            boolean complete = tt != null && !tt[0].isEmpty() && !tt[1].isEmpty();
            // One reminder toast halfway through the wait, in case the
            // first one was missed. (The floating chip also showed this
            // prompt until v0.15.3; the user asked for the bottom toast
            // only.)
            if (tlPhaseTicks == 48 && !complete) {
                Toast.makeText(svc, "タイムラインを開いてください ・ 登園 "
                        + (tt != null && !tt[0].isEmpty() ? "✓" : "―")
                        + " / 降園 " + (tt != null && !tt[1].isEmpty() ? "✓" : "―"),
                        Toast.LENGTH_LONG).show();
            }
            if (complete || ++tlPhaseTicks > 120) { finish(true, ""); }
            return;
        }
        AccessibilityNodeInfo root = rootWithDetails();
        if (stage == 0) {
            if (root == null) {
                // While waiting for the report screen, watch Lukumi's
                // timeline (its home tab) for the day's 登園/降園 times.
                AccessibilityNodeInfo lr = lukumiRoot();
                if (lr != null) {
                    tlSeen = true;
                    if (collectTimeline(lr) > 0) waitTicks = 0;
                    scrollTimeline(lr);
                    // Once the newest day's stamps are read from the
                    // timeline, switch to the 連絡帳 tab ourselves
                    // (user request) so the user does not
                    // have to navigate. An older target day's stamps,
                    // if still missing, are fetched in stage 2.
                    maybeTapSchoolTab(lr);
                    String newest = TimelineParser.newestDate(timeline);
                    boolean pairReady = newest != null && !timelineIncompleteFor(newest);
                    // Fallback trigger: ~10s on the timeline without a
                    // complete pair (e.g. the stamps' date-times did not
                    // pair up) — switch anyway; stage 2 retries the
                    // timeline after the report.
                    if ((pairReady || waitTicks >= 40)
                            && Store.isAutoTabSwitch(svc)
                            && containsTimelineTitle(lr)
                            && waitTicks - st0LastTapTick >= 8) {
                        if (tapTab(lr, "連絡帳")) st0LastTapTick = waitTicks;
                    }
                }
                int cap = tlSeen ? 480 : 240;
                if (++waitTicks > cap) { finish(false, "園・施設からの画面が見つかりません"); }
                return;
            }
            readHeader(root);
            stage = 1;
            return;
        }
        if (root == null) {
            AccessibilityNodeInfo lr = lukumiRoot();
            if (lr != null) { collectTimeline(lr); maybeTapSchoolTab(lr); awayTicks = 0; return; }
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
            if (rows.size() > 0 && !tlPhaseDone && !dateIso.isEmpty() && timelineIncompleteFor(dateIso)) {
                tlPhaseDone = true;
                stage = 2;
                tlPhaseTicks = 0;
                tlScrollSteps = 0;
                tlScrollIdle = 0;
                tlLastTapTick = -100;
                Toast.makeText(svc, "登園・降園の時刻を読み取るため、タイムラインへ切り替えます", Toast.LENGTH_LONG).show();
                return;
            }
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
        ProgressChip.hide(svc);
        Store.saveSchoolResult(svc, false, "中止しました");
        Toast.makeText(svc, "園の連絡帳の読み取りを中止しました", Toast.LENGTH_LONG).show();
        Intent i = new Intent(svc, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        svc.startActivity(i);
    }

    private void finish(boolean ok, String note) {
        running = false;
        ProgressChip.hide(svc);
        Store.setSchoolPending(svc, false);
        String dur = Store.fmtDuration(System.currentTimeMillis() - startMs);
        if (ok) {
            SchoolDraft d = parse();
            String[] tt = timeline.get(d.dateIso);
            if (tt != null) {
                if (!tt[0].isEmpty()) d.toenTime = tt[0];
                if (!tt[1].isEmpty()) d.koenTime = tt[1];
            }
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

    // ---------------- timeline (登園/降園 times) ----------------

    /** A window root belonging to Lukumi, whatever screen it shows. */
    private AccessibilityNodeInfo lukumiRoot() {
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null
                        && "com.unifa_e.hiyoko.family".equals(r.getPackageName().toString())) return r;
            }
        }
        AccessibilityNodeInfo active = svc.getRootInActiveWindow();
        if (active != null && active.getPackageName() != null
                && "com.unifa_e.hiyoko.family".equals(active.getPackageName().toString())) return active;
        return null;
    }

    /**
     * Collect 登園/降園 sightings from a Lukumi screen (in practice the
     * timeline tab). Returns how many map entries changed, so the caller
     * can treat sightings as progress. A title counts only when its card
     * subtree holds exactly one date-time; ambiguous cards are skipped
     * rather than guessed.
     */
    private int collectTimeline(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        gatherNodes(root, nodes);
        int before = timelineHash();
        for (AccessibilityNodeInfo n : nodes) {
            // Titles may live in text or in contentDescription.
            String[] cands = new String[2];
            cands[0] = n.getText() == null ? "" : n.getText().toString();
            cands[1] = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            for (String title : cands) {
                if (title.isEmpty()) continue;
                boolean toen = TimelineParser.isToenTitle(title);
                boolean koen = !toen && TimelineParser.isKoenTitle(title);
                if (!toen && !koen) continue;
                String[] dt = cardDateTime(n);
                if (dt != null) TimelineParser.merge(timeline, dt[0], dt[1], toen);
            }
        }
        return timelineHash() != before ? 1 : 0;
    }

    private int timelineHash() {
        int h = 0;
        for (Map.Entry<String, String[]> e : timeline.entrySet()) {
            h = h * 31 + e.getKey().hashCode();
            h = h * 31 + e.getValue()[0].hashCode();
            h = h * 31 + e.getValue()[1].hashCode();
        }
        return h;
    }

    /** The single date-time in the title node's card, or null if not exactly one. */
    private String[] cardDateTime(AccessibilityNodeInfo titleNode) {
        AccessibilityNodeInfo anc = titleNode;
        for (int level = 0; level < 4 && anc != null; level++) {
            anc = anc.getParent();
            if (anc == null) break;
            List<String> texts = new ArrayList<String>();
            gatherAllTexts(anc, texts);
            String[] found = null;
            boolean ambiguous = false;
            for (String s : texts) {
                String[] dt = TimelineParser.parseDateTime(s);
                if (dt == null) continue;
                if (found == null) found = dt;
                else if (!found[0].equals(dt[0]) || !found[1].equals(dt[1])) ambiguous = true;
            }
            if (found != null) return ambiguous ? null : found;
        }
        return null;
    }

    /**
     * Scroll the timeline a little so the target day's cards come into
     * view — but stop early. The feed lazy-loads months of history, so
     * "scroll until nothing changes" runs away (user report).
     * Stop when: two days already have both times, the visible content
     * stops changing (true bottom), 6 scrolls are used up, or the last
     * scroll was less than 800ms ago (let lazy loading settle).
     */
    /** gatherTexts plus contentDescriptions (Lukumi labels often live there). */
    private void gatherAllTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null) out.add(t.toString());
        CharSequence cd = node.getContentDescription();
        if (cd != null) out.add(cd.toString());
        for (int i = 0; i < node.getChildCount(); i++) gatherAllTexts(node.getChild(i), out);
    }

    /** True when this root shows timeline cards (a 登園/降園 title). */
    private boolean containsTimelineTitle(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<String>();
        gatherAllTexts(root, texts);
        for (String s : texts) {
            if (TimelineParser.isToenTitle(s) || TimelineParser.isKoenTitle(s)) return true;
        }
        return false;
    }

    /** Tap Lukumi's bottom タイムライン tab (via its clickable ancestor). */
    private boolean tapTimelineTab(AccessibilityNodeInfo root) {
        return tapTab(root, "タイムライン");
    }

    /**
     * Tap a Lukumi bottom-navigation tab by its label. Lukumi exposes
     * many labels as contentDescription rather than text (the filler's
     * findByDesc exists for this), so match both, prefer the bottom-most
     * match, and tap its centre with a gesture first — the mechanism
     * the filler/writer use for all their Lukumi taps. ACTION_CLICK on
     * an ancestor is the fallback. (v0.15.6/7 searched text only and
     * never found the tabs at all: user report, no switch.)
     */
    private boolean tapTab(AccessibilityNodeInfo root, String labelText) {
        tapTries++;
        AccessibilityNodeInfo label = findLabelNode(root, labelText);
        if (label == null) { tapLast = labelText + ":なし"; return false; }
        android.graphics.Rect r = new android.graphics.Rect();
        label.getBoundsInScreen(r);
        if (!r.isEmpty()) {
            try {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(r.centerX(), r.centerY());
                p.lineTo(r.centerX(), r.centerY());
                android.accessibilityservice.GestureDescription g =
                        new android.accessibilityservice.GestureDescription.Builder()
                                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80))
                                .build();
                if (svc.dispatchGesture(g, null, null)) { tapOks++; tapLast = labelText + ":gesture"; return true; }
            } catch (Exception ignored) { }
        }
        AccessibilityNodeInfo n = label;
        for (int i = 0; i < 5 && n != null; i++) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                tapOks++; tapLast = labelText + ":click";
                return true;
            }
            n = n.getParent();
        }
        tapLast = labelText + ":失敗";
        return false;
    }

    /** Bottom-most node whose text OR contentDescription equals the label. */
    private AccessibilityNodeInfo findLabelNode(AccessibilityNodeInfo root, String labelText) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        gatherNodes(root, nodes);
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo n : nodes) {
            if (!labelText.equals(nodeTextOrDesc(n))) continue;
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            if (r.top > bestTop) { bestTop = r.top; best = n; }
        }
        return best;
    }

    private static String nodeTextOrDesc(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && !t.toString().trim().isEmpty()) return t.toString().trim();
        CharSequence cd = n.getContentDescription();
        return cd == null ? "" : cd.toString().trim();
    }

    /**
     * The 連絡帳 screen has two sub-tabs; the report lives under
     * 園・施設から, but the tab often reopens on 家庭から (the fill
     * flow leaves it there). While the report list is not visible,
     * tap the 園・施設から sub-tab (user request).
     */
    private void maybeTapSchoolTab(AccessibilityNodeInfo lr) {
        if (!Store.isAutoTabSwitch(svc)) return;
        if (findByIdSuffix(lr, "rv_details") != null) return;
        long now = System.currentTimeMillis();
        if (now - subTapLastMs < 2000) return;
        if (findLabelNode(lr, "園・施設から") == null) return;
        if (tapTab(lr, "園・施設から")) subTapLastMs = now;
    }

    private boolean timelineIncompleteFor(String iso) {
        String[] tt = timeline.get(iso);
        return tt == null || tt[0].isEmpty() || tt[1].isEmpty();
    }

    private void scrollTimeline(AccessibilityNodeInfo root) {
        scrollTimeline(root, null);
    }

    /** targetIso != null: stop once that date has both times. */
    private void scrollTimeline(AccessibilityNodeInfo root, String targetIso) {
        if (targetIso != null) {
            if (!timelineIncompleteFor(targetIso)) return;
            if (tlScrollSteps >= 6) return;
        } else {
            // No target date yet (waiting for the report): the newest
            // day in the feed is the usual target. If its pair is
            // already visible, do not scroll at all (user report,
            // v0.15.1: scrolled even though today's stamps were on
            // screen). Older target days are handled by the stage-2
            // phase, which knows the date.
            String newest = TimelineParser.newestDate(timeline);
            if (newest != null && !timelineIncompleteFor(newest)) return;
            if (tlScrollSteps >= 2) return;
        }
        long now = System.currentTimeMillis();
        if (now - tlLastScrollMs < 800) return;
        List<String> texts = new ArrayList<String>();
        gatherTexts(root, texts);
        String sig = Integer.toString(texts.hashCode());
        if (sig.equals(tlLastVisibleSig)) {
            if (++tlScrollIdle >= 2) return;
        } else {
            tlScrollIdle = 0;
        }
        tlLastVisibleSig = sig;
        AccessibilityNodeInfo sc = findScrollable(root);
        if (sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            tlScrollSteps++;
            tlLastScrollMs = now;
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private void gatherNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) gatherNodes(node.getChild(i), out);
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
