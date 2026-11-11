package jp.papadenkilab.combookbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String PKG_PAPATTO = "jp.firstascent.papaikuji";
    static final String PKG_LUKUMI = "com.unifa_e.hiyoko.family";

    private TextView statusView;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            pollHandler.postDelayed(this, 700);
        }
    };
    private Button btnScan;
    private Button btnFill;
    private Button btnSchool;
    private Button btnWrite;
private Button btnA11yWarn;
    private float den;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        den = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF7F5F0);

        TextView title = new TextView(this);
        title.setText("パパっと → ルクミー 橋渡し");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF333333);
        root.addView(title);

        TextView ver = new TextView(this);
        ver.setText("v1.0.0 ・ 送信は必ず手動です（自動入力は送信確認の手前で止まります）");
        ver.setTextSize(13);
        ver.setTextColor(0xFF777777);
        ver.setPadding(0, dp(4), 0, dp(16));
        root.addView(ver);

// ---- accessibility-off banner (steers the user to the settings page) ----
btnA11yWarn = new Button(this);
btnA11yWarn.setText("\u26a0 \u30a2\u30af\u30bb\u30b7\u30d3\u30ea\u30c6\u30a3\u304c\u30aa\u30d5\u3067\u3059\n\u52d5\u4f5c\u8a2d\u5b9a\u3067ON\u306b\u3057\u3066\u304f\u3060\u3055\u3044\n\uff08ON\u306b\u3067\u304d\u306a\u3044\u5834\u5408\u306f\u300c\u5236\u9650\u4ed8\u304d\u8a2d\u5b9a\u300d\u306e\u8a31\u53ef\u304c\u5fc5\u8981\u3067\u3059\uff09");
btnA11yWarn.setTextSize(16);
btnA11yWarn.setAllCaps(false);
btnA11yWarn.setTextColor(0xFFFFFFFF);
GradientDrawable warnBg = new GradientDrawable();
warnBg.setColor(0xFFC62828);
warnBg.setCornerRadius(dp(12));
btnA11yWarn.setBackground(warnBg);
btnA11yWarn.setOnClickListener(new View.OnClickListener() {
public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
});
btnA11yWarn.setMinHeight(dp(64));
btnA11yWarn.setPadding(dp(12), dp(10), dp(12), dp(10));
LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
wLp.setMargins(0, 0, 0, dp(14));
btnA11yWarn.setLayoutParams(wLp);
btnA11yWarn.setVisibility(View.GONE);
root.addView(btnA11yWarn);

        // ---- concise status card ----
        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setLineSpacing(0, 1.25f);
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(14));
        card.setStroke(dp(1), 0xFFE3DED4);
        statusView.setBackground(card);
        root.addView(statusView);
        LinearLayout.LayoutParams cardLp = (LinearLayout.LayoutParams) statusView.getLayoutParams();
        cardLp.setMargins(0, 0, 0, dp(18));
        statusView.setLayoutParams(cardLp);

        root.addView(sectionHeader("☀ 登園前（朝）", "パパっと育児 → ルクミーへ転記（家庭からの連絡帳）", 0xFFE65100));
        btnScan = bigButton("パパっと育児から読み取る", 0xFF2E7D32);
        btnScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startScan(); }
        });
        root.addView(btnScan);

        btnFill = bigButton("ルクミーへ自動入力", 0xFF1565C0);
        btnFill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startFill(); }
        });
        root.addView(btnFill);
        root.addView(sectionHeader("🌙 降園後（夕方）", "ルクミー → パパっと育児へ転記（園からの連絡帳）", 0xFF283593));
        btnSchool = bigButton("園の連絡帳を読み取る", 0xFF6A1B9A);
        btnSchool.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startSchoolScan(); }
        });
        root.addView(btnSchool);
        btnWrite = bigButton("パパっとへ書き込む", 0xFFEF6C00);
        btnWrite.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startWrite(); }
        });
        root.addView(btnWrite);

        Button btnAbort = new Button(this);
        btnAbort.setText("すべて中止する");
        btnAbort.setTextSize(15);
        btnAbort.setTextColor(0xFFC62828);
        btnAbort.setAllCaps(false);
        btnAbort.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.setFillPending(MainActivity.this, false);
                Store.setWritePending(MainActivity.this, false);
                Store.setSchoolPending(MainActivity.this, false);
                Toast.makeText(MainActivity.this, "中止しました", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnAbort, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        // ---- sub pages ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        Button btnHowto = subButton("？ 使い方");
        btnHowto.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, HowToUseActivity.class)); }
        });
        row.addView(btnHowto, new LinearLayout.LayoutParams(0, dp(58), 1));

        Button btnSettingsPage = subButton("⚙ 動作設定");
        btnSettingsPage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(58), 1);
        sLp.setMargins(dp(10), 0, 0, 0);
        row.addView(btnSettingsPage, sLp);
        root.addView(row);

        setContentView(root);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        pollHandler.removeCallbacks(pollTick);
        pollHandler.post(pollTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollTick);
    }

    private int dp(int v) { return (int) (v * den); }

    /** Group label separating the morning (before drop-off) and evening
     *  (after pickup) flows on the main page. */
    private View sectionHeader(String title, String sub, int color) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        g.addView(t);
        TextView s2 = new TextView(this);
        s2.setText(sub);
        s2.setTextSize(13);
        s2.setTextColor(0xFF757575);
        g.addView(s2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(8));
        g.setLayoutParams(lp);
        return g;
    }

    private Button bigButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        lp.setMargins(0, 0, 0, dp(12));
        b.setLayoutParams(lp);
        return b;
    }

    private Button subButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(0xFF444444);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFD8D2C8);
        b.setBackground(bg);
        return b;
    }

    private static boolean isToday(long ts) {
        if (ts <= 0) return false;
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ts);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String hhmm(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.JAPAN).format(new java.util.Date(ts));
    }

    /** Concise status: one line per phase; failures in red bold. */
    private void refreshStatus() {
        if (statusView == null) return;
        Store.clearStale(this);
if (btnA11yWarn != null) btnA11yWarn.setVisibility(A11y.isEnabled(this) ? View.GONE : View.VISIBLE);
        android.content.SharedPreferences p = Store.prefs(this);
        SpannableStringBuilder sb = new SpannableStringBuilder();

        long scanWhen = p.getLong(Store.KEY_WHEN, 0);
        boolean scanOk = p.getBoolean(Store.KEY_SCAN_OK, false);
        String scanSummary = p.getString(Store.KEY_SCAN_SUMMARY, "");
        sb.append("読み取り　");
        if (scanWhen <= 0) {
            sb.append("未実行");
        } else if (scanOk) {
            sb.append("✓ ").append(isToday(scanWhen) ? "今日 " : "").append(hhmm(scanWhen));
            if (scanSummary != null && !scanSummary.isEmpty()) sb.append(" ・ ").append(scanSummary);
        } else {
            int s = sb.length();
            sb.append("✗ 取得失敗");
            if (scanSummary != null && !scanSummary.isEmpty()) sb.append(" ・ ").append(scanSummary);
            sb.setSpan(new ForegroundColorSpan(0xFFC62828), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        long fillWhen = p.getLong(Store.KEY_FILL_WHEN, 0);
        boolean fillOk = p.getBoolean(Store.KEY_FILL_OK, false);
        String fillSummary = p.getString(Store.KEY_FILL_SUMMARY, "");
        sb.append("\n転記　　　");
        if (fillWhen <= 0) {
            sb.append("未実行");
        } else if (fillOk) {
            sb.append("✓ ").append(isToday(fillWhen) ? "今日 " : "").append(hhmm(fillWhen));
            if (fillSummary != null && !fillSummary.isEmpty()) sb.append(" ・ ").append(fillSummary);
        } else {
            int s = sb.length();
            sb.append("✗ 転記失敗");
            if (fillSummary != null && !fillSummary.isEmpty()) sb.append(" ・ ").append(fillSummary);
            sb.setSpan(new ForegroundColorSpan(0xFFC62828), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        long revWhen = p.getLong(Store.KEY_REV_WHEN, 0);
        boolean revOk = p.getBoolean(Store.KEY_REV_OK, false);
        String revSummary = p.getString(Store.KEY_REV_SUMMARY, "");
        sb.append("\n園読取　");
        if (revWhen <= 0) {
            sb.append("未実行");
        } else if (revOk) {
            sb.append("✓ ").append(isToday(revWhen) ? "今日 " : "").append(hhmm(revWhen));
            if (revSummary != null && !revSummary.isEmpty()) sb.append(" ・ ").append(revSummary);
        } else {
            int s = sb.length();
            sb.append("✗ 取得失敗");
            if (revSummary != null && !revSummary.isEmpty()) sb.append(" ・ ").append(revSummary);
            sb.setSpan(new ForegroundColorSpan(0xFFC62828), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        long writeWhen = p.getLong(Store.KEY_WRITE_WHEN, 0);
        boolean writeOk = p.getBoolean(Store.KEY_WRITE_OK, false);
        String writeSummary = p.getString(Store.KEY_WRITE_SUMMARY, "");
        sb.append("\n園書込　");
        if (writeWhen <= 0) {
            sb.append("未実行");
        } else if (writeOk) {
            sb.append("✓ ").append(isToday(writeWhen) ? "今日 " : "").append(hhmm(writeWhen));
            if (writeSummary != null && !writeSummary.isEmpty()) sb.append(" ・ ").append(writeSummary);
        } else {
            int s = sb.length();
            sb.append("✗ 書込失敗");
            if (writeSummary != null && !writeSummary.isEmpty()) sb.append(" ・ ").append(writeSummary);
            sb.setSpan(new ForegroundColorSpan(0xFFC62828), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        String prog = p.getString(Store.KEY_PROGRESS, "");
        if (prog != null && !prog.isEmpty()) {
            sb.append("\n進捗　").append(prog);
        }
        statusView.setText(sb);

        btnScan.setText((isToday(scanWhen) ? "☑️ " : "") + "パパっと育児から読み取る");
        btnFill.setText((isToday(fillWhen) ? "☑️ " : "") + "ルクミーへ自動入力");
        btnSchool.setText((isToday(revWhen) ? "☑️ " : "") + "園の連絡帳を読み取る");
        btnWrite.setText((isToday(writeWhen) ? "☑️ " : "") + "パパっとへ書き込む");
    }

private boolean requireA11y() {
if (A11y.isEnabled(this)) return true;
Toast.makeText(this, "\u30a2\u30af\u30bb\u30b7\u30d3\u30ea\u30c6\u30a3\u304c\u30aa\u30d5\u3067\u3059\u3002\u52d5\u4f5c\u8a2d\u5b9a\u3067ON\u306b\u3057\u3066\u304f\u3060\u3055\u3044", Toast.LENGTH_LONG).show();
startActivity(new Intent(this, SettingsActivity.class));
return false;
}
private void startFill() {
if (!requireA11y()) return;
        String data = Store.getData(this);
        if (data == null || data.isEmpty()) {
            Toast.makeText(this, "先に読み取りをしてください", Toast.LENGTH_LONG).show();
            return;
        }
        Store.saveLog(this, "");
        Store.setFillPending(this, true);
        PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(PKG_LUKUMI);
        if (i == null) {
            Toast.makeText(this, "ルクミーが見つかりません", Toast.LENGTH_LONG).show();
            Store.setFillPending(this, false);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "ルクミーを起動できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Store.setFillPending(this, false);
            return;
        }
        Toast.makeText(this, "ルクミーで連絡帳（家庭から）の入力画面を開いてください", Toast.LENGTH_LONG).show();
    }

private void startSchoolScan() {
if (!requireA11y()) return;
        Store.setSchoolPending(this, true);
        PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(PKG_LUKUMI);
        if (i == null) {
            Toast.makeText(this, "ルクミーが見つかりません", Toast.LENGTH_LONG).show();
            Store.setSchoolPending(this, false);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "ルクミーを起動できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Store.setSchoolPending(this, false);
            return;
        }
        Toast.makeText(this, "ルクミーで連絡帳（園・施設から）を開き、対象の日付を表示してください", Toast.LENGTH_LONG).show();
    }


private void startWrite() {
if (!requireA11y()) return;
        String data = Store.getSchoolData(this);
        if (data == null || data.isEmpty()) {
            Toast.makeText(this, "先に園の連絡帳を読み取ってください", Toast.LENGTH_LONG).show();
            return;
        }
        final SchoolDraft d = SchoolDraft.fromData(data);
        if (d.dateIso == null || d.dateIso.isEmpty()) {
            Toast.makeText(this, "対象日が読み取れていません", Toast.LENGTH_LONG).show();
            return;
        }
        if (Store.isImported(this, d.dateIso)) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("取込済みの日付です")
                .setMessage((d.dateLabel.isEmpty() ? d.dateIso : d.dateLabel) + " はすでにパパっとへ書き込み済みです。もう一度書き込むと記録が重複します。続けますか？")
                .setNegativeButton("やめる", null)
                .setPositiveButton("続ける", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dlg, int which) { launchWrite(); }
                })
                .show();
            return;
        }
        launchWrite();
    }

private void launchWrite() {
        Store.saveLog(this, "");
        Store.setWritePending(this, true);
        PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(PKG_PAPATTO);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(PKG_PAPATTO, PKG_PAPATTO + ".MainActivity");
            i.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "パパっと育児を起動できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Store.setWritePending(this, false);
            return;
        }
        Toast.makeText(this, "書き込み中です。そのままお待ちください", Toast.LENGTH_LONG).show();
    }


private void startScan() {
if (!requireA11y()) return;
        Store.setPending(this, true);
        PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(PKG_PAPATTO);
        if (i == null) {
            // fallback: explicit component (Papotto's only activity is .MainActivity)
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(PKG_PAPATTO, PKG_PAPATTO + ".MainActivity");
            i.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "パパっと育児を起動できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Store.setPending(this, false);
            return;
        }
        Toast.makeText(this, "読み取り中です。そのままお待ちください", Toast.LENGTH_LONG).show();
    }

}