package jp.papadenkilab.combookbridge;

/** One row read from the Papotto timeline. Plain data, no Android deps. */
public class RecordItem {
    public String dateIso;   // yyyy-MM-dd (from the daily section header)
    public String time;      // HH:mm
    public String name;      // ごはん / 哺乳瓶 / 母乳 / うんち / 寝る / 起きる / 体温 ...
    public String memo;      // memo text (may be truncated in the list view)
    public String value;     // right-side value text, e.g. ミルク200ml / 9時間25分 / 36.3℃

    public RecordItem(String dateIso, String time, String name, String memo, String value) {
        this.dateIso = dateIso == null ? "" : dateIso;
        this.time = time == null ? "" : time;
        this.name = name == null ? "" : name;
        this.memo = memo == null ? "" : memo;
        this.value = value == null ? "" : value;
    }

    public String key() {
        return dateIso + "|" + time + "|" + name + "|" + value + "|" + memo;
    }

    public String toString() {
        return dateIso + " " + time + " " + name
            + (memo.isEmpty() ? "" : " memo=" + memo)
            + (value.isEmpty() ? "" : " value=" + value);
    }
}
