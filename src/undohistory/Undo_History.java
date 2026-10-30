package undohistory;

import ij.IJ;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

/**
 * Entry point for all menu commands (see plugins.config). The argument selects the action:
 * start, stop, toggle, window, redo, back, keep, reopen, clear, options.
 */
public class Undo_History implements PlugIn {

	public void run(String arg) {
		if (arg == null) arg = "";
		switch (arg) {
			case "start": startRecording(); return;
			case "stop": forget(); return;
			case "pause": Engine.get().pause(); IJ.showStatus("Undo History: paused"); return;
			case "toggle":
				if (Engine.isRunning()) stopRecording(); else startRecording();
				return;
			case "options": showOptions(); return;
			case "redo": case "back": case "keep": case "reopen": case "clear":
				if (!Engine.isRunning()) {
					IJ.beep();
					IJ.showStatus("Undo History is " + (Engine.isPaused() ? "paused" : "stopped")
							+ ": Edit > Start/Stop Undo History " + (Engine.isPaused() ? "resumes" : "starts") + " it");
					return;
				}
				break;
			default:
				HistoryWindow.showWindow();
				return;
		}
		Engine engine = Engine.get();
		switch (arg) {
			case "redo": engine.redoActive(); break;
			case "back": engine.undoActive(); break;
			case "keep": {
				int id = currentID();
				Engine.View v = id == 0 ? null : engine.view(id);
				if (v != null)
					for (int i = 0; i < v.rows.size(); i++)
						if (v.rows.get(i).current) engine.togglePin(id, i);
				break;
			}
			case "reopen":
				if (!engine.reopenLast()) IJ.showStatus("Undo History: no closed image to reopen");
				break;
			case "clear":
				if (currentID() != 0) engine.clear(currentID());
				break;
		}
	}

	/** Called by the startup macro (macros/AutoRun): starts recording and shows the window, as set in the Options. */
	public static String autostart() {
		if (Engine.startAtLaunch()) Engine.get().start();
		if (Engine.showAtStartup()) HistoryWindow.showWindow();
		return "";
	}

	static void startRecording() {
		boolean resuming = Engine.isPaused();
		Engine.get().start();
		IJ.showStatus("Undo History: " + (resuming ? "resumed" : "recording"));
	}

	/**
	 * Asks whether to pause (keep the history) or stop and forget it, when there is something to keep. The images
	 * stay as they are either way. Inside a macro, stops without asking.
	 */
	static void stopRecording() {
		Engine e = Engine.get();
		int steps = e.recordedSteps();
		if (steps == 0 || IJ.macroRunning()) { forget(); return; }
		GenericDialog gd = new GenericDialog("Undo History");
		gd.addMessage("Pause or stop Undo History?\n \n"
				+ "Your images stay exactly as they are now: only the history is affected.\n \n"
				+ "Pause: the history (" + steps + " step" + (steps == 1 ? "" : "s") + ") and its memory are kept. When you resume,\n"
				+ "what changed in the meantime becomes one step, \"Changes while paused\".\n \n"
				+ "Stop: the history is forgotten, and its memory and disk space are freed.\n \n"
				+ "Either way, Edit > Undo is ImageJ's single undo until you resume or start again.");
		gd.enableYesNoCancel("Pause", "Stop and forget");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		if (gd.wasOKed()) {
			e.pause();
			IJ.showStatus("Undo History: paused");
		} else forget();
	}

	static void forget() {
		Engine.get().stop();
		IJ.showStatus("Undo History: stopped");
	}

	private static int currentID() {
		return WindowManager.getCurrentImage() == null ? 0 : WindowManager.getCurrentImage().getID();
	}

	static void showOptions() {
		Engine e = Engine.get();
		long maxMB = IJ.maxMemory() >> 20;
		GenericDialog gd = new GenericDialog("Undo History Options");
		gd.addNumericField("Memory for history:", e.memoryPercent(), 0, 4,
				"% of Fiji's memory (" + maxMB + " MB → " + e.memoryMB() + " MB)");
		gd.addCheckbox("When memory is full, keep older steps on disk", e.useDisk());
		gd.addDirectoryField("Disk folder:", e.diskFolder().getPath(), 28);
		gd.addNumericField("Disk space for history:", e.diskPercent(), 0, 4,
				"% of the free space on that disk (now " + IJ.d2s(e.diskBudgetBytes() / 1073741824.0, 1) + " GB)");
		gd.addCheckbox("Redo also with " + (IJ.isMacOSX() ? "⌘⇧Z" : "Ctrl+Shift+Z")
				+ " (the Channels Tool then opens from Image ▸ Color only)", e.redoShiftZ());
		gd.addCheckbox("Start a new history when an image is saved", e.trimOnSave());
		gd.addCheckbox("Record from the moment Fiji starts", Engine.startAtLaunch());
		gd.addCheckbox("Show the Undo History window when Fiji starts", Engine.showAtStartup());
		gd.addChoice("Macro button:", Macros.SAVE_MODES, Macros.SAVE_MODES[Macros.saveMode()]);
		gd.addDirectoryField("Macro folder:", Macros.folder().getPath(), 28);
		gd.addCheckbox("Open a new macro in the editor", Macros.openInEditor());
		gd.addMessage("Recent steps stay in memory. With the disk on, older ones move to the disk folder\n"
				+ "(use a fast SSD), and " + IJ.d2s(e.minFreeDiskGB(), 0) + " GB always stay free on that disk.\n"
				+ "When there is no more room, the oldest steps are dropped. The opened state, saved states\n"
				+ "and kept steps never are. 20% of Fiji's memory always stays free for your work.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		Prefs.set(Engine.PREF + "memoryPercent", Math.max(1, Math.min(90, (int) gd.getNextNumber())));
		Prefs.set(Engine.PREF + "useDisk", gd.getNextBoolean());
		String folder = gd.getNextString().trim();
		String defaultFolder = new java.io.File(System.getProperty("java.io.tmpdir"), "FijiUndoHistory").getPath();
		Prefs.set(Engine.PREF + "diskFolder", folder.equals(defaultFolder) ? "" : folder);
		Prefs.set(Engine.PREF + "diskPercent", Math.max(1, Math.min(90, (int) gd.getNextNumber())));
		Prefs.set(Engine.PREF + "redoShiftZ", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "trimOnSave", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "startAtLaunch", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "showAtStartup", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "macroSave", gd.getNextChoiceIndex());
		String macroFolder = gd.getNextString().trim();
		Prefs.set(Engine.PREF + "macroFolder", macroFolder.equals(Macros.folder().getPath()) && Macros.folderPref().isEmpty() ? "" : macroFolder);
		Prefs.set(Engine.PREF + "macroOpenEditor", gd.getNextBoolean());
		Prefs.savePreferences();
		e.settingsChanged();
	}
}
