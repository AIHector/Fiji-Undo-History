package undohistory;

import java.util.List;

/** A reviewable ImageJ macro, with explicit stops for steps that cannot be reconstructed. */
final class MacroExport {
    final String code;
    final int missing;
    final int excluded;

    MacroExport(List<Engine.Row> rows, boolean gaps) {
        int count = 0;
        for (Engine.Row row : rows) if (row.included && row.macroCode == null) count++;
        missing = count;
        int skipped = 0; for (Engine.Row row : rows) if (!row.included) skipped++; excluded = skipped;
        StringBuilder text = new StringBuilder("// Created from selected Undo History steps, in history order.\n"
                + "// Run on a suitable input image. Review selections, channels, paths and dependencies first.\n"
                + "// Captured code preserves recorded settings; it does not prove identical results on other images.\n");
        if (gaps || excluded > 0) text.append("// Some intervening steps were omitted or removed. Check dependencies on those steps.\n");
        if (missing > 0) text.append("exit(\"Draft only: complete the ").append(missing)
                .append(" missing step(s), then remove this stop.\");\n");
        text.append('\n');
        for (Engine.Row row : rows) {
            text.append("// ").append(row.included ? "" : "[excluded] ").append(oneLine(row.label)).append('\n');
            if (!row.note.isEmpty()) comment(text, "Note: " + row.note);
            if (!row.included) {
                if (row.macroCode != null) comment(text, row.macroCode);
                else text.append("// No recorded code for this excluded step.\n");
            } else if (row.macroCode != null) text.append(row.macroCode);
            else text.append("// TODO: not recorded as macro code. Repeat this operation with Record enabled.\n");
            text.append('\n');
        }
        code = text.toString();
    }

    static void comment(StringBuilder text, String code) {
        for (String line : code.split("\\r?\\n", -1)) text.append("// ").append(line.replace('\r', ' ')).append('\n');
    }

    static String oneLine(String text) { return text.replace('\r', ' ').replace('\n', ' '); }
}
