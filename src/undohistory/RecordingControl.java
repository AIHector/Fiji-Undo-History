package undohistory;

import ij.IJ;
import ij.Prefs;
import ij.plugin.frame.Recorder;
import java.awt.EventQueue;

/** The checkbox controls capture into history; an independently opened Recorder retains its own lifecycle. */
final class RecordingControl {
    private static final String PREF = Engine.PREF + "recordMacro";
    private static Recorder owned;
    static boolean enabled() { return Prefs.get(PREF, true); }

    static void start() {
        if (!enabled()) return;
        if (EventQueue.isDispatchThread()) setEnabled(true);
        else try { EventQueue.invokeAndWait(() -> setEnabled(true)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Macro recorder startup interrupted",ex); }
        catch (java.lang.reflect.InvocationTargetException ex) { throw new IllegalStateException("Could not start macro recorder",ex.getCause()); }
    }

    /** Must run on the event thread so native Recorder controls are created safely. */
    static boolean setEnabled(boolean enabled) {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("Recording controls require the event thread");
        if (enabled && Recorder.getInstance() != null && Recorder.scriptMode()) {
            Prefs.set(PREF, false); MacroCapture.invalidate();
            IJ.showMessage("Record macro", "Choose Macro in the Recorder's Record menu before enabling history macro recording.");
            return false;
        }
        Prefs.set(PREF, enabled); MacroCapture.invalidate();
        if (enabled && Recorder.getInstance() == null) {
            String previous = Prefs.get("recorder.mode", "Macro");
            Prefs.set("recorder.mode", "Macro");
            try { owned = new Recorder(false); }
            finally { Prefs.set("recorder.mode", previous); }
        } else if (!enabled && owned != null && owned == Recorder.getInstance() && !owned.isVisible()) {
            owned.close(); owned = null;
        }
        MacroCapture.resetFeed();
        Prefs.savePreferences();
        return enabled;
    }

    static void showRecorder() {
        if (!setEnabled(true)) return;
        Recorder.getInstance().setVisible(true); Recorder.getInstance().toFront();
    }

    static void sync() {
        // Closing the Recorder or changing its language is an explicit change; do not silently reopen it.
        if (enabled() && (Recorder.getInstance() == null || Recorder.scriptMode())) {
            Prefs.set(PREF, false); MacroCapture.invalidate();
        }
    }
}
