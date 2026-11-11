package undohistory;

import ij.Macro;
import ij.plugin.frame.Recorder;

/** Captures real options or Recorder output; a command label alone is never executable evidence. */
final class MacroCapture {
    private static long generation;
    static final int MAX_TEXT = 1024 * 1024;

    static final class Ticket {
        final String direct, before;
        final Recorder recorder;
        final long generation;
        Ticket(String direct, String before, Recorder recorder, long generation) {
            this.direct = direct; this.before = before; this.recorder = recorder; this.generation = generation;
        }
    }

    static synchronized void invalidate() { generation++; }

    static synchronized Ticket begin(String command) {
        long current = ++generation;
        String options = Macro.getOptions();
        // IJ.run supplies the exact option string on its macro thread, including an explicitly empty string.
        if (options != null) return new Ticket("run(\"" + quote(command) + "\", \"" + quote(options) + "\");\n", null, null, current);
        Recorder r = Recorder.getInstance();
        String text = r != null && Recorder.record && !Recorder.scriptMode() ? r.getText() : null;
        if (text != null && text.length() > MAX_TEXT) text = null;
        return new Ticket(null, text, r, current);
    }

    static synchronized String finish(Ticket t) {
        if (t == null) return null;
        if (t.direct != null) return t.direct;
        if (t.generation != generation || t.before == null || t.recorder != Recorder.getInstance()
                || !Recorder.record || Recorder.scriptMode()) return null;
        return appended(t.before, t.recorder.getText());
    }

    static String appended(String before, String after) {
        if (before == null || after == null || after.length() > MAX_TEXT || !after.startsWith(before)) return null;
        String code = after.substring(before.length()).trim();
        // Comments alone (e.g. opening an adjustment dialog) are not a recorded operation.
        boolean executable = false;
        for (String line : code.split("\\r?\\n"))
            if (!line.trim().isEmpty() && !line.trim().startsWith("//")) executable = true;
        return executable ? code + "\n" : null;
    }

    static String quote(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
}
