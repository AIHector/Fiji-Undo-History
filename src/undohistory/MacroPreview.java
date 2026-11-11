package undohistory;

import ij.IJ;
import ij.io.SaveDialog;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Preview and save only: exporting never executes code against the active image. */
final class MacroPreview {
    static void show(MacroExport export) {
        JFrame frame = new JFrame("Macro from history — review before running");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        JTextArea code = new JTextArea(export.code, 24, 78);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        code.setEditable(false);
        JLabel info = new JLabel(export.missing == 0
                ? "Recorded code. " + export.excluded + " excluded step(s) are comments. Review dependencies before use."
                : export.missing + " step(s) lack recorded code. This draft stops before running until completed.");
        info.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JButton save = new JButton(export.missing == 0 ? "Save macro…" : "Save draft…");
        save.addActionListener(e -> {
            SaveDialog dialog = new SaveDialog("Save macro from history", "History_macro", ".ijm");
            if (dialog.getFileName() == null) return;
            File file = new File(dialog.getDirectory(), dialog.getFileName());
            if (file.exists() && JOptionPane.showConfirmDialog(frame, "Replace " + file.getName() + "?",
                    "Save macro", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try { Files.write(file.toPath(), export.code.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ex) { IJ.error("Undo History", "Could not save macro: " + ex.getMessage()); return; }
            info.setText("Saved " + file.getAbsolutePath() + ". Open in Fiji's macro editor to review or edit.");
        });
        JButton close = new JButton("Close"); close.addActionListener(e -> frame.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(save); buttons.add(close);
        frame.add(info, BorderLayout.NORTH); frame.add(new JScrollPane(code), BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH); frame.pack(); frame.setLocationRelativeTo(null); frame.setVisible(true);
    }
}
