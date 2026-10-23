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
import ij.gui.RoiListener;
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
final class Engine implements ImageListener, CommandListener, RoiListener {

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
	/** Cmd/Ctrl+Shift+Z redoes, instead of opening Image ▸ Color ▸ Channels Tool... (ImageJ's own use of that key). */
	boolean redoShiftZ() { return Prefs.get(PREF + "redoShiftZ", false); }

	static final String CHANNELS_TOOL = "Channels Tool...";
	/** ImageJ's shortcut table keys a Shift shortcut as its key code + 200. */
	static final Integer SHIFT_Z = KeyEvent.VK_Z + 200;

	/**
	 * What the Shift+Z entry of ImageJ's shortcut table becomes: "Redo" when the option is on; the Channels Tool
	 * again when it is turned off and the entry is still ours; otherwise null (leave it: the user may have given
	 * the key to another command).
	 */
	static String shiftZCommand(String current, boolean on) {
		if (on) return "Redo".equals(current) ? null : "Redo";
		return "Redo".equals(current) ? CHANNELS_TOOL : null;
	}

	/**
	 * Gives Cmd/Ctrl+Shift+Z to Edit ▸ Redo, or back to the Channels Tool: in ImageJ's shortcut table (used when an
	 * ImageJ window has the keyboard) and on the two menu items, which show the key and receive it from the macOS
	 * menu bar.
	 */
	static void applyShiftZ(final boolean on) {
		EventQueue.invokeLater(() -> {
			@SuppressWarnings("unchecked")
			java.util.Hashtable<Integer, String> table = ij.Menus.getShortcuts();
			if (table == null) return;
			String next = shiftZCommand(table.get(SHIFT_Z), on);
			if (next == null) return;
			table.put(SHIFT_Z, next);
			java.awt.MenuItem redo = findItem(ij.Menus.getMenuBar(), "Redo");
			java.awt.MenuItem channels = findItem(ij.Menus.getMenuBar(), CHANNELS_TOOL);
			java.awt.MenuShortcut key = new java.awt.MenuShortcut(KeyEvent.VK_Z, true);
			if (on) {
				if (channels != null) channels.deleteShortcut();
				if (redo != null) redo.setShortcut(key);
			} else {
				if (redo != null) redo.deleteShortcut();
				if (channels != null) channels.setShortcut(key);
			}
		});
	}

	static java.awt.MenuItem findItem(java.awt.MenuBar bar, String label) {
		if (bar == null) return null;
		for (int i = 0; i < bar.getMenuCount(); i++) {
			java.awt.MenuItem it = findItem(bar.getMenu(i), label);
			if (it != null) return it;
		}
		return null;
	}

	private static java.awt.MenuItem findItem(java.awt.Menu m, String label) {
		if (m == null) return null;
		for (int i = 0; i < m.getItemCount(); i++) {
			java.awt.MenuItem it = m.getItem(i);
			if (it instanceof java.awt.Menu) {
				java.awt.MenuItem found = findItem((java.awt.Menu) it, label);
				if (found != null) return found;
			} else if (it != null && label.equals(it.getLabel())) return it;
		}
		return null;
	}
	boolean trimOnSave() { return Prefs.get(PREF + "trimOnSave", false); }
	/** How many closed images keep their history (to reopen them, or for a command that replaces an image). */
	int keepClosed() { return (int) Prefs.get(PREF + "keepClosed", 5); }

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
	/** Histories of closed images, most recently closed first. Their memory is the first to be given back. */
	private final java.util.Deque<History> closed = new java.util.ArrayDeque<>();
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
		Roi.addRoiListener(this);
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
				} else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
					mouseDown = false;
					// a tool may have edited the overlay or the selection without touching the pixels
					if (e.getSource() instanceof ImageCanvas) metaChanged(((ImageCanvas) e.getSource()).getImage(), null);
				}
			}
		}, AWTEvent.MOUSE_EVENT_MASK);
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_Z || !redoKey())
				return false;
			boolean cmd = IJ.isMacOSX() ? e.isMetaDown() : e.isControlDown();
			if (!cmd || !e.isAltDown() || e.isShiftDown())
				return false;
			if (altGrInText(IJ.isMacOSX(), e.isControlDown(), e.isAltDown(), focusInText()))
				return false; // AltGr+Z types a letter on some keyboards (ż in Polish): leave it to the text field
			if (WindowManager.getCurrentImage() == null)
				return false;
			new Thread(this::redoActive, "UndoHistory-redo").start();
			return true;
		});
		applyShiftZ(redoShiftZ());
		lastRmQuick = quickRm();
		lastRtQuick = quickRt();
		ticker = new Thread(this::tickLoop, "UndoHistory-ticker");
		ticker.setDaemon(true);
		ticker.start();
	}

	/** On Windows and Linux the AltGr key is reported as Ctrl+Alt; in a text field it may be typing a character. */
	static boolean altGrInText(boolean mac, boolean ctrl, boolean alt, boolean textFocus) {
		return !mac && ctrl && alt && textFocus;
	}

	static boolean focusInText() {
		java.awt.Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		return f instanceof java.awt.TextComponent || f instanceof javax.swing.text.JTextComponent;
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

	/** A selection was drawn, moved, changed or removed. */
	public void roiModified(ImagePlus imp, int id) {
		if (!running || imp == null || id == RoiListener.MODIFIED && mouseDown) return; // drags end with COMPLETED or a release
		metaChanged(imp, "Selection");
	}

	/** Something that is not pixels may have changed: checked by signature at the next quiet moment. */
	private void metaChanged(ImagePlus imp, String what) {
		History h = imp == null ? null : histories.get(imp.getID());
		if (h == null || h.restoring || !h.restoreSettled || h.untracked != null) return;
		if (!h.dirty) h.metaOnly = true;
		h.dirty = true;
		h.lastUpdate = System.currentTimeMillis();
		if (h.pendingCommand == null && h.pendingTool == null && what != null) h.pendingTool = what;
	}

	public void imageClosed(ImagePlus imp) {
		History h = histories.remove(imp.getID());
		lastViews.remove(imp.getID());
		if (h != null) {
			h.closedAt = System.currentTimeMillis();
			h.title = imp.getTitle();
			h.closedBy = liveCommandThread() != null || macroRunning() ? lastCommand : null;
			ImageWindow w = imp.getWindow();
			if (w != null) h.lastLocation = w.getLocation();
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
		h.metaOnly = false;
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
			h.metaOnly = false;
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
		pollShared(now);
		lock.lock();
		try {
			processQueues();
			for (History h : histories.values()) {
				h.threads.removeIf(t -> !t.isAlive());
				if (h.dirty && h.threads.isEmpty() && !macro && !mouseDown && now - h.lastUpdate >= QUIET_MS)
					flush(h);
				if (h.dirty && h.metaOnly && !h.threads.isEmpty()) h.metaOnly = false; // a command is still running on it
				boolean changes = h.imp.changes;
				if (h.lastChanges && !changes && !h.dirty && h.untracked == null)
					markSaved(h);
				h.lastChanges = changes;
			}
		} finally { lock.unlock(); }
	}

	private long lastSharedPoll, lastRmQuick, lastRtQuick;

	/**
	 * The ROI Manager and the Results table can change without a menu command (their own buttons): every half second
	 * a quick signature is compared, and a change becomes a step of the active image.
	 */
	private void pollShared(long now) {
		if (now - lastSharedPoll < 500) return;
		lastSharedPoll = now;
		long rm = Extras.trackRoiManager() ? quickRm() : 0, rt = Extras.trackResults() ? quickRt() : 0;
		boolean rmChanged = rm != lastRmQuick, rtChanged = rt != lastRtQuick;
		lastRmQuick = rm;
		lastRtQuick = rt;
		if (!rmChanged && !rtChanged) return;
		ImagePlus imp = WindowManager.getCurrentImage();
		if (imp != null) metaChanged(imp, rmChanged ? "ROI Manager" : "Results");
	}

	private static long quickRm() {
		ij.plugin.frame.RoiManager m = ij.plugin.frame.RoiManager.getRawInstance();
		if (m == null) return 0;
		Roi[] list = m.getRoisAsArray();
		long h = 7 + list.length;
		for (Roi r : list) h = h * 31 + r.getBounds().hashCode() * 7 + (r.getName() == null ? 0 : r.getName().hashCode());
		return h;
	}

	private static long quickRt() {
		ij.measure.ResultsTable t = ij.plugin.filter.Analyzer.getResultsTable();
		if (t == null) return 0;
		long h = 11 + t.size();
		String heads = t.getColumnHeadings();
		h = h * 31 + (heads == null ? 0 : heads.hashCode());
		if (t.size() > 0) h = h * 31 + t.getRowAsString(t.size() - 1).hashCode();
		return h;
	}

	private void processQueues() {
		processCloses();
		Object[] o;
		while ((o = pendingOpen.poll()) != null)
			register((ImagePlus) o[0], (String) o[1]);
		store.enforce();
	}

	/**
	 * A closed image's history is kept (up to keepClosed() of them) so the image can be reopened with its history,
	 * and so that a command that replaces an image by a new window (Make Composite...) can hand the history over.
	 */
	private void processCloses() {
		History h;
		while ((h = pendingClose.poll()) != null) {
			History heir = h.closedBy == null ? null : heirOf(h);
			if (heir != null) { adopt(h, heir); continue; }
			if (keepClosed() > 0 && h.untracked == null && !h.states.isEmpty()) {
				closed.addFirst(h);
				while (closed.size() > keepClosed()) releaseAll(closed.removeLast());
			} else releaseAll(h);
		}
	}

	private void releaseAll(History h) {
		for (State s : h.states) release(s.snap);
		h.states.clear();
	}

	/** An image the same command opened with the same title, that has only its first state: it replaces the closed one. */
	private History heirOf(History gone) {
		for (History h : histories.values())
			if (h.states.size() == 1 && gone.closedBy.equals(h.createdBy) && h.imp.getTitle().equals(gone.title)) return h;
		return null;
	}

	/** A closed image's history from the same command and with the same title as a newly opened image. */
	private History ancestorOf(History born) {
		if (born.createdBy == null) return null;
		long now = System.currentTimeMillis();
		for (History c : closed)
			if (born.createdBy.equals(c.closedBy) && now - c.closedAt < 30000 && c.title.equals(born.imp.getTitle())) return c;
		return null;
	}

	/**
	 * The new image continues the old one's history: the old steps (up to where it was) come first, the new image as
	 * it appeared is the last step, named after the command. Going back to an older step brings the old kind of image
	 * back in place of the new window.
	 */
	private void adopt(History old, History heir) {
		closed.remove(old);
		while (old.states.size() > old.cursor + 1) release(old.states.remove(old.states.size() - 1).snap);
		State born = heir.states.get(0);
		born.label = pretty(heir.createdBy);
		born.pinned = false;
		heir.states.clear();
		heir.states.addAll(old.states);
		heir.states.add(born);
		heir.cursor = heir.states.size() - 1;
		heir.dropped += old.dropped;
		old.states.clear();
		enforceStepLimit(heir);
		fireChanged();
	}

	/** Reopens the most recently closed image, with its history. Returns false if there is none. */
	boolean reopenLast() {
		History h;
		lock.lock();
		try {
			processQueues();
			h = closed.pollFirst();
			if (h == null) return false;
			State s = h.states.get(h.cursor);
			ImagePlus n;
			try {
				n = s.snap.toImage(h.title, store);
			} catch (IOException e) {
				IJ.error("Undo History", "Could not rebuild " + h.title + ":\n" + e.getMessage());
				releaseAll(h);
				return false;
			}
			h.imp = n;
			h.closedBy = null;
			h.createdBy = null;
			histories.put(n.getID(), h);
			n.changes = !s.clean;
			h.lastChanges = n.changes;
			n.show();
			if (h.lastLocation != null && n.getWindow() != null) n.getWindow().setLocation(h.lastLocation);
		} finally { lock.unlock(); }
		IJ.showStatus("Undo History: reopened " + h.title + " (" + h.states.size() + " steps)");
		fireChanged();
		return true;
	}

	int closedCount() { return closed.size(); }

	// ---- core (caller holds the lock) -----------------------------------

	/** Duration of the last capture (compare + copy), for diagnostics and benchmarks. */
	volatile double lastCaptureMs;

	private void register(ImagePlus imp, String label) {
		if (histories.containsKey(imp.getID()) || imp.getWindow() == null) return;
		processCloses(); // memory of images closed a moment ago is free again
		History h = new History(imp);
		if (label.startsWith("Created by ")) h.createdBy = lastCommand;
		histories.put(imp.getID(), h);
		captureBaseline(h, label);
		h.lastChanges = imp.changes;
		History old = h.states.size() == 1 ? ancestorOf(h) : null;
		if (old != null) adopt(old, h);
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
		if (imp.getProcessor() == null || !histories.containsKey(imp.getID())) { h.dirty = false; return; } // closing
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
			boolean metaOnly = h.metaOnly;
			h.metaOnly = false;
			s = metaOnly ? Snapshot.captureMeta(imp, cur.snap)
					: Snapshot.capture(imp, cur.snap, store, System.currentTimeMillis(), only);
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
		boolean fromCommand = h.pendingCommand != null;
		String label = h.pendingCommand != null ? pretty(h.pendingCommand)
				: h.pendingTool != null ? h.pendingTool : "Edit";
		h.pendingCommand = null;
		h.pendingTool = null;
		if (s == null) { noChangeCaptures++; return; }
		if (s.metaOnly && !fromCommand) {
			// no pixels changed and no command ran: name the step after what changed
			String what = s.metaChange(cur.snap);
			if (what != null) label = what;
		}
		while (h.states.size() > h.cursor + 1)
			release(h.states.remove(h.states.size() - 1).snap);
		if (s.metaOnly && (label.equals("Selection") || label.equals("Display")) && h.cursor > 0
				&& cur.snap.metaOnly && cur.label.equals(label) && !cur.pinned && !cur.saved) {
			// a run of selection (or display) changes is one step: the latest replaces the previous one
			retain(s, System.currentTimeMillis());
			release(cur.snap);
			State st = new State(label, s);
			st.clean = cur.clean && !imp.changes;
			h.states.set(h.cursor, st);
			fireChanged();
			return;
		}
		State st = new State(label, s);
		// a selection, display or ROI Manager change leaves the image as saved, as in ImageJ (no "Save changes?")
		if (s.metaOnly) st.clean = cur.clean && !imp.changes;
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
		if ((s.snap.luts != null) != imp.isComposite()) { // a composite and a plain image: one cannot become the other in place
			replaceWindow(h, k);
			return;
		}
		h.restoring = true;
		h.restoreSettled = false;
		try {
			// the image matches the current step unless its latest change could not be recorded
			Snapshot from = h.problem == null && k != h.cursor && h.cursor >= 0 && h.cursor < h.states.size()
					? h.states.get(h.cursor).snap : null;
			s.snap.restoreInto(imp, store, from);
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

	/** Shows step k as a new window in place of the image's window, which closes; the history moves with it. */
	private void replaceWindow(History h, int k) {
		State s = h.states.get(k);
		ImagePlus old = h.imp;
		ImagePlus n;
		try {
			n = s.snap.toImage(old.getTitle(), store);
		} catch (IOException e) {
			IJ.error("Undo History", "Could not restore \"" + s.label + "\":\n" + e.getMessage());
			return;
		}
		java.awt.Point loc = old.getWindow() != null ? old.getWindow().getLocation() : null;
		histories.remove(old.getID());
		h.imp = n;
		h.cursor = k;
		h.dirty = false;
		h.problem = null;
		histories.put(n.getID(), h);
		n.changes = !s.clean;
		h.lastChanges = n.changes;
		n.show();
		if (loc != null && n.getWindow() != null) n.getWindow().setLocation(loc);
		old.changes = false;
		old.close(); // no history is attached to it any more: closing it releases nothing
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

	/** Gives back the memory of closed images first, then drops the oldest droppable step of any image. */
	boolean dropOldestStep() {
		if (!closed.isEmpty()) {
			releaseAll(closed.removeLast());
			return true;
		}
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
		applyShiftZ(redoShiftZ());
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

	/** Heap held by the current step's selection, ROI Manager list and Results table, not shared with the step before. */
	long currentExtraBytes(int imageID) {
		History h = histories.get(imageID);
		if (h == null || h.cursor < 1) return -1;
		lock.lock();
		try { return h.states.get(h.cursor).snap.extras.ownBytes(h.states.get(h.cursor - 1).snap.extras); }
		finally { lock.unlock(); }
	}
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
		volatile ImagePlus imp;
		volatile String title;
		/** The command that opened this image (when a command did), and the command running when it closed. */
		volatile String createdBy, closedBy;
		volatile long closedAt;
		volatile java.awt.Point lastLocation;
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
		/** Dirty only through non-pixel events (selection, overlay, ROI Manager): compare signatures, not planes. */
		volatile boolean metaOnly;
		volatile long lastUpdate;
		volatile String pendingCommand, pendingTool;
		boolean lastChanges;
		History(ImagePlus imp) { this.imp = imp; this.title = imp.getTitle(); }
	}
}
