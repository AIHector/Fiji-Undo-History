package undohistory;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.ImageCanvas;
import ij.gui.OvalRoi;
import ij.gui.Toolbar;
import ij.plugin.tool.BrushTool;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * End-to-end test in a real (non-headless) ImageJ: menu commands, macro commands,
 * paintbrush strokes, Edit ▸ Undo interception, redo, branch truncation, stacks,
 * type conversion, memory budget, save marking, composite images, and the window.
 */
public class GuiTest {

	static int pass, fail;
	static Engine engine;

	public static void main(String[] args) throws Exception {
		String outDir = args.length > 0 ? args[0] : ".";
		Prefs.set(Engine.PREF + "testMemoryMB", 512);
		Prefs.set(Engine.PREF + "useDisk", false);
		Prefs.set(Engine.PREF + "diskFolder", new File(outDir, "spill").getPath());
		Prefs.set(Engine.PREF + "testDiskMB", 300);
		Prefs.set(Engine.PREF + "maxSteps", 100);
		Prefs.set(Engine.PREF + "replaceUndo", true);
		Prefs.set(Engine.PREF + "trimOnSave", false);
		new ImageJ(ImageJ.NO_SHOW);
		new Undo_History().run("start");
		engine = Engine.get();

		// 1. Opened baseline
		ImagePlus imp = new ImagePlus("blobs-test.tif", noise(256, 256, 1));
		imp.show();
		waitFor(() -> imp.getCanvas() != null && view(imp) != null, 5000);
		check("opened image has a 1-state history", rows(imp) == 1 && view(imp).rows.get(0).label.equals("Opened"));

		// 2. Menu commands (async Executer threads), one step each
		byte[] s0 = pix(imp);
		menu("Invert", imp, 2);
		byte[] s1 = pix(imp);
		menu("Smooth", imp, 3);
		byte[] s2 = pix(imp);
		menu("Find Edges", imp, 4);
		byte[] s3 = pix(imp);
		check("3 menu commands -> 4 states", rows(imp) == 4);
		check("labels are command names", labels(imp).equals("[Opened, Invert, Smooth, Find Edges]"));

		// 3. Edit > Undo goes back through the history, several levels
		IJ.doCommand("Undo");
		waitFor(() -> cursor(imp) == 2, 3000);
		check("Undo #1 restores Smooth state", Arrays.equals(pix(imp), s2));
		IJ.doCommand("Undo");
		waitFor(() -> cursor(imp) == 1, 3000);
		check("Undo #2 restores Invert state", Arrays.equals(pix(imp), s1));
		IJ.doCommand("Undo");
		waitFor(() -> cursor(imp) == 0, 3000);
		check("Undo #3 restores opened state", Arrays.equals(pix(imp), s0));
		check("back at opened state -> image marked unchanged", !imp.changes);

		// 4. Redo
		engine.redoActive();
		engine.redoActive();
		check("Redo x2 restores Smooth state", Arrays.equals(pix(imp), s2) && cursor(imp) == 2);
		check("redo steps kept while not changed", rows(imp) == 4);

		// 5. A new change after undo drops the redo branch
		menu("Sharpen", imp, -1);
		waitFor(() -> labels(imp).endsWith("Sharpen]"), 5000);
		System.out.println("      " + labels(imp) + " cursor=" + cursor(imp));
		check("new change truncates redo branch", labels(imp).equals("[Opened, Invert, Smooth, Sharpen]") && cursor(imp) == 3);

		// 6. Jump directly to any state
		engine.goTo(imp.getID(), 1);
		check("goTo(1) restores Invert state", Arrays.equals(pix(imp), s1));
		engine.goTo(imp.getID(), 3);

		// 7. Macro-style commands (IJ.run in one thread): one step each
		int before = rows(imp);
		Thread macro = new Thread(() -> {
			IJ.run(imp, "Gaussian Blur...", "sigma=2");
			IJ.run(imp, "Add Specified Noise...", "standard=10");
			IJ.run(imp, "Median...", "radius=1");
		}, "macro-thread");
		macro.start();
		macro.join();
		waitFor(() -> rows(imp) == before + 3, 4000);
		System.out.println("      " + labels(imp) + " cursor=" + cursor(imp));
		check("3 IJ.run commands -> 3 steps", rows(imp) == before + 3);
		check("macro step labels", labels(imp).endsWith("Gaussian Blur, Add Specified Noise, Median]"));

		// 8. Paintbrush stroke (synthetic mouse events on the canvas)
		BrushTool brush = new BrushTool();
		EventQueue.invokeAndWait(() -> Toolbar.addPlugInTool(brush));
		IJ.setForegroundColor(255, 255, 255);
		int r0 = rows(imp);
		stroke(imp.getCanvas(), 40, 40, 120, 90);
		waitFor(() -> rows(imp) == r0 + 1, 4000);
		check("brush stroke -> 1 step", rows(imp) == r0 + 1);
		check("brush step labelled by tool (" + last(imp) + ")", last(imp).toLowerCase().contains("brush"));
		byte[] painted = pix(imp);
		IJ.doCommand("Undo");
		waitFor(() -> cursor(imp) == r0 - 1, 3000);
		check("Undo removes the brush stroke", !Arrays.equals(pix(imp), painted) && cursor(imp) == r0 - 1);
		// two strokes then a command: each stroke must be its own step, committed before the command
		int r1 = rows(imp) - 1; // the undone stroke will be truncated
		stroke(imp.getCanvas(), 30, 200, 200, 210);
		stroke(imp.getCanvas(), 200, 30, 210, 200); // 50 ms later: still its own step
		menu("Invert", imp, r1 + 3);
		System.out.println("      r1=" + r1 + " " + labels(imp) + " cursor=" + cursor(imp));
		check("quick stroke, stroke, command -> 3 steps", labels(imp).endsWith("Median, Paintbrush Tool, Paintbrush Tool, Invert]"));

		// 9. Stack: unchanged planes are shared between states
		ImagePlus stk = new ImagePlus("stack-test", noiseStack(256, 256, 50));
		stk.show();
		waitFor(() -> view(stk) != null, 5000);
		long used0 = engine.usedBytes();
		stk.setSlice(10);
		byte[] plane10 = ((byte[]) stk.getStack().getPixels(10)).clone();
		ImageProcessor ip = stk.getProcessor();
		ip.setColor(255);
		ip.fill(new OvalRoi(50, 50, 60, 60));
		stk.updateAndDraw();
		waitFor(() -> rows(stk) == 2, 4000);
		long delta = engine.usedBytes() - used0;
		check("stack edit on 1 of 50 planes costs 1 plane (" + delta + " bytes)", rows(stk) == 2 && delta == 256 * 256);
		engine.undoActive();
		check("stack undo restores plane 10", Arrays.equals(plane10, (byte[]) stk.getStack().getPixels(10)) && cursor(stk) == 0);

		// 10. Type conversion: undo restores bit depth and pixels
		WindowManagerHelper.activate(imp);
		byte[] before32 = pix(imp);
		int r2 = rows(imp);
		menu("32-bit", imp, r2 + 1);
		check("32-bit conversion recorded", imp.getBitDepth() == 32);
		IJ.doCommand("Undo");
		waitFor(() -> imp.getBitDepth() == 8, 3000);
		check("Undo of 32-bit restores 8-bit pixels", imp.getBitDepth() == 8 && Arrays.equals(pix(imp), before32));

		// 11. Save marking
		engine.redoActive();
		engine.undoActive();
		imp.changes = true;
		Thread.sleep(1000); // edited for a while (the engine samples the flag every 150 ms)
		imp.changes = false; // what File > Save does
		waitFor(() -> view(imp).rows.get(cursor(imp)).saved, 3000);
		check("save is marked on the current state", view(imp).rows.get(cursor(imp)).saved);

		// 12. Memory budget: oldest steps dropped, opened state kept
		Prefs.set(Engine.PREF + "testMemoryMB", 64);
		engine.settingsChanged();
		ImagePlus big = new ImagePlus("float-test", new FloatProcessor(1024, 1024, randomFloats(1024 * 1024)));
		big.show();
		waitFor(() -> view(big) != null, 5000);
		for (int i = 0; i < 20; i++) {
			int target = Math.min(rows(big) + 1, 99);
			int dropped = view(big).dropped;
			menu("Invert", big, -1);
			final int d0 = dropped;
			waitFor(() -> rows(big) == target || view(big).dropped > d0, 3000);
		}
		check("budget respected (" + engine.usedBytes() / 1048576 + " MB <= 64)", engine.usedBytes() <= 64L * 1048576);
		check("old steps dropped (" + view(big).dropped + ")", view(big).dropped > 0);
		check("opened state never dropped", view(big).rows.get(0).label.equals("Opened"));
		check("saved state of other image kept", savedStillThere(imp));
		Prefs.set(Engine.PREF + "testMemoryMB", 512);
		engine.settingsChanged();

		// 13. Composite image: undo restores pixels and LUTs
		ImagePlus c3 = new ImagePlus("composite-test", noiseStack(128, 128, 3));
		c3.setDimensions(3, 1, 1);
		CompositeImage ci = new CompositeImage(c3, CompositeImage.COMPOSITE);
		ci.show();
		waitFor(() -> view(ci) != null, 5000);
		byte[] ch2 = ((byte[]) ci.getStack().getPixels(2)).clone();
		ci.setC(2);
		menu("Invert", ci, 2);
		boolean changed = !Arrays.equals(ch2, (byte[]) ci.getStack().getPixels(2));
		IJ.doCommand("Undo");
		waitFor(() -> cursor(ci) == 0, 3000);
		check("composite: invert changed channel 2, undo restored it",
				changed && Arrays.equals(ch2, (byte[]) ci.getStack().getPixels(2)));

		// 14. Closing an image frees its memory
		long beforeClose = engine.usedBytes();
		big.changes = false;
		big.close();
		waitFor(() -> engine.usedBytes() < beforeClose, 3000);
		check("closing frees history memory", engine.usedBytes() < beforeClose);

		// 15. Disk tier: tiny memory budget, steps move to disk and undo exactly from there
		Prefs.set(Engine.PREF + "testMemoryMB", 10);
		Prefs.set(Engine.PREF + "useDisk", true);
		engine.settingsChanged();
		ImagePlus fd = new ImagePlus("disk-test", new FloatProcessor(1024, 1024, randomFloats(1024 * 1024)));
		fd.show();
		waitFor(() -> view(fd) != null, 5000);
		float[][] states = new float[7][];
		states[0] = ((float[]) fd.getProcessor().getPixels()).clone();
		String[] cmds = {"Invert", "Smooth", "Square", "Invert", "Smooth", "Square Root"};
		for (int i = 0; i < cmds.length; i++) {
			menu(cmds[i], fd, i + 2);
			states[i + 1] = ((float[]) fd.getProcessor().getPixels()).clone();
		}
		System.out.println("      disk " + engine.diskBytes() / 1048576 + " MB, memory " + engine.usedBytes() / 1048576 + " MB, " + labels(fd));
		check("disk tier: 6 steps kept with a 10 MB memory budget", rows(fd) == 7 && view(fd).dropped == 0);
		check("disk tier: memory stays within budget (" + engine.usedBytes() / 1048576 + " MB)", engine.usedBytes() <= 10L * 1048576);
		check("disk tier: spill files in use", engine.diskBytes() > 0);
		menu("Measure", fd, -1);
		check("no-change command after spill adds no step (hash check)", rows(fd) == 7);
		boolean exact = true;
		for (int k = 5; k >= 0; k--) {
			IJ.doCommand("Undo");
			final int kk = k;
			waitFor(() -> cursor(fd) == kk, 4000);
			exact &= Arrays.equals(states[k], (float[]) fd.getProcessor().getPixels());
		}
		check("disk tier: undo 6 levels back from disk, pixels exact", exact && cursor(fd) == 0);
		engine.goTo(fd.getID(), 6);
		check("disk tier: jump forward to last step exact", Arrays.equals(states[6], (float[]) fd.getProcessor().getPixels()));
		fd.changes = false;
		fd.close();
		Prefs.set(Engine.PREF + "testMemoryMB", 512);
		Prefs.set(Engine.PREF + "useDisk", false);
		engine.settingsChanged();

		// 16. A change that cannot be recorded is not silently discarded by Undo
		Prefs.set(Engine.PREF + "testMemoryMB", 6);
		engine.settingsChanged();
		ImagePlus nr = new ImagePlus("noroom-test", new FloatProcessor(1024, 1024, randomFloats(1024 * 1024)));
		nr.show();
		waitFor(() -> view(nr) != null, 5000);
		menu("Invert", nr, -1);
		waitFor(() -> view(nr).problem != null, 4000);
		if (view(nr).problem == null) {
			System.out.println("      DIAG nr: untracked=" + view(nr).untracked + " labels=" + labels(nr) + " ram=" + engine.usedBytes()
					+ " budget=" + engine.store.ramBudget + " headroom=" + (Store.heapHeadroom() >> 20) + " MB");
			histo();
		}
		check("unrecordable change is reported (" + view(nr).problem + ")", view(nr).problem != null && rows(nr) == 1);
		float[] unrecorded = ((float[]) nr.getProcessor().getPixels()).clone();
		Prefs.set(Engine.PREF + "confirmDiscard", true);
		Thread u = new Thread(engine::undoActive);
		u.start();
		java.awt.Dialog dlg = null;
		for (int i = 0; i < 100 && dlg == null; i++) {
			Thread.sleep(50);
			for (Window x : Window.getWindows())
				if (x instanceof java.awt.Dialog && x.isVisible() && "Undo History".equals(((java.awt.Dialog) x).getTitle())) dlg = (java.awt.Dialog) x;
		}
		check("undo asks before discarding unrecorded changes", dlg != null);
		if (dlg != null) {
			final java.awt.Dialog d = dlg;
			EventQueue.invokeAndWait(() -> d.dispatchEvent(new java.awt.event.WindowEvent(d, java.awt.event.WindowEvent.WINDOW_CLOSING)));
		}
		u.join(5000);
		check("cancel keeps the unrecorded image", Arrays.equals(unrecorded, (float[]) nr.getProcessor().getPixels()));
		Prefs.set(Engine.PREF + "confirmDiscard", false);
		engine.undoActive();
		check("confirmed discard goes back to opened state", !Arrays.equals(unrecorded, (float[]) nr.getProcessor().getPixels())
				&& view(nr).problem == null);
		nr.changes = false;
		nr.close();
		Prefs.set(Engine.PREF + "testMemoryMB", 512);
		engine.settingsChanged();

		// 17. An image refused for lack of room is tracked once the budget is raised
		Prefs.set(Engine.PREF + "testMemoryMB", 2);
		engine.settingsChanged();
		ImagePlus late = new ImagePlus("late-test", new FloatProcessor(1024, 1024, randomFloats(1024 * 1024)));
		late.show();
		waitFor(() -> view(late) != null, 5000);
		check("too-big image is not tracked at first", view(late).untracked != null);
		Prefs.set(Engine.PREF + "testMemoryMB", 512);
		engine.settingsChanged();
		menu("Invert", late, -1);
		waitFor(() -> view(late).untracked == null && rows(late) >= 1, 4000);
		check("after raising the budget it is tracked (" + labels(late) + ")", view(late).untracked == null && rows(late) >= 1);
		late.changes = false;
		late.close();

		// 18. No full re-check after an undo (ImageJ's late "updated" notices are recognised)
		WindowManagerHelper.activate(imp);
		menu("Invert", imp, -1);
		Thread.sleep(800);
		int nc0 = engine.noChangeCaptures;
		engine.undoActive();
		Thread.sleep(1200);
		check("undo causes no extra re-check (" + (engine.noChangeCaptures - nc0) + ")", engine.noChangeCaptures == nc0);

		// 19. Brush stroke on a big (300 MB) stack: only the touched plane is checked, undo restores it
		ImagePlus bigs = new ImagePlus("big-stack-test", noiseStack(1024, 1024, 300));
		bigs.show();
		waitFor(() -> view(bigs) != null && rows(bigs) == 1, 20000);
		bigs.setSlice(150);
		Thread.sleep(300);
		byte[] p150 = ((byte[]) bigs.getStack().getPixels(150)).clone();
		long ram0 = engine.usedBytes() + engine.diskBytes();
		stroke(bigs.getCanvas(), 40, 40, 300, 200);
		waitFor(() -> rows(bigs) == 2, 8000);
		double strokeMs = engine.lastCaptureMs;
		long cost = engine.usedBytes() + engine.diskBytes() - ram0;
		check("big stack stroke recorded as 1 plane (" + cost + " bytes, " + Math.round(strokeMs) + " ms)", rows(bigs) == 2 && cost == 1024 * 1024);
		check("big stack stroke check is fast (" + Math.round(strokeMs) + " ms < 100 ms)", strokeMs < 100);
		IJ.doCommand("Undo");
		waitFor(() -> cursor(bigs) == 0, 8000);
		check("big stack undo restores the painted plane", Arrays.equals(p150, (byte[]) bigs.getStack().getPixels(150)));
		bigs.changes = false;
		bigs.close();

		// 20. A command run the instant an image opens: the opened state is the original
		ImagePlus quick = new ImagePlus("quick-test", noise(256, 256, 99));
		byte[] orig = ((byte[]) quick.getProcessor().getPixels()).clone();
		Thread qm = new Thread(() -> { quick.show(); IJ.run(quick, "Invert", ""); }, "quick-macro");
		qm.start();
		qm.join();
		waitFor(() -> rows(quick) == 2, 4000);
		check("instant command: 2 states (" + labels(quick) + ")", rows(quick) == 2);
		engine.goTo(quick.getID(), 0);
		check("instant command: opened state is the original image", Arrays.equals(orig, (byte[]) quick.getProcessor().getPixels()));
		quick.changes = false;
		quick.close();

		// 21. The window renders
		WindowManagerHelper.activate(imp);
		EventQueue.invokeAndWait(HistoryWindow::showWindow);
		Thread.sleep(1200);
		Frame w = null;
		for (Window x : Window.getWindows()) if (x instanceof HistoryWindow && x.isVisible()) w = (Frame) x;
		check("history window opens", w != null);
		if (w != null) {
			final Frame fw = w;
			BufferedImage bi = new BufferedImage(fw.getWidth(), fw.getHeight(), BufferedImage.TYPE_INT_RGB);
			EventQueue.invokeAndWait(() -> fw.paint(bi.getGraphics()));
			ImageIO.write(bi, "png", new File(outDir, "history-window.png"));
		}

		System.out.println("\nRESULT: " + pass + " passed, " + fail + " failed");
		System.exit(fail == 0 ? 0 : 1);
	}

	// ---- helpers ----

	static void histo() {
		try {
			long pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0].length() > 0
					? Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]) : 0;
			Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/jcmd", "" + pid, "GC.class_histogram").redirectErrorStream(true).start();
			java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
			String l; int n = 0;
			while ((l = br.readLine()) != null && n++ < 10) System.out.println("      " + l);
		} catch (Exception e) { System.out.println("      histo failed " + e); }
	}

	static boolean savedStillThere(ImagePlus imp) {
		for (Engine.Row r : view(imp).rows) if (r.saved) return true;
		return false;
	}

	static void menu(String cmd, ImagePlus imp, int expectRows) throws Exception {
		WindowManagerHelper.activate(imp);
		IJ.doCommand(cmd);
		if (expectRows > 0) waitFor(() -> rows(imp) == expectRows, 5000);
		else Thread.sleep(600);
	}

	static void stroke(ImageCanvas c, int x0, int y0, int x1, int y1) throws Exception {
		EventQueue.invokeAndWait(() -> {
			long t = System.currentTimeMillis();
			c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, x0, y0, 1, false, MouseEvent.BUTTON1));
			for (int i = 1; i <= 20; i++) {
				int x = x0 + (x1 - x0) * i / 20, y = y0 + (y1 - y0) * i / 20;
				c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, t + i, InputEvent.BUTTON1_DOWN_MASK, x, y, 0, false, MouseEvent.NOBUTTON));
			}
			c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 21, 0, x1, y1, 1, false, MouseEvent.BUTTON1));
		});
		Thread.sleep(50);
	}

	static Engine.View view(ImagePlus imp) {
		for (int i = 0; i < 50; i++) {
			Engine.View v = engine.view(imp.getID());
			if (v != null) return v;
			try { Thread.sleep(20); } catch (InterruptedException e) { }
		}
		return null;
	}

	static int rows(ImagePlus imp) { Engine.View v = view(imp); return v == null ? -1 : v.rows.size(); }

	static int cursor(ImagePlus imp) {
		Engine.View v = view(imp);
		if (v == null) return -1;
		for (int i = 0; i < v.rows.size(); i++) if (v.rows.get(i).current) return i;
		return -1;
	}

	static String labels(ImagePlus imp) {
		StringBuilder sb = new StringBuilder("[");
		for (Engine.Row r : view(imp).rows) sb.append(sb.length() > 1 ? ", " : "").append(r.label);
		return sb.append("]").toString();
	}

	static String last(ImagePlus imp) { Engine.View v = view(imp); return v.rows.get(v.rows.size() - 1).label; }

	static byte[] pix(ImagePlus imp) { return ((byte[]) imp.getProcessor().getPixels()).clone(); }

	static ByteProcessor noise(int w, int h, long seed) {
		byte[] p = new byte[w * h];
		new Random(seed).nextBytes(p);
		return new ByteProcessor(w, h, p);
	}

	static ImageStack noiseStack(int w, int h, int n) {
		ImageStack s = new ImageStack(w, h);
		for (int i = 0; i < n; i++) s.addSlice("p" + (i + 1), noise(w, h, i));
		return s;
	}

	static float[] randomFloats(int n) {
		float[] f = new float[n];
		Random r = new Random(1);
		for (int i = 0; i < n; i++) f[i] = r.nextFloat();
		return f;
	}

	static void waitFor(BooleanSupplier c, long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
	}

	static void check(String name, boolean ok) {
		if (ok) pass++; else fail++;
		System.out.println((ok ? "PASS  " : "FAIL  ") + name);
	}

	static final class WindowManagerHelper {
		static void activate(ImagePlus imp) throws Exception {
			ij.WindowManager.setTempCurrentImage(null);
			for (int i = 0; i < 40 && ij.WindowManager.getCurrentImage() != imp; i++) {
				ij.WindowManager.setCurrentWindow(imp.getWindow());
				Thread.sleep(50);
			}
			if (ij.WindowManager.getCurrentImage() != imp) System.out.println("      (could not activate " + imp.getTitle() + ")");
			Thread.sleep(100);
		}
	}
}
