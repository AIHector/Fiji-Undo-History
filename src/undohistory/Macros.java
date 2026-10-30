package undohistory;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.io.SaveDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Create Macro: the steps of a history (the selected ones, or all up to the current one) as an .ijm file that runs on
 * the active image. Where the file goes is an option; unless it is an export, the macro also gets a button on Side
 * Toolbar's macro bar (when Side Toolbar is installed and shows one) and opens in the editor.
 */
final class Macros {

	private Macros() { }

	static final String[] SAVE_MODES = {
		"Save in the macro folder",
		"Ask, starting in the macro folder",
		"Ask, starting in the image's folder",
		"Ask, starting in the last folder used",
		"Export: ask, starting in the image's folder (no button)",
	};
	static final int FIXED = 0, ASK_MACRO = 1, ASK_IMAGE = 2, ASK_LAST = 3, EXPORT = 4;

	static int saveMode() { return Math.max(0, Math.min(4, (int) Prefs.get(Engine.PREF + "macroSave", FIXED))); }

	static String folderPref() { return Prefs.get(Engine.PREF + "macroFolder", ""); }

	/** The macro folder: chosen in the Options, or Fiji's macros/Undo History. */
	static File folder() {
		String f = folderPref();
		if (!f.isEmpty()) return new File(f);
		String macros = IJ.getDir("macros");
		return new File(macros != null ? macros : System.getProperty("user.home"), "Undo History");
	}

	static boolean openInEditor() { return Prefs.get(Engine.PREF + "macroOpenEditor", true); }

	/** Makes the macro and saves it as the Options say. Returns the file, or null (cancelled, or no history). */
	static File create(int imageID, int[] steps) {
		Engine e = Engine.get();
		String code = e.macroOf(imageID, steps);
		ImagePlus imp = WindowManager.getImage(imageID);
		if (code == null || imp == null) {
			IJ.showStatus("Undo History: this image has no history");
			return null;
		}
		int mode = saveMode();
		String name = fileName(imp.getTitle());
		File file;
		if (mode == FIXED) {
			File dir = folder();
			dir.mkdirs();
			file = unique(new File(dir, name));
		} else {
			File start = mode == ASK_IMAGE || mode == EXPORT ? imageFolder(imp)
					: mode == ASK_LAST ? lastFolder() : folder();
			if (start == null) start = folder();
			start.mkdirs();
			SaveDialog sd = new SaveDialog(mode == EXPORT ? "Export Macro" : "Save Macro", start.getPath(), name, ".ijm");
			if (sd.getFileName() == null) return null;
			file = new File(sd.getDirectory(), sd.getFileName());
		}
		try {
			write(file, code);
		} catch (IOException x) {
			IJ.error("Undo History", "Could not save the macro:\n" + file + "\n" + x.getMessage());
			return null;
		}
		Prefs.set(Engine.PREF + "macroLastFolder", file.getParent());
		if (mode == EXPORT) {
			IJ.showStatus("Undo History: macro exported to " + file);
			return file;
		}
		boolean button = addToSideToolbar(file);
		IJ.showStatus("Undo History: macro saved to " + file + (button ? ", with a button on the macro bar" : ""));
		if (openInEditor()) IJ.open(file.getPath());
		return file;
	}

	/** "blobs.tif" → "blobs_2026-10-30_1402.ijm" */
	static String fileName(String title) {
		String base = title.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
		if (base.isEmpty()) base = "macro";
		return base.replace(' ', '_') + "_" + new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm").format(new java.util.Date()) + ".ijm";
	}

	private static File unique(File f) {
		if (!f.exists()) return f;
		String n = f.getName().replaceFirst("\\.ijm$", "");
		for (int i = 2; ; i++) {
			File g = new File(f.getParentFile(), n + "_" + i + ".ijm");
			if (!g.exists()) return g;
		}
	}

	private static File imageFolder(ImagePlus imp) {
		FileInfo fi = imp.getOriginalFileInfo();
		return fi != null && fi.directory != null && !fi.directory.isEmpty() ? new File(fi.directory) : null;
	}

	private static File lastFolder() {
		String f = Prefs.get(Engine.PREF + "macroLastFolder", "");
		return f.isEmpty() ? null : new File(f);
	}

	private static void write(File f, String text) throws IOException {
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
			w.write(text);
		}
	}

	/** Asks Side Toolbar, when installed, to add a button for the macro. Returns true if it did. */
	static boolean addToSideToolbar(File f) {
		try {
			Class<?> c = Class.forName("sidetoolbar.Side_Toolbar", true, IJ.getClassLoader());
			Object r = c.getMethod("addMacroButton", String.class).invoke(null, f.getPath());
			return "added".equals(r);
		} catch (Throwable t) {
			return false; // not installed, or a version without macro buttons from other plugins
		}
	}
}
