import jp.papadenkilab.combookbridge.Aggregator;
import jp.papadenkilab.combookbridge.RecordItem;

public class TestAggregator {
    public static void main(String[] args) {

        java.util.List<RecordItem> xs = new java.util.ArrayList<RecordItem>();
        String d1 = "2026-01-01", d2 = "2026-01-02";
        xs.add(new RecordItem(d1, "18:30", "ごはん", "お粥 90g\n絹豆腐 50g\nとうもろこし 20g", "離乳食"));
        xs.add(new RecordItem(d1, "18:50", "お風呂", "", ""));
        xs.add(new RecordItem(d1, "18:52", "哺乳瓶", "", "ミルク140ml"));
        xs.add(new RecordItem(d1, "20:15", "寝る", "", ""));
        xs.add(new RecordItem(d2, "5:40", "起きる", "", "9時間25分"));
        xs.add(new RecordItem(d2, "6:15", "うんち", "", ""));
        xs.add(new RecordItem(d2, "6:15", "おしっこ", "", ""));
        xs.add(new RecordItem(d2, "6:15", "オムツ替", "", ""));
        xs.add(new RecordItem(d2, "6:40", "ごはん", "お粥 90g\nにんじんペー…", "離乳食"));
        xs.add(new RecordItem(d2, "6:50", "哺乳瓶", "", "ミルク200ml"));
        xs.add(new RecordItem(d2, "7:00", "うんち", "", ""));
        xs.add(new RecordItem(d2, "7:00", "オムツ替", "", ""));
        xs.add(new RecordItem(d2, "7:11", "体温", "", "36.3℃"));

        // add records that must be EXCLUDED: previous day before 18:00
        xs.add(new RecordItem("2026-01-01", "15:00", "うんち", "", ""));
        xs.add(new RecordItem("2026-01-01", "17:30", "哺乳瓶", "", "ミルク999ml"));
        // and a medicine record inside the period
        xs.add(new RecordItem("2026-01-02", "7:30", "薬", "解熱剤", ""));
        Aggregator.Draft d = Aggregator.aggregate(xs);
        String out = d.format();
        System.out.println(out);
        check(out.contains("体温: 36.3℃（07:11）"), "temperature");
        check(out.contains("睡眠: 20:15 〜 05:40"), "sleep");
        check(out.contains("便: あり 06:15、07:00"), "stool excludes 15:00");
        check(out.contains("18:52 140ml") && out.contains("06:50 200ml") && !out.contains("999ml"), "milk period");
        check(out.contains("夕食: 18:30"), "dinner start time");
        check(out.contains("朝食: 06:40"), "breakfast start time");
        check(out.contains("省略あり"), "truncation flag");
        check(out.contains("投薬: あり 07:30 解熱剤"), "medicine");
        check(out.contains("授乳: なし"), "breast none");
        System.out.println("ALL TESTS PASSED");
    }
    static void check(boolean ok, String label) {
        if (!ok) { System.out.println("FAIL: " + label); System.exit(1); }
        System.out.println("ok: " + label);
    }
}
