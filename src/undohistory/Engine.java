package undohistory;

import ij.CommandListener;
import ij.Executer;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.Prefs;
import ij.Undo;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.macro.Interpreter;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Multi-level undo for ImageJ 1.x / Fiji.
 *
 * Every displayed image gets a {@link History}: a list of {@link Snapshot}s
 * starting with the state it had when it was opened. A new state is committed
 * whenever the pixels or dimensions change, detected at these moments:
 * <ul>
 * <li>just before any menu command or macro {@code run()} (so each command is its own step),</li>
 * <li>when a command's thread finishes,</li>
 * <li>after an image update once the mouse is released and things are quiet
 *     (brush strokes, flood fills, B&amp;C "Apply", ...).</li>
 * </ul>
 * Edit ▸ Undo (Cmd/Ctrl+Z) is redirected here; Cmd/Ctrl+Option/Alt+Z redoes.
 */
final class Engine implements ImageListener, CommandListener {

	static final String PREF = "undohistory.";
	static final long QUIET_MS = 300;
	/** Above this size, brush/pencil/fill strokes are checked only on the planes they touched. */
	static final long PARTIAL_CHECK_BYTES = 256L << 20;

	private static Engine instance;

	static synchronized Engine get() {
		if (instance == null) instance = new Engine();
		return instance;
	}

	static synchronized boolean isRunning() {
		return instance != null && instance.running;
	}

	// ---- settings -------------------------------------------------------

	/** Share of Fiji's maximum memory the history may use (the rest stays for your work). */
	int memoryPercent() { return (int) Prefs.get(PREF + "memoryPercent", 33); }
	boolean useDisk() { return Prefs.get(PREF + "useDisk", true); }
	String diskFolderPref() { return Prefs.get(PREF + "diskFolder", ""); }
	File diskFolder() {
		String f = diskFolderPref();
		return f.isEmpty() ? new File(System.getProperty("java.io.tmpdir"), "FijiUndoHistory") : new File(f);
	}
	/** Disk budget in GB; 0 = automatic (half of the free space, at most 50 GB). */
	double diskGBPref() { return Prefs.get(PREF + "diskGB", 0); }
	double minFreeDiskGB() { return Prefs.get(PREF + "minFreeDiskGB", 5); }

	long ramBudgetBytes() {
		double test = Prefs.get(PREF + "testMemoryMB", 0); // for the test suite only
		if (test > 0) return (long) (test * 1048576);
		return Runtime.getRuntime().maxMemory() / 100 * Math.max(1, Math.min(90, memoryPercent()));
	}

	long diskBudgetBytes() {
		double test = Prefs.get(PREF + "testDiskMB", 0);
		if (test > 0) return (long) (test * 1048576);
		double gb = diskGBPref();
		if (gb > 0) return (long) (gb * 1073741824L);
		File f = diskFolder();
		while (f != null && !f.exists()) f = f.getParentFile();
		long free = f == null ? 0 : f.getUsableSpace();
		return Math.min(50L << 30, free / 2);
	}

	long memoryMB() { return ramBudgetBytes() >> 20; }
	int maxSteps() { return (int) Prefs.get(PREF + "maxSteps", 100); }
	boolean replaceUndo() { return Prefs.get(PREF + "replaceUndo", true); }
	boolean redoKey() { return Prefs.get(PREF + "redoKey", true); }
	boolean trimOnSave() { return Prefs.get(PREF + "trimOnSave", false); }

	private void applySettings() {
		store.ramBudget = ramBudgetBytes();
		store.diskEnabled = useDisk();
		store.diskBudget = diskBudgetBytes();
		store.minFreeDisk = (long) (minFreeDiskGB() * 1073741824L);
		store.folder = diskFolder();
	}

	// ---- state ----------------------------------------------------------

	private final ReentrantLock lock = new ReentrantLock();
	private final Map<Integer, History> histories = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Object[]> pendingOpen = new ConcurrentLinkedQueue<>(); // {ImagePlus, label}
	private final ConcurrentLinkedQueue<History> pendingClose = new ConcurrentLinkedQueue<>();
	final Store store = new Store(this::dropOldestStep);
	private final List<Thread> commandThreads = Collections.synchronizedList(new ArrayList<Thread>());
	private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();
	private volatile String lastCommand;
	private volatile boolean mouseDown;
	private volatile boolean running;
	private Thread ticker;

	private Engine() {}

	// ---- lifecycle ------------------------------------------------------

	synchronized void start() {
		if (running) return;
		running = true;
		applySettings();
		Executer.addCommandListener(this);
		ImagePlus.addImageListener(this);
		int[] ids = WindowManager.getIDList();
		if (ids != null) {
			lock.lock();
			try {
				for (int id : ids) {
					ImagePlus imp = WindowManager.getImage(id);
					if (imp != null && imp.getWindow() != null)
						register(imp, "Tracking started");
				}
			} finally { lock.unlock(); }
		}
		Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
			public void eventDispatched(AWTEvent e) {
				if (e.getID() == MouseEvent.MOUSE_PRESSED) {
					mouseDown = true;
					if (e.getSource() instanceof ImageCanvas)
						commitBeforeStroke(((ImageCanvas) e.getSource()).getImage());
				} else if (e.getID() == MouseEvent.MOUSE_RELEASED)
					mouseDown = false;
			}
		}, AWTEvent.MOUSE_EVENT_MASK);
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_Z || !redoKey())
				return false;
			boolean cmd = IJ.isMacOSX() ? e.isMetaDown() : e.isControlDown();
			if (!cmd || !e.isAltDown() || e.isShiftDown())
				return false;
			if (WindowManager.getCurrentImage() == null)
				return false;
			new Thread(this::redoActive, "UndoHistory-redo").start();
			return true;
		});
		ticker = new Thread(this::tickLoop, "UndoHistory-ticker");
		ticker.setDaemon(true);
		ticker.start();
	}

	// ---- listeners (must never block: some fire on the event thread) ----

	public void imageOpened(ImagePlus imp) {
		if (!running) return;
		// ImageJ delivers this on the event thread: never copy a (possibly huge) image here.
		// The ticker registers it within 150 ms, or a command does just before it runs.
		pendingOpen.add(new Object[] {imp, openLabel()});
	}

	/**
	 * Runs on the event thread before the tool sees the press, so the previous
	 * stroke becomes its own step even when strokes follow each other quickly.
	 */
	private void commitBeforeStroke(ImagePlus imp) {
		History h = imp == null ? null : histories.get(imp.getID());
		if (h == null) return;
		if (h.dirty && h.threads.isEmpty() && !macroRunning() && lock.tryLock()) {
			try { flush(h); } finally { lock.unlock(); }
		}
		h.touched.add(imp.getCurrentSlice()); // the stroke about to start paints this plane
	}

	public void imageClosed(ImagePlus imp) {
		History h = histories.remove(imp.getID());
		lastViews.remove(imp.getID());
		if (h != null) {
			pendingClose.add(h);
			if (lock.tryLock()) {
				try { processCloses(); } finally { lock.unlock(); }
			}
			fireChanged();
		}
	}

	volatile int noChangeCaptures;
	static volatile boolean traceUpdates;

	public void imageUpdated(ImagePlus imp) {
		History h = histories.get(imp.getID());
		if (h == null || h.restoring || !h.restoreSettled) return; // notices queued by our own restore
		if (traceUpdates) new Throwable("imageUpdated " + imp.getTitle() + " on " + Thread.currentThread().getName()).printStackTrace(System.out);
		h.dirty = true;
		h.lastUpdate = System.currentTimeMillis();
		if (h.pendingCommand == null) {
			Thread busyThread = liveCommandThread();
			if (busyThread != null) {
				// Changed by a command started on another image (e.g. Image Calculator).
				h.pendingCommand = lastCommand;
				h.threads.add(busyThread);
			} else {
				String what = describeInteractiveEdit(imp);
				if (h.pendingTool == null) h.pendingTool = what;
				if (what.startsWith("Edit from ")) h.nonToolUpdate = true; // e.g. B&C "Apply" may change every plane
				else h.touched.add(imp.getCurrentSlice());
			}
		} else
			h.nonToolUpdate = true;
	}

	public String commandExecuting(String cmd) {
		if (!running || cmd == null) return cmd;
		if (cmd.equals("Undo")) {
			if (!replaceUndo()) return cmd;
			ImagePlus imp = WindowManager.getCurrentImage();
			if (imp != null && !histories.containsKey(imp.getID())) {
				lock.lock();
				try { processQueues(); } finally { lock.unlock(); }
			}
			History h = imp == null ? null : histories.get(imp.getID());
			if (h == null || h.untracked != null || isPasting(imp)) return cmd;
			lock.lock();
			try {
				processQueues();
				flush(h);
				int target = undoTarget(h);
				if (target >= 0) {
					goTo(h, target);
					return null;
				}
			} finally { lock.unlock(); }
			return cmd; // nothing of ours to undo: let ImageJ undo selections etc.
		}
		if (isOwnCommand(cmd)) return cmd;
		lastCommand = cmd;
		ImagePlus imp = WindowManager.getCurrentImage();
		if (imp != null && imp.getWindow() != null && !histories.containsKey(imp.getID())) {
			// Opened a moment ago (the "opened" notice may still be on its way): record it before the command runs.
			lock.lock();
			try {
				processQueues();
				register(imp, "Opened");
			} finally { lock.unlock(); }
		}
		History h = imp == null ? null : histories.get(imp.getID());
		if (h != null && (h.untracked == null || h.retryBaseline)) {
			Thread t = Thread.currentThread();
			h.threads.removeIf(x -> !x.isAlive());
			if (h.threads.contains(t)) {
				// A command calling other commands (a plugin using IJ.run): one step, named after the outer command.
				h.dirty = true;
				return cmd;
			}
			if (h.threads.isEmpty()) {
				lock.lock();
				try {
					processQueues();
					flush(h); // commit earlier edits (a brush stroke, the previous macro command) as their own step
				} finally { lock.unlock(); }
			}
			h.pendingCommand = cmd;
			h.pendingTool = null;
			h.dirty = true;
			h.lastUpdate = System.currentTimeMillis();
			// Menu commands run in their own thread, which ends with the command;
			// macro commands share the macro's thread, so they end at the next command or the macro's end.
			if (!t.getName().startsWith("Run$_")) {
				h.threads.add(t);
				commandThreads.add(t);
			} else
				lastMacroCommand = System.currentTimeMillis();
		}
		return cmd;
	}

	static boolean isOwnCommand(String cmd) {
		return cmd.startsWith("Undo History") || cmd.equals("Redo") || cmd.startsWith("Keep This State")
				|| cmd.startsWith("Clear History") || cmd.equals("Start Undo History");
	}

	// ---- periodic work --------------------------------------------------

	private void tickLoop() {
		while (true) {
			try {
				Thread.sleep(150);
				tick();
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				IJ.log("Undo History: " + t);
			}
		}
	}

	private void tick() {
		synchronized (commandThreads) {
			commandThreads.removeIf(t -> !t.isAlive());
		}
		boolean macro = macroRunning();
		long now = System.currentTimeMillis();
		lock.lock();
		try {
			processQueues();
			for (History h : histories.values()) {
				h.threads.removeIf(t -> !t.isAlive());
				if (h.dirty && h.threads.isEmpty() && !macro && !mouseDown && now - h.lastUpdate >= QUIET_MS)
					flush(h);
				boolean changes = h.imp.changes;
				if (h.lastChanges && !changes && !h.dirty && h.untracked == null)
					markSaved(h);
				h.lastChanges = changes;
			}
		} finally { lock.unlock(); }
	}

	private void processQueues() {
		processCloses();
		Object[] o;
		while ((o = pendingOpen.poll()) != null)
			register((ImagePlus) o[0], (String) o[1]);
		store.enforce();
	}

	private void processCloses() {
		History h;
		while ((h = pendingClose.poll()) != null)
			for (State s : h.states) release(s.snap);
	}

	// ---- core (caller holds the lock) -----------------------------------

	/** Duration of the last capture (compare + copy), for diagnostics and benchmarks. */
	volatile double lastCaptureMs;

	private void register(ImagePlus imp, String label) {
		if (histories.containsKey(imp.getID()) || imp.getWindow() == null) return;
		processCloses(); // memory of images closed a moment ago is free again
		History h = new History(imp);
		histories.put(imp.getID(), h);
		captureBaseline(h, label);
		h.lastChanges = imp.changes;
		fireChanged();
	}

	/** Stores the first state; on a temporary lack of room, retries at the next change. */
	private void captureBaseline(History h, String label) {
		ImagePlus imp = h.imp;
		long size = Snapshot.sizeInBytes(imp);
		long room = store.ramBudget + (store.diskEnabled ? store.diskBudget : 0);
		if (imp.getStack().isVirtual()) {
			h.untracked = "virtual stack (planes are read from disk)";
		} else if (size > room) {
			h.untracked = "image (" + (size >> 20) + " MB) is larger than the history's memory ("
					+ (store.ramBudget >> 20) + " MB)" + (store.diskEnabled ? " plus disk (" + (store.diskBudget >> 20) + " MB)" : "")
					+ ". Raise them in Options";
		} else {
			long t0 = System.nanoTime();
			try {
				Snapshot s = Snapshot.capture(imp, null, store, System.currentTimeMillis(), null);
				lastCaptureMs = (System.nanoTime() - t0) / 1e6;
				if (s == null) {
					h.untracked = "no pixel data";
				} else {
					State st = new State(label, s);
					st.pinned = true;
					st.clean = !imp.changes;
					h.states.add(st);
					h.cursor = 0;
					h.untracked = null;
					h.retryBaseline = false;
					retain(s, st.time);
				}
			} catch (Store.NoRoomException e) {
				h.untracked = e.getMessage();
				h.retryBaseline = true;
			}
		}
	}

	/** Commits the image's current content as a new state if it changed. */
	private void flush(History h) {
		if (h.retryBaseline && h.dirty) {
			h.dirty = false;
			h.pendingCommand = null;
			h.pendingTool = null;
			captureBaseline(h, "Tracking started (no room when opened)");
			fireChanged();
			return;
		}
		if (h.untracked != null || h.states.isEmpty() || !h.dirty) return;
		ImagePlus imp = h.imp;
		if (isPasting(imp)) return; // wait until the paste is committed
		h.dirty = false;
		State cur = h.states.get(h.cursor);
		long t0 = System.nanoTime();
		Snapshot s;
		try {
			Set<Integer> only = null;
			if (h.pendingCommand == null && !h.nonToolUpdate && !h.touched.isEmpty()
					&& Snapshot.sizeInBytes(imp) > PARTIAL_CHECK_BYTES)
				only = new java.util.HashSet<>(h.touched); // tool strokes on a big stack: check only the planes they touched
			h.touched.clear();
			h.nonToolUpdate = false;
			s = Snapshot.capture(imp, cur.snap, store, System.currentTimeMillis(), only);
			h.problem = null;
		} catch (Store.NoRoomException e) {
			h.problem = "Last change not recorded: " + e.getMessage();
			h.pendingCommand = null;
			h.pendingTool = null;
			IJ.showStatus("Undo History: " + h.problem);
			fireChanged();
			return;
		}
		lastCaptureMs = (System.nanoTime() - t0) / 1e6;
		String label = h.pendingCommand != null ? pretty(h.pendingCommand)
				: h.pendingTool != null ? h.pendingTool : "Edit";
		h.pendingCommand = null;
		h.pendingTool = null;
		if (s == null) { noChangeCaptures++; return; }
		while (h.states.size() > h.cursor + 1)
			release(h.states.remove(h.states.size() - 1).snap);
		State st = new State(label, s);
		h.states.add(st);
		h.cursor = h.states.size() - 1;
		retain(s, st.time);
		enforceStepLimit(h);
		fireChanged();
	}

	private void goTo(History h, int k) {
		if (k < 0 || k >= h.states.size()) return;
		State s = h.states.get(k);
		ImagePlus imp = h.imp;
		if (h.problem != null && Prefs.get(PREF + "confirmDiscard", true)
				&& !IJ.showMessageWithCancel("Undo History",
						"The image's latest changes are not in the history:\n" + h.problem
						+ "\n \nGoing to \"" + s.label + "\" will discard them for good.\n"
						+ "To keep them, cancel and save a copy first (File > Save As),\n"
						+ "or give the history more room (Undo History > Options).\n \nDiscard the unrecorded changes?"))
			return;
		h.restoring = true;
		h.restoreSettled = false;
		try {
			s.snap.restoreInto(imp, store);
			h.cursor = k;
		} catch (IOException e) {
			IJ.error("Undo History", "Could not restore \"" + s.label + "\":\n" + e.getMessage());
			return;
		} finally {
			h.restoring = false;
			// ImageJ posts its "updated" notices to the event queue; this marker runs after them.
			EventQueue.invokeLater(() -> h.restoreSettled = true);
		}
		h.dirty = false;
		h.problem = null;
		h.pendingCommand = null;
		h.pendingTool = null;
		Undo.reset();
		imp.changes = !s.clean;
		h.lastChanges = imp.changes;
		IJ.showStatus("Undo History: " + s.label + " (" + k + "/" + (h.states.size() - 1) + ")");
		fireChanged();
	}

	private void markSaved(History h) {
		if (h.states.isEmpty()) return;
		State s = h.states.get(h.cursor);
		for (State o : h.states) o.clean = false;
		s.clean = true;
		s.saved = true;
		s.pinned = true;
		if (trimOnSave()) {
			for (int i = h.states.size() - 1; i >= 0; i--)
				if (i != h.cursor) {
					release(h.states.remove(i).snap);
					h.dropped++;
				}
			h.cursor = 0;
		}
		fireChanged();
	}

	private void enforceStepLimit(History h) {
		int max = Math.max(1, maxSteps());
		while (h.states.size() - 1 > max) {
			int i = oldestRemovable(h);
			if (i < 0) break;
			removeState(h, i);
		}
	}

	/** Drops the oldest droppable step of any image (called by the store when it needs room). */
	boolean dropOldestStep() {
		History best = null;
		int bestIdx = -1;
		long bestTime = Long.MAX_VALUE;
		for (History h : histories.values()) {
			int i = oldestRemovable(h);
			if (i >= 0 && h.states.get(i).time < bestTime) {
				best = h; bestIdx = i; bestTime = h.states.get(i).time;
			}
		}
		if (best == null) return false;
		removeState(best, bestIdx);
		return true;
	}

	private static int oldestRemovable(History h) {
		for (int i = 0; i < h.states.size(); i++)
			if (i != h.cursor && !h.states.get(i).pinned) return i;
		return -1;
	}

	private void removeState(History h, int i) {
		release(h.states.remove(i).snap);
		if (i < h.cursor) h.cursor--;
		h.dropped++;
	}

	private void retain(Snapshot s, long time) {
		for (Plane p : s.planes) store.retain(p, time);
	}

	private void release(Snapshot s) {
		for (Plane p : s.planes) store.release(p);
	}

	// ---- public operations (any thread except the event thread) ---------

	void undoActive() { step(-1); }
	void redoActive() { step(+1); }

	private void step(int delta) {
		ImagePlus imp = WindowManager.getCurrentImage();
		History h = imp == null ? null : histories.get(imp.getID());
		if (h == null) { IJ.showStatus("Undo History: no tracked image"); return; }
		lock.lock();
		try {
			processQueues();
			flush(h);
			int k = delta < 0 ? undoTarget(h) : h.cursor + delta;
			if (k < 0 || k >= h.states.size()) {
				IJ.beep();
				IJ.showStatus("Undo History: nothing to " + (delta < 0 ? "undo" : "redo"));
				return;
			}
			goTo(h, k);
		} finally { lock.unlock(); }
	}

	/**
	 * Where Undo goes: the previous step, or, when the latest change could not be recorded,
	 * back to the last recorded step (the image no longer matches it).
	 */
	private static int undoTarget(History h) {
		return h.problem != null ? h.cursor : h.cursor - 1;
	}

	void goTo(int imageID, int k) {
		History h = histories.get(imageID);
		if (h == null) return;
		lock.lock();
		try {
			processQueues();
			flush(h);
			if (k != h.cursor || h.problem != null) goTo(h, k);
		} finally { lock.unlock(); }
	}

	void togglePin(int imageID, int k) {
		History h = histories.get(imageID);
		if (h == null) return;
		lock.lock();
		try {
			if (k == 0 && k < h.states.size()) return; // the first state always stays
			if (k >= 0 && k < h.states.size()) h.states.get(k).pinned ^= true;
		} finally { lock.unlock(); }
		fireChanged();
	}

	void clear(int imageID) {
		History h = histories.get(imageID);
		if (h == null) return;
		lock.lock();
		try {
			flush(h);
			for (int i = h.states.size() - 1; i >= 0; i--)
				if (i != h.cursor) {
					release(h.states.remove(i).snap);
					h.dropped++;
				}
			h.cursor = 0;
			if (!h.states.isEmpty()) h.states.get(0).pinned = true;
		} finally { lock.unlock(); }
		fireChanged();
	}

	ImagePlus copyOf(int imageID, int k) {
		History h = histories.get(imageID);
		if (h == null || k < 0 || k >= h.states.size()) return null;
		State s = h.states.get(k);
		String title = WindowManager.getUniqueName(h.imp.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", "")
				+ " [step " + k + " " + s.label + "]");
		lock.lock();
		try {
			return s.snap.toImage(title, store);
		} catch (IOException e) {
			IJ.error("Undo History", "Could not read step " + k + ": " + e.getMessage());
			return null;
		} finally { lock.unlock(); }
	}

	void settingsChanged() {
		lock.lock();
		try {
			applySettings();
			for (History h : histories.values()) {
				enforceStepLimit(h);
				if (h.states.isEmpty() && !h.imp.getStack().isVirtual()) {
					h.retryBaseline = true; // e.g. budgets were raised: try again at the next change
					h.dirty = true;
				}
			}
			store.enforce();
		} finally { lock.unlock(); }
		fireChanged();
	}

	// ---- read-only view for the window (no locking) ---------------------

	static final class Row {
		final String label, time;
		final boolean current, future, pinned, saved, clean;
		Row(State s, boolean current, boolean future) {
			label = s.label;
			time = s.timeString();
			this.current = current;
			this.future = future;
			pinned = s.pinned;
			saved = s.saved;
			clean = s.clean;
		}
	}

	static final class View {
		String title;
		String untracked, problem;
		int dropped;
		final List<Row> rows = new ArrayList<>();
	}

	private final Map<Integer, View> lastViews = new ConcurrentHashMap<>();

	/** Called on the event thread: never waits for the lock, falls back to the last view. */
	/** Current step of an image, waiting for the lock (tests and benchmarks). */
	int cursorOf(int imageID) {
		History h = histories.get(imageID);
		if (h == null) return -1;
		lock.lock();
		try { return h.cursor; } finally { lock.unlock(); }
	}

	View view(int imageID) {
		History h = histories.get(imageID);
		if (h == null) return null;
		if (!lock.tryLock()) return lastViews.get(imageID);
		try {
			View v = buildView(h);
			lastViews.put(imageID, v);
			return v;
		} finally { lock.unlock(); }
	}

	private View buildView(History h) {
		View v = new View();
		v.title = h.imp.getTitle();
		v.untracked = h.untracked;
		v.problem = h.problem;
		v.dropped = h.dropped;
		List<State> states = h.states;
		int cursor = h.cursor;
		for (int i = 0; i < states.size(); i++)
			v.rows.add(new Row(states.get(i), i == cursor, i > cursor));
		return v;
	}

	long usedBytes() { return store.ramBytes; }
	long diskBytes() { return store.diskBytes; }
	int trackedImages() { return histories.size(); }

	void addChangeListener(Runnable r) { changeListeners.add(r); }
	void removeChangeListener(Runnable r) { changeListeners.remove(r); }

	private void fireChanged() {
		for (Runnable r : changeListeners) EventQueue.invokeLater(r);
	}

	// ---- helpers --------------------------------------------------------

	private boolean tryLock() {
		try {
			return lock.tryLock(50, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			return false;
		}
	}

	private Thread liveCommandThread() {
		synchronized (commandThreads) {
			for (int i = commandThreads.size() - 1; i >= 0; i--)
				if (commandThreads.get(i).isAlive()) return commandThreads.get(i);
		}
		return null;
	}

	private String openLabel() {
		if (liveCommandThread() != null || macroRunning())
			return lastCommand != null ? "Created by " + pretty(lastCommand) : "Created";
		return "Opened";
	}

	private volatile long lastMacroCommand;

	/**
	 * True inside a macro's run() call, or between two run() calls of a running macro.
	 * The interpreter reference can outlive its macro, so it only counts for 2 s after a run().
	 */
	private boolean macroRunning() {
		return IJ.macroRunning()
				|| Interpreter.getInstance() != null && System.currentTimeMillis() - lastMacroCommand < 2000;
	}

	private static String describeInteractiveEdit(ImagePlus imp) {
		Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (w != null && !(w instanceof ImageWindow) && w != IJ.getInstance()) {
			String t = w instanceof Frame ? ((Frame) w).getTitle() : null;
			if (t != null && !t.isEmpty()) return "Edit from " + t;
		}
		String tool = Toolbar.getToolName();
		if (tool == null || tool.isEmpty()) return "Edit";
		return tool.substring(0, 1).toUpperCase() + tool.substring(1);
	}

	static String pretty(String cmd) {
		return cmd.endsWith("...") ? cmd.substring(0, cmd.length() - 3) : cmd;
	}

	private static Field clipboardField;

	/** True while a pasted selection is still floating (Edit ▸ Paste not yet committed). */
	static boolean isPasting(ImagePlus imp) {
		Roi roi = imp.getRoi();
		if (roi == null) return false;
		try {
			if (clipboardField == null) {
				clipboardField = Roi.class.getDeclaredField("clipboard");
				clipboardField.setAccessible(true);
			}
			return clipboardField.get(roi) != null;
		} catch (Throwable t) {
			return false;
		}
	}

	// ---- data -----------------------------------------------------------

	static final class State {
		String label;
		final long time = System.currentTimeMillis();
		final Snapshot snap;
		boolean pinned, saved, clean;
		State(String label, Snapshot snap) { this.label = label; this.snap = snap; }
		String timeString() {
			return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(time));
		}
	}

	static final class History {
		final ImagePlus imp;
		final List<State> states = new ArrayList<>();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		int cursor = -1;
		int dropped;
		String untracked, problem;
		boolean retryBaseline;
		volatile boolean dirty, restoring, restoreSettled = true;
		/** Planes touched by interactive tools since the last step (1-based), and whether anything else changed. */
		final Set<Integer> touched = ConcurrentHashMap.newKeySet();
		volatile boolean nonToolUpdate;
		volatile long lastUpdate;
		volatile String pendingCommand, pendingTool;
		boolean lastChanges;
		History(ImagePlus imp) { this.imp = imp; }
	}
}
