package undohistory;

import ij.*;
import ij.process.ByteProcessor;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Direct component events in a separate test ImageJ: selection, restore and export are distinct actions. */
public final class UsabilityTest {
    static int pass;
    static Engine engine;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); pass++; }
    static void waitFor(BooleanSupplier test) throws Exception {
        for (int i = 0; i < 100; i++) { if (test.getAsBoolean()) return; Thread.sleep(50); }
        throw new AssertionError("timed out");
    }
    static Object field(Object target, String name) throws Exception {
        Field f = HistoryWindow.class.getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    public static void main(String[] args) throws Exception {
        try {
            Prefs.set(Engine.PREF + "testMemoryMB", 64); Prefs.set(Engine.PREF + "useDisk", false);
            new ImageJ(ImageJ.NO_SHOW); new Undo_History().run("start"); engine = Engine.get();
            ByteProcessor bp = new ByteProcessor(100, 100);
            for (int i = 0; i < 10000; i++) bp.set(i, (i * 17) % 256);
            ImagePlus imp = new ImagePlus("History selection and macro test", bp); imp.show();
            waitFor(() -> engine.view(imp.getID()) != null);
            IJ.run(imp, "Invert", ""); IJ.run(imp, "Gaussian Blur...", "sigma=2.75");
            waitFor(() -> engine.view(imp.getID()).rows.size() == 3);
            EventQueue.invokeAndWait(HistoryWindow::showWindow);
            HistoryWindow history = null;
            for (Window w : Window.getWindows()) if (w instanceof HistoryWindow) history = (HistoryWindow) w;
            final HistoryWindow panel = history;
            JList<?> list = (JList<?>) field(panel, "list");
            JButton go = (JButton) field(panel, "go"), macro = (JButton) field(panel, "macro");
            JButton copy = (JButton) field(panel, "copy"), delete = (JButton) field(panel, "delete");
            byte[] current = ((byte[]) imp.getProcessor().getPixels()).clone();
            EventQueue.invokeAndWait(() -> list.setSelectedIndex(1));
            Thread.sleep(500);
            check(engine.cursorOf(imp.getID()) == 2 && Arrays.equals(current, (byte[]) imp.getProcessor().getPixels()),
                    "selecting a row does not restore or alter image");
            EventQueue.invokeAndWait(go::doClick);
            waitFor(() -> engine.cursorOf(imp.getID()) == 1);
            Thread.sleep(500);
            check(engine.cursorOf(imp.getID()) == 1, "Go to restores selected row");
            EventQueue.invokeAndWait(() -> list.setSelectionInterval(1, 2));
            check(macro.isEnabled() && !copy.isEnabled() && delete.isEnabled(), "multi-selection enables batch delete and export while disabling single-image actions");
            Thread.sleep(500);
            check(list.getSelectedIndices().length == 2, "selection survives timer refresh");
            EventQueue.invokeAndWait(macro::doClick);
            final JFrame[] preview = new JFrame[1];
            waitFor(() -> {
                for (Window w : Window.getWindows()) if (w instanceof JFrame && String.valueOf(((JFrame) w).getTitle()).startsWith("Macro from history")) preview[0] = (JFrame) w;
                return preview[0] != null;
            });
            check(engine.cursorOf(imp.getID()) == 1, "macro preview does not execute or restore steps");
            EventQueue.invokeAndWait(() -> {
                BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
                try { ImageIO.write(image, "png", new File(args[0], "history-candidate.png")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
                BufferedImage previewImage = new BufferedImage(preview[0].getWidth(), preview[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                g = previewImage.createGraphics(); preview[0].paint(g); g.dispose();
                try { ImageIO.write(previewImage, "png", new File(args[0], "macro-preview.png")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
                preview[0].dispose();
            });
            imp.changes = false; imp.close(); Thread.sleep(700);
            check(!delete.isEnabled() && !macro.isEnabled(), "no-image state disables delete and export");
            System.out.println(pass + " interface behavior checks passed");
            System.exit(0);
        } catch (Throwable ex) { ex.printStackTrace(); System.exit(1); }
    }
}
