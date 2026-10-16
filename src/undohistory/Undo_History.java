package undohistory;

import ij.IJ;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

/**
 * Entry point for all menu commands (see plugins.config). The argument selects the action:
 * start, window, redo, back, keep, clear, options.
 */
public class Undo_History implements PlugIn {

	public void run(String arg) {
		Engine engine = Engine.get();
		engine.start();
		if (arg == null) arg = "";
		switch (arg) {
			case "start":
				if (Prefs.get(Engine.PREF + "showAtStartup", false)) HistoryWindow.showWindow();
				break;
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
			case "clear":
				if (currentID() != 0) engine.clear(currentID());
				break;
			case "options": showOptions(); break;
			default: HistoryWindow.showWindow();
		}
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
		gd.addNumericField("Disk space for history:", e.diskGBPref(), 1, 6,
				"GB (0 = automatic: now " + (e.diskBudgetBytes() >> 30) + " GB)");
		gd.addNumericField("Always leave free on that disk:", e.minFreeDiskGB(), 1, 6, "GB");
		gd.addNumericField("Maximum steps per image:", e.maxSteps(), 0, 6, "");
		gd.addCheckbox("Edit ▸ Undo (" + (IJ.isMacOSX() ? "⌘Z" : "Ctrl+Z") + ") steps back through the history", e.replaceUndo());
		gd.addCheckbox("Redo with " + (IJ.isMacOSX() ? "⌘⌥Z" : "Ctrl+Alt+Z"), e.redoKey());
		gd.addCheckbox("Start a new history when an image is saved", e.trimOnSave());
		gd.addCheckbox("Show the Undo History window when Fiji starts", Prefs.get(Engine.PREF + "showAtStartup", false));
		gd.addMessage("Recent steps stay in memory; older ones move to the disk folder (use a fast SSD).\n"
				+ "When both are full, the oldest steps are dropped. The opened state, saved states\n"
				+ "and kept steps are never dropped. 20% of Fiji's memory always stays free for your work.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		Prefs.set(Engine.PREF + "memoryPercent", Math.max(1, Math.min(90, (int) gd.getNextNumber())));
		Prefs.set(Engine.PREF + "useDisk", gd.getNextBoolean());
		String folder = gd.getNextString().trim();
		String defaultFolder = new java.io.File(System.getProperty("java.io.tmpdir"), "FijiUndoHistory").getPath();
		Prefs.set(Engine.PREF + "diskFolder", folder.equals(defaultFolder) ? "" : folder);
		Prefs.set(Engine.PREF + "diskGB", Math.max(0, gd.getNextNumber()));
		Prefs.set(Engine.PREF + "minFreeDiskGB", Math.max(0, gd.getNextNumber()));
		Prefs.set(Engine.PREF + "maxSteps", Math.max(1, (int) gd.getNextNumber()));
		Prefs.set(Engine.PREF + "replaceUndo", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "redoKey", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "trimOnSave", gd.getNextBoolean());
		Prefs.set(Engine.PREF + "showAtStartup", gd.getNextBoolean());
		Prefs.savePreferences();
		e.settingsChanged();
	}
}
