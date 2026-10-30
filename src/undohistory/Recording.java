package undohistory;

import ij.WindowManager;
import ij.plugin.frame.Recorder;

import java.awt.EventQueue;

/**
 * Keeps ImageJ's macro Recorder running out of sight, so that each step can carry the macro code that repeats it
 * (the command, with the values typed in its dialog). ImageJ's dialogs report their values only while a Recorder is
 * running. When the user opens Plugins ▸ Macros ▸ Record..., the hidden one closes and theirs is read instead.
 */
final class Recording {

	private Recording() { }

	private static Recorder hidden;
	private static int mark;

	/** Starts the hidden Recorder unless one (hidden or the user's) is running. */
	static void ensure() {
		if (Recorder.getInstance() != null) return;
		Runnable r = () -> {
			if (Recorder.getInstance() != null) return;
			hidden = new Recorder(false);
			WindowManager.removeWindow(hidden); // not in the Window menu
			mark = 0;
		};
		if (EventQueue.isDispatchThread()) r.run();
		else try { EventQueue.invokeAndWait(r); } catch (Exception ignored) { }
	}

	/** Closes the hidden Recorder (never the user's). */
	static void stop() {
		Recorder h = hidden;
		hidden = null;
		if (h != null && Recorder.getInstance() == h) EventQueue.invokeLater(h::close);
	}

	/** Plugins ▸ Macros ▸ Record... is about to open the user's Recorder: the hidden one makes way for it. */
	static void handOver() {
		Recorder h = hidden;
		hidden = null;
		if (h == null || Recorder.getInstance() != h) return;
		if (EventQueue.isDispatchThread()) h.close();
		else try { EventQueue.invokeAndWait(h::close); } catch (Exception ignored) { }
		mark = 0;
	}

	/** The code recorded since the last call (ours to keep for a step), without lines that do not repeat it. */
	static String take() {
		Recorder r = Recorder.getInstance();
		if (r == null) return "";
		String all = r.getText();
		if (all.length() < mark) mark = 0; // the user cleared their Recorder
		String text = all.substring(mark);
		mark = all.length();
		return clean(text);
	}

	/** Forgets what was recorded so far (code that belongs to no step, e.g. while undoing). */
	static void skip() { take(); }

	/** Lines that do not belong in a macro that runs on the active image: they open, save, close or switch images. */
	private static final String[] DROP = {"setTool(", "selectWindow(", "selectImage(", "open(", "newImage(", "saveAs(",
		"save(", "close(", "run(\"Close\")", "run(\"Close All\")", "run(\"Undo\")"};

	/** Drops tool changes, window switches, opening, saving and closing, and Undo History's own commands. */
	static String clean(String text) {
		StringBuilder b = new StringBuilder();
		lines:
		for (String line : text.split("\n")) {
			String l = line.trim();
			if (l.isEmpty()) continue;
			for (String d : DROP) if (l.startsWith(d)) continue lines;
			String cmd = commandOf(l);
			if (cmd != null && Engine.isOwnCommand(cmd)) continue;
			b.append(l).append('\n');
		}
		return b.toString();
	}

	/** The command of a {@code run("...")} line, or null. */
	static String commandOf(String line) {
		if (!line.startsWith("run(\"")) return null;
		int end = line.indexOf('"', 5);
		return end < 0 ? null : line.substring(5, end);
	}

	/** {@code run("cmd", "options");} as a macro line. */
	static String runLine(String cmd, String options) {
		String o = options == null ? "" : options.trim();
		return "run(\"" + esc(cmd) + "\"" + (o.isEmpty() ? "" : ", \"" + esc(o) + "\"") + ");\n";
	}

	static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
