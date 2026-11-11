package undohistory;

import java.util.Arrays;

/** Pure export and recorder-boundary checks; no windows or user preferences are needed. */
public final class MacroTests {
    static int pass;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); pass++; }
    static Engine.Row row(String label, String code) {
        Engine.State state = new Engine.State(label, null); state.macroCode = code;
        return new Engine.Row(state, false, false);
    }
    public static void main(String[] args) {
        check("run(\"Blur\", \"sigma=2.75\");\n".equals(MacroCapture.appended("// prior\n", "// prior\nrun(\"Blur\", \"sigma=2.75\");\n")), "exact recorded options retained");
        check(MacroCapture.appended("old", "edited text") == null, "recorder edits invalidate boundary");
        check(MacroCapture.appended("old", "old") == null, "no output is missing code");
        check(MacroCapture.appended("", "// run(\"Brightness/Contrast...\");\n") == null, "comments are not executable evidence");
        check(MacroCapture.quote("a\\b\"c\nd\r").equals("a\\\\b\\\"c\\nd\\r"), "options escaped as macro string");
        MacroExport complete = new MacroExport(Arrays.asList(row("Invert", "run(\"Invert\");\n"), row("Gaussian Blur", "run(\"Gaussian Blur...\", \"sigma=2.75\");\n")), false);
        check(complete.missing == 0 && !complete.code.contains("exit("), "complete code is runnable");
        check(complete.code.indexOf("// Invert") < complete.code.indexOf("// Gaussian Blur"), "selected history order retained");
        MacroExport missing = new MacroExport(Arrays.asList(row("Brush", null), row("Invert", "run(\"Invert\");\n")), true);
        check(missing.missing == 1 && missing.code.indexOf("exit(") < missing.code.indexOf("run("), "draft stops before any operation");
        check(missing.code.contains("omitted or removed"), "gaps are disclosed");
        MacroExport injection = new MacroExport(Arrays.asList(row("label\nrun(\"Delete\");", null)), false);
        check(injection.code.contains("// label run(\"Delete\");"), "labels cannot inject executable lines");
        Engine.Row a = row("same", null), b = row("same", null);
        check(a.key != b.key, "identical labels have distinct stable identities");
        Engine.State excluded = new Engine.State("Excluded blur", null);
        excluded.macroCode = "run(\"Gaussian Blur...\", \"sigma=2\");\nrun(\"Invert\");\n";
        excluded.included = false; excluded.note = "Review\nrun(\"Delete\");";
        MacroExport comments = new MacroExport(Arrays.asList(new Engine.Row(excluded,false,false)),false);
        check(comments.missing == 0 && comments.excluded == 1, "excluded code is not counted as missing executable code");
        check(comments.code.contains("// run(\"Invert\");"), "all lines of excluded code are comments");
        check(comments.code.contains("// run(\"Delete\");"), "notes cannot inject executable lines");
        check(comments.code.contains("[excluded]"), "exclusions remain explicit in exported recipe");
        System.out.println(pass + " macro unit checks passed");
    }
}
