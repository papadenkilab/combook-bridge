package jp.papadenkilab.combookbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

private TextView detailView;
private TextView a11yState;
private TextView a11yHint;
private float den;

@Override
protected void onCreate(Bundle savedInstanceState) {
super.onCreate(savedInstanceState);
den = getResources().getDisplayMetrics().density;

ScrollView scroll = new ScrollView(this);
LinearLayout root = new LinearLayout(this);
root.setOrientation(LinearLayout.VERTICAL);
int pad = dp(20);
root.setPadding(pad, pad, pad, pad);
root.setBackgroundColor(0xFFF7F5F0);
scroll.addView(root);

TextView title = new TextView(this);
title.setText("動作設定");
title.setTextSize(22);
title.setPadding(0, 0, 0, dp(14));
root.addView(title);

// ---- accessibility ----
a11yState = new TextView(this);
a11yState.setTextSize(15);
root.addView(a11yState);

Button btnA11y = new Button(this);
btnA11y.setText("アクセシビリティ設定を開く");
btnA11y.setTextSize(16);
btnA11y.setAllCaps(false);
btnA11y.setOnClickListener(new View.OnClickListener() {
public void onClick(View v) {
startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
Toast.makeText(SettingsActivity.this, "「パパっとルクミー橋渡し」をオンにしてください", Toast.LENGTH_LONG).show();
}
});
root.addView(btnA11y, new LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

a11yHint = new TextView(this);
a11yHint.setTextSize(13);
a11yHint.setPadding(0, dp(8), 0, 0);
a11yHint.setText("オンにできないとき（Android 13以降）：「制限付き設定によって管理されています」と表示される場合は、端末の設定 → アプリ →「パパっとルクミー橋渡し」→ アプリ情報の右上「\u22ee」→「制限付き設定を許可」を押してから、もう一度上のボタンからオンにしてください。");
root.addView(a11yHint);

// ---- speed ----
TextView speedLabel = new TextView(this);
speedLabel.setText("\u52d5\u4f5c\u30b9\u30d4\u30fc\u30c9\uff08\u30b9\u30af\u30ed\u30fc\u30eb\u30fb\u6570\u5024\u5909\u66f4\u306e\u9593\u9694\uff09\n\u901f\u3044\u307b\u3069\u51e6\u7406\u306f\u77ed\u304f\u306a\u308a\u307e\u3059\u3002\u5931\u6557\u3057\u305f\u9805\u76ee\u306f\u4e00\u6bb5\u9045\u3044\u901f\u5ea6\u3067\u81ea\u52d5\u3067\u3084\u308a\u76f4\u3057\u307e\u3059\u3002" + "\nこの設定はルクミー入力とパパっと書込の両方に効きます。");
speedLabel.setTextSize(15);
speedLabel.setPadding(0, dp(20), 0, dp(6));
root.addView(speedLabel);

RadioGroup group = new RadioGroup(this);
final String[] names = {"\u9650\u754c", "\u6700\u901f", "\u8d85\u901f\u3044", "\u901f\u3044", "\u6a19\u6e96", "\u3086\u3063\u304f\u308a"};
final int[] levels = {5, 4, 3, 0, 1, 2};
for (int i = 0; i < names.length; i++) {
RadioButton rb = new RadioButton(this);
rb.setText(names[i]);
rb.setTextSize(16);
rb.setId(100 + i);
group.addView(rb);
}
int cur = Store.getSpeedLevel(this);
int curIdx = 1;
for (int i = 0; i < levels.length; i++) if (levels[i] == cur) curIdx = i;
group.check(100 + curIdx);
group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
public void onCheckedChanged(RadioGroup g, int checkedId) {
int idx = checkedId - 100;
Store.setSpeedLevel(SettingsActivity.this, levels[idx]);
Toast.makeText(SettingsActivity.this, "\u30b9\u30d4\u30fc\u30c9\u3092\u300c" + names[idx] + "\u300d\u306b\u3057\u307e\u3057\u305f", Toast.LENGTH_SHORT).show();
}
});
root.addView(group);

// ---- required items ----
TextView reqLabel = new TextView(this);
reqLabel.setText("\u5fc5\u9808\u9805\u76ee\uff08\u8aad\u307f\u53d6\u308c\u306a\u3051\u308c\u3070\u30a8\u30e9\u30fc\u8868\u793a\uff09");
reqLabel.setTextSize(15);
reqLabel.setPadding(0, dp(20), 0, dp(6));
root.addView(reqLabel);
for (int idx = 0; idx < Store.REQ_KEYS.length; idx++) {
final int fi = idx;
CheckBox cb = new CheckBox(this);
cb.setText(Store.REQ_LABELS[idx]);
cb.setTextSize(15);
cb.setChecked(Store.getRequired(this, idx));
cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
public void onCheckedChanged(CompoundButton b, boolean isChecked) {
Store.setRequired(SettingsActivity.this, fi, isChecked);
}
});
root.addView(cb);
}

// ---- detailed log ----
TextView logLabel = new TextView(this);
logLabel.setText("詳細ログ（直近の読み取り結果と転記ログ）");
logLabel.setTextSize(15);
logLabel.setPadding(0, dp(20), 0, dp(6));
root.addView(logLabel);

detailView = new TextView(this);
detailView.setTextSize(13);
detailView.setMovementMethod(new ScrollingMovementMethod());
root.addView(detailView);

Button btnClear = new Button(this);
btnClear.setText("\u7d50\u679c\u3092\u30af\u30ea\u30a2\uff08\u8aad\u307f\u53d6\u308a\u30fb\u8ee2\u8a18\u30fb\u4e0b\u66f8\u304d\uff09");
btnClear.setTextSize(14);
btnClear.setAllCaps(false);
btnClear.setTextColor(0xFFC62828);
btnClear.setOnClickListener(new View.OnClickListener() {
public void onClick(View v) {
new android.app.AlertDialog.Builder(SettingsActivity.this)
.setTitle("\u7d50\u679c\u3092\u30af\u30ea\u30a2\u3057\u307e\u3059\u304b\uff1f")
.setMessage("\u8aad\u307f\u53d6\u308a\u7d50\u679c\u30fb\u8ee2\u8a18\u7d50\u679c\u30fb\u8a73\u7d30\u30ed\u30b0\u30fb\u4e0b\u66f8\u304d\u30c7\u30fc\u30bf\u3092\u6d88\u53bb\u3057\u307e\u3059\u3002\u6b21\u306b\u81ea\u52d5\u5165\u529b\u3059\u308b\u524d\u306b\u3001\u3082\u3046\u4e00\u5ea6\u8aad\u307f\u53d6\u308a\u304c\u5fc5\u8981\u3067\u3059\u3002")
.setPositiveButton("\u30af\u30ea\u30a2\u3059\u308b", new android.content.DialogInterface.OnClickListener() {
public void onClick(android.content.DialogInterface dialog, int which) {
Store.clearResults(SettingsActivity.this);
refresh();
android.widget.Toast.makeText(SettingsActivity.this, "\u7d50\u679c\u3092\u30af\u30ea\u30a2\u3057\u307e\u3057\u305f", android.widget.Toast.LENGTH_SHORT).show();
}
})
.setNegativeButton("\u30ad\u30e3\u30f3\u30bb\u30eb", null)
.show();
}
});
LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
cLp.setMargins(0, dp(18), 0, dp(24));
root.addView(btnClear, cLp);

setContentView(scroll);
refresh();
}

@Override
protected void onResume() {
super.onResume();
refresh();
}

private int dp(int v) { return (int) (v * den);}

private void refresh() {
boolean enabled = A11y.isEnabled(this);
if (a11yState!= null) {
a11yState.setText("アクセシビリティ: " + (enabled? "有効 ✓": "無効（下のボタンからオンにしてください）"));
if (a11yHint != null) a11yHint.setVisibility(enabled? android.view.View.GONE : android.view.View.VISIBLE);
}
if (detailView == null) return;
StringBuilder sb = new StringBuilder();
String draft = Store.prefs(this).getString(Store.KEY_DRAFT, "");
if (draft!= null &&!draft.isEmpty()) sb.append("\n").append(draft).append("\n\n");
String log = Store.getLog(this);
String school = Store.getSchoolDraftText(this);
if (school != null && !school.isEmpty()) sb.append("\n\n\u3010\u5712\u306e\u9023\u7d61\u5e33 \u4e0b\u66f8\u304d\u3011\n").append(school);
if (log!= null &&!log.isEmpty()) sb.append("\n").append(log);
if (sb.length() == 0) sb.append("まだ記録がありません。");
detailView.setText(sb.toString());
}
}
