package jp.papadenkilab.combookbridge;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class HowToUseActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float den = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * den);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF7F5F0);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("使い方");
        title.setTextSize(22);
        root.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(15);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, (int) (12 * den), 0, 0);
        body.setText(
            "毎朝の手順\n"
            + "1. メイン画面の「パパっと育児から読み取る」を押す\n"
            + "   パパっと育児が開き、一覧を自動でスクロールして読み取ります。そのまま触らずに待ってください。終わるとこのアプリに戻ります。\n"
            + "2. 「ルクミーへ自動入力」を押す\n"
            + "   ルクミーが開くので、連絡帳（家庭から）の入力画面を開いてください。画面を検出すると自動入力が始まり、画面の上の方に進捗（例: 自動入力 4/10：ミルク）が出ます。\n"
            + "3. 内容を確認して、送信は自分で押す\n"
            + "   自動入力は「送信確認」を押しません。ルクミーで内容を確認してから送信してください。\n\n"
            + "転記のルール\n"
            + "・対象は前日18:00以降のパパっとの全記録です\n"
            + "・睡眠は、ルクミーにデフォルトで入っている前日分を削除してから、当日分を入れ直します\n"
            + "・夕食・朝食の時刻は、パパっとの「ごはん」の開始時刻を使います\n"
            + "・ルクミーの時刻ピッカーは5分単位なので、時刻は5分未満を切り捨てて入力します（例: 7:11 → 7:10）\n"
            + "・投薬は、パパっとに薬の記録がなければ「なし」、あれば「あり」にして内容を入力します\n"
            + "・お迎えする方・お迎え時刻、機嫌（ふつう）は触りません\n\n"
            + "困ったとき\n"
            + "・項目を取りこぼす／失敗する → 「動作設定」でスピードを一段遅くしてください\n"
            + "・途中で止めたい → メイン画面の「自動入力を中止する」\n"
            + "・何が起きたか詳しく見たい → 「動作設定」の詳細ログ\n"
            + "・初めて使うときは、「動作設定」からアクセシビリティで「パパっとルクミー橋渡し」をオンにしてください（アプリを更新するとオフに戻ることがあります）\n\n"
            + "オンにできないとき（Android 13以降）\n"
            + "「制限付き設定によって管理されています」と表示されてオンにできない場合は、次を試してください。\n"
            + "1. 端末の設定 → アプリ →「パパっとルクミー橋渡し」を開く\n"
            + "2. アプリ情報の右上「\u22ee」→「制限付き設定を許可」を押す（PIN／生体認証の確認があります）\n"
            + "   「\u22ee」に出ない場合は、先にアクセシビリティの画面で本アプリを選ぶところまで進めてから戻ってください\n"
            + "3. もう一度アクセシビリティの設定で本アプリをオンにする");
        root.addView(body);

        setContentView(scroll);
    }
}
