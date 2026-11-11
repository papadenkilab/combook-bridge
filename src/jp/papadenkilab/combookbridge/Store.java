package jp.papadenkilab.combookbridge;

import android.content.Context;
import android.content.SharedPreferences;

public class Store {
    private static final String PREF = "bridge";
    public static final String KEY_DRAFT = "draft";
    public static final String KEY_RAW = "raw";
    public static final String KEY_PENDING = "pending_scan";
    public static final String KEY_WHEN = "when";
    public static final String KEY_DATA = "data";
    public static final String KEY_FILL = "pending_fill";
    public static final String KEY_LOG = "fill_log";
    public static final String KEY_SPEED = "speed_level";
    public static final String KEY_SCAN_OK = "scan_ok";
    public static final String KEY_SCAN_SUMMARY = "scan_summary";
    public static final String KEY_SCAN_WHEN = "scan_when";
    public static final String KEY_FILL_WHEN = "fill_when";
    public static final String KEY_FILL_OK = "fill_ok";
    public static final String KEY_FILL_SUMMARY = "fill_summary";
    public static final String KEY_PENDING_SCHOOL = "pending_school";
    public static final String KEY_SCHOOL_DATA = "school_data";
    public static final String KEY_SCHOOL_DRAFT = "school_draft";
    public static final String KEY_REV_WHEN = "rev_when";
    public static final String KEY_REV_OK = "rev_ok";
    public static final String KEY_REV_SUMMARY = "rev_summary";
    public static final String KEY_WRITE_PENDING = "write_pending";
    public static final String KEY_WRITE_WHEN = "write_when";
    public static final String KEY_WRITE_OK = "write_ok";
    public static final String KEY_WRITE_SUMMARY = "write_summary";
    public static final String KEY_IMPORTED = "imported_dates";
    public static final String KEY_PROGRESS = "progress_text";

    public static void setProgress(Context c, String text) {
        prefs(c).edit().putString(KEY_PROGRESS, text == null ? "" : text).apply();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void saveDraft(Context c, String draft, String raw) {
        prefs(c).edit()
            .putString(KEY_DRAFT, draft)
            .putString(KEY_RAW, raw)
            .putLong(KEY_WHEN, System.currentTimeMillis())
            .putBoolean(KEY_PENDING, false)
            .apply();
    }

    public static void setPending(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_PENDING, v).apply();
    }

    public static boolean isPending(Context c) {
        return prefs(c).getBoolean(KEY_PENDING, false);
    }

    public static void saveData(Context c, String data) {
        prefs(c).edit().putString(KEY_DATA, data).apply();
    }

    public static String getData(Context c) {
        return prefs(c).getString(KEY_DATA, "");
    }

    public static void setFillPending(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_FILL, v).apply();
    }

    public static boolean isFillPending(Context c) {
        return prefs(c).getBoolean(KEY_FILL, false);
    }

    public static void saveLog(Context c, String log) {
        prefs(c).edit().putString(KEY_LOG, log).apply();
    }

    public static String getLog(Context c) {
        return prefs(c).getString(KEY_LOG, "");
    }

    // ---- speed level: 0 = fast, 1 = standard, 2 = slow ----
    public static int getSpeedLevel(Context c) {
        return prefs(c).getInt(KEY_SPEED, 0);
    }

    public static void setSpeedLevel(Context c, int level) {
        prefs(c).edit().putInt(KEY_SPEED, level).apply();
    }

    /** Main loop interval (ms) of the Lukumi filler for the speed level. */
public static String fmtDuration(long ms) {
long sec = Math.max(0, ms / 1000);
if (sec < 60) return sec + "\u79d2";
return (sec / 60) + "\u5206" + String.format(java.util.Locale.JAPAN, "%02d", sec % 60) + "\u79d2";
}


    // ---- nursery-report (reverse) scan ----
    public static void setSchoolPending(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_PENDING_SCHOOL, v).apply();
    }
    public static boolean isSchoolPending(Context c) {
        return prefs(c).getBoolean(KEY_PENDING_SCHOOL, false);
    }
    public static void saveSchoolData(Context c, String data) {
        prefs(c).edit().putString(KEY_SCHOOL_DATA, data).apply();
    }
    public static String getSchoolData(Context c) {
        return prefs(c).getString(KEY_SCHOOL_DATA, "");
    }
    public static void saveSchoolDraftText(Context c, String text) {
        prefs(c).edit().putString(KEY_SCHOOL_DRAFT, text).apply();
    }
    public static String getSchoolDraftText(Context c) {
        return prefs(c).getString(KEY_SCHOOL_DRAFT, "");
    }
    public static void saveSchoolResult(Context c, boolean ok, String summary) {
        prefs(c).edit()
            .putLong(KEY_REV_WHEN, System.currentTimeMillis())
            .putBoolean(KEY_REV_OK, ok)
            .putString(KEY_REV_SUMMARY, summary)
            .apply();
    }


    // ---- nursery -> Papotto write ----
    public static void setWritePending(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_WRITE_PENDING, v).apply();
    }
    public static boolean isWritePending(Context c) {
        return prefs(c).getBoolean(KEY_WRITE_PENDING, false);
    }
    public static void saveWriteResult(Context c, boolean ok, String summary) {
        prefs(c).edit()
            .putLong(KEY_WRITE_WHEN, System.currentTimeMillis())
            .putBoolean(KEY_WRITE_OK, ok)
            .putString(KEY_WRITE_SUMMARY, summary)
            .apply();
    }
    public static boolean isImported(Context c, String dateIso) {
        if (dateIso == null || dateIso.isEmpty()) return false;
        String s = prefs(c).getString(KEY_IMPORTED, "");
        if (s == null) return false;
        for (String d : s.split(",")) if (d.equals(dateIso)) return true;
        return false;
    }
    public static void markImported(Context c, String dateIso) {
        if (dateIso == null || dateIso.isEmpty() || isImported(c, dateIso)) return;
        String s = prefs(c).getString(KEY_IMPORTED, "");
        String v = (s == null || s.isEmpty()) ? dateIso : s + "," + dateIso;
        prefs(c).edit().putString(KEY_IMPORTED, v).apply();
    }


/** Stale-info cleanup (user rule, ): a read/write result - and
 *  the draft that goes with it - is cleared once it is more than 6 hours
 *  old, per pipeline, so stale information is never shown or reused. */
public static void clearStale(Context c) {
SharedPreferences p = prefs(c);
long now = System.currentTimeMillis();
long SIX_H = 6L * 3600L * 1000L;
SharedPreferences.Editor e = p.edit();
boolean changed = false;
long scanWhen = p.getLong(KEY_WHEN, 0);
if (scanWhen > 0 && now - scanWhen > SIX_H) {
e.remove(KEY_DRAFT).remove(KEY_RAW).remove(KEY_WHEN).remove(KEY_DATA);
changed = true;
}
long scanResWhen = p.getLong(KEY_SCAN_WHEN, 0);
if (scanResWhen > 0 && now - scanResWhen > SIX_H) {
e.remove(KEY_SCAN_OK).remove(KEY_SCAN_SUMMARY).remove(KEY_SCAN_WHEN).remove(KEY_SCAN_WHEN);
changed = true;
}
long fillWhen = p.getLong(KEY_FILL_WHEN, 0);
if (fillWhen > 0 && now - fillWhen > SIX_H) {
e.remove(KEY_FILL_WHEN).remove(KEY_FILL_OK).remove(KEY_FILL_SUMMARY);
changed = true;
}
long revWhen = p.getLong(KEY_REV_WHEN, 0);
if (revWhen > 0 && now - revWhen > SIX_H) {
e.remove(KEY_SCHOOL_DATA).remove(KEY_SCHOOL_DRAFT)
.remove(KEY_REV_WHEN).remove(KEY_REV_OK).remove(KEY_REV_SUMMARY);
changed = true;
}
long writeWhen = p.getLong(KEY_WRITE_WHEN, 0);
if (writeWhen > 0 && now - writeWhen > SIX_H) {
e.remove(KEY_WRITE_WHEN).remove(KEY_WRITE_OK).remove(KEY_WRITE_SUMMARY);
changed = true;
}
if (changed) e.apply();
}

public static void clearResults(Context c) {
prefs(c).edit()
.remove(KEY_DRAFT).remove(KEY_RAW).remove(KEY_WHEN).remove(KEY_DATA)
.remove(KEY_LOG)
.remove(KEY_SCAN_OK).remove(KEY_SCAN_SUMMARY)
.remove(KEY_FILL_WHEN).remove(KEY_FILL_OK).remove(KEY_FILL_SUMMARY)
.remove(KEY_SCHOOL_DATA).remove(KEY_SCHOOL_DRAFT)
.remove(KEY_REV_WHEN).remove(KEY_REV_OK).remove(KEY_REV_SUMMARY)
.remove(KEY_PENDING_SCHOOL)
.remove(KEY_WRITE_WHEN).remove(KEY_WRITE_OK).remove(KEY_WRITE_SUMMARY)
.remove(KEY_WRITE_PENDING)
.apply();
}


    public static int tickMs(Context c) {
        switch (getSpeedLevel(c)) {
            case 5: return 50;
            case 4: return 70;
            case 3: return 110;
            case 1: return 300;
            case 2: return 450;
            default: return 180;
        }
    }

    // ---- last run results (for the concise main-page status) ----
    // ---- required items: a scan missing a required item is shown as an error ----
    public static final String[] REQ_KEYS = {"temp", "stool", "sleep", "milk", "breast", "dinner", "breakfast", "medicine"};
    public static final String[] REQ_LABELS = {"\u4f53\u6e29", "\u4fbf", "\u7761\u7720", "\u30df\u30eb\u30af", "\u6388\u4e73", "\u5915\u98df", "\u671d\u98df", "\u85ac"};
    public static final boolean[] REQ_DEFAULTS = {true, true, true, true, false, true, true, false};

    public static boolean getRequired(Context c, int idx) {
        return prefs(c).getBoolean("req_" + REQ_KEYS[idx], REQ_DEFAULTS[idx]);
    }

    public static void setRequired(Context c, int idx, boolean v) {
        prefs(c).edit().putBoolean("req_" + REQ_KEYS[idx], v).apply();
    }


    public static void saveScanResult(Context c, boolean ok, String summary) {
        prefs(c).edit()
            .putLong(KEY_SCAN_WHEN, System.currentTimeMillis())
            .putBoolean(KEY_SCAN_OK, ok)
            .putString(KEY_SCAN_SUMMARY, summary)
            .apply();
    }

    public static void saveFillResult(Context c, boolean ok, String summary) {
        prefs(c).edit()
            .putLong(KEY_FILL_WHEN, System.currentTimeMillis())
            .putBoolean(KEY_FILL_OK, ok)
            .putString(KEY_FILL_SUMMARY, summary)
            .apply();
    }

    }
