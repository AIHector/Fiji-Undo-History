package undohistory;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.OvalRoi;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * One rung of the size ladder, with the plugin's default (percentage) settings.
 * A 16-bit stack of 2048x2040 planes; three whole-stack/one-plane edits; undo all; redo all.
 * Args: sizeMB [diskFolder]
 */
public class Bench {
	static Engine engine;

	public static void main(String[] a) throws Exception {
		int mb = Integer.parseInt(a[0]);
		if (a.length > 1) Prefs.set(Engine.PREF + "diskFolder", a[1]);
		Prefs.set(Engine.PREF + "useDisk", true);
		Prefs.set(Engine.PREF + "confirmDiscard", false); // no dialog in the benchmark
		if (System.getProperty("pct") != null) Prefs.set(Engine.PREF + "memoryPercent", Integer.parseInt(System.getProperty("pct")));
		new ImageJ(ImageJ.NO_SHOW);
		new Undo_History().run("start");
		engine = Engine.get();
		int W = 2048, H = 2040, planes = (int) ((long) mb * 1048576 / (W * H * 2));
		ImageStack st = new ImageStack(W, H);
		for (int p = 0; p < planes; p++) {
			short[] px = new short[W * H];
			for (int k = 0; k < px.length; k += 5) px[k] = (short) (k * 31 + p);
			st.addSlice("p" + p, new ShortProcessor(W, H, px, null));
		}
		ImagePlus imp = new ImagePlus("bench", st);
		System.out.printf("== %d MB image (%d planes), heap max %d MB, history memory %d MB, disk budget %d MB, disk free %d MB%n",
				mb, planes, IJ.maxMemory() >> 20, engine.memoryMB(), engine.diskBudgetBytes() >> 20,
				engine.diskFolder().getParentFile().getUsableSpace() >> 20);
		long t = System.nanoTime();
		imp.show();
		waitFor(() -> engine.view(imp.getID()) != null, 300000);
		report("open + baseline", t, imp);
		if (engine.view(imp.getID()).untracked != null) { System.exit(0); }

		java.util.List<short[]> recorded = new java.util.ArrayList<>(); // pixels of plane mid at each recorded step
		recorded.add(((short[]) imp.getStack().getPixels(planes / 2)).clone());
		String[] ops = {"Add...|value=7 stack", "Multiply...|value=1.5 stack", "PAINT"};
		for (String op : ops) {
			int before = rows(imp);
			t = System.nanoTime();
			if (op.equals("PAINT")) {
				imp.setSlice(planes / 2);
				ImageProcessor ip = imp.getProcessor();
				ip.setColor(65535);
				ip.fill(new OvalRoi(100, 100, 400, 400));
				imp.updateAndDraw();
				Thread.sleep(400);
			} else {
				String[] c = op.split("\\|");
				IJ.run(imp, c[0], c[1]);
			}
			waitFor(() -> rows(imp) > before || engine.view(imp.getID()).problem != null, 300000);
			report((op.equals("PAINT") ? "paint 1 plane" : "whole-stack " + op.substring(0, op.indexOf('.'))) + " + record", t, imp);
			if (rows(imp) > before) recorded.add(((short[]) imp.getStack().getPixels(planes / 2)).clone());
		}
		int top = rows(imp) - 1;
		boolean ok = true;
		Engine.traceUpdates = System.getProperty("trace") != null;
		while (engine.cursorOf(imp.getID()) > 0 || engine.view(imp.getID()).problem != null) {
			t = System.nanoTime();
			int nc0 = engine.noChangeCaptures;
			engine.undoActive();
			int c = engine.cursorOf(imp.getID());
			Thread.sleep(1500);
			System.out.println("    (full re-checks finding no change since this undo: " + (engine.noChangeCaptures - nc0) + ")");
			boolean eq = Arrays.equals(recorded.get(c), (short[]) imp.getStack().getPixels(planes / 2));
			ok &= eq;
			report("undo -> step " + c + (eq ? " (exact)" : " (WRONG PIXELS)"), t, imp);
		}
		t = System.nanoTime();
		engine.goTo(imp.getID(), top);
		boolean eq = Arrays.equals(recorded.get(top), (short[]) imp.getStack().getPixels(planes / 2));
		ok &= eq;
		report("jump to last step " + top + (eq ? " (exact)" : " (WRONG PIXELS)"), t, imp);
		System.out.println("RESULT " + mb + " MB: " + (ok ? "all restores exact" : "RESTORE ERRORS") + ", dropped=" + engine.view(imp.getID()).dropped);
		System.exit(0);
	}

	static void report(String what, long t0, ImagePlus imp) {
		Engine.View v = engine.view(imp.getID());
		long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
		Store st = engine.store;
		String io = String.format(" | disk I/O so far: wrote %d MB in %.1f s, read %d MB in %.1f s", st.bytesWritten >> 20,
				st.writeNanos / 1e9, st.bytesRead >> 20, st.readNanos / 1e9);
		System.out.printf("  %-34s %7.0f ms | steps %d | history RAM %5d MB, disk %5d MB | heap used %5d MB%s%s%s%n", what,
				(System.nanoTime() - t0) / 1e6, v.rows.size() - 1, engine.usedBytes() >> 20, engine.diskBytes() >> 20, used, io,
				v.untracked != null ? " | NOT TRACKED: " + v.untracked : "", v.problem != null ? " | " + v.problem : "");
	}

	static int cursor(ImagePlus imp) {
		Engine.View v = engine.view(imp.getID());
		for (int i = 0; i < v.rows.size(); i++) if (v.rows.get(i).current) return i;
		return -1;
	}

	static int rows(ImagePlus imp) { Engine.View v = engine.view(imp.getID()); return v == null ? -1 : v.rows.size(); }
	static void waitFor(BooleanSupplier c, long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
	}
}
