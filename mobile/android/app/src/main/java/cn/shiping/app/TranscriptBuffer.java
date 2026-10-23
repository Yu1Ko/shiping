package cn.shiping.app;

/** Tracks the consumed prefix of an evolving ASR sentence, without replaying it. */
final class TranscriptBuffer {
    private String complete = "", partial = "";
    private int consumed;
    String text() { return complete + partial.substring(Math.min(consumed, partial.length())); }
    void restore(String saved) { complete = saved; partial = ""; consumed = 0; }
    boolean sentence(String value, boolean end) {
        partial = value;
        if (end) finish();
        if (complete.length() <= 30000) return false;
        complete = complete.substring(complete.length() - 30000);
        return true;
    }
    void finish() {
        String rest = partial.substring(Math.min(consumed, partial.length()));
        if (!rest.isEmpty()) complete += rest + "\n";
        partial = ""; consumed = 0;
    }
    boolean consume(String expected) {
        if (!text().startsWith(expected)) return false;
        if (expected.length() <= complete.length()) complete = complete.substring(expected.length());
        else { consumed += expected.length() - complete.length(); complete = ""; }
        return true;
    }
}
