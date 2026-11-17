package undohistory;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The "Undo History" panel: every state of the active image. A click on a step brings the image back (or forward) to
 * it; Cmd/Ctrl-click and Shift-click select steps without moving. Keep, Copy, Clear (Delete) and Macro act on the
 * selected steps; with none selected, Clear and Macro act on the whole history, Keep and Copy on the current step.
 */
final class HistoryWindow extends JFrame {

	private static HistoryWindow instance;
	private static final String LOC = Engine.PREF + "window.loc";

	private final Engine engine = Engine.get();
	private final DefaultListModel<Engine.Row> model = new DefaultListModel<>();
	private final JList<Engine.Row> list = new JList<>(model);
	private final JLabel header = new JLabel(" ");
	private final JLabel footer = new JLabel(" ");
	private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"),
			keep = new JButton("Keep"), copy = new JButton("Copy"),
			clear = new JButton("Clear"), macro = new JButton("Macro"),
			options = new JButton("Options…"), onOff = new JButton("Stop");
	private final Runnable refresher = this::refresh;
	private final Timer timer;
	private int imageID;
	private int shownStamp = -1;

	static void showWindow() {
		if (instance == null) instance = new HistoryWindow();
		instance.setVisible(true);
		instance.toFront();
		instance.refresh();
	}

	private HistoryWindow() {
		super("Undo History");
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		list.setCellRenderer(new RowRenderer());
		javax.swing.ToolTipManager.sharedInstance().registerComponent(list); // each row's tooltip shows its macro code
		list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (e.isShiftDown() || e.isControlDown() || e.isMetaDown()) return; // selecting, not moving
				int k = list.locationToIndex(e.getPoint());
				if (k < 0 || !list.getCellBounds(k, k).contains(e.getPoint())) return;
				list.clearSelection();
				final int id = imageID;
				run(() -> engine.goTo(id, k));
			}
		});
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSteps");
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteSteps");
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "unselect");
		list.getActionMap().put("deleteSteps", new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				final int id = imageID;
				final int[] ks = selectedOrCurrent();
				if (ks.length > 0) run(() -> deleteSteps(id, ks));
			}
		});
		list.getActionMap().put("unselect", new AbstractAction() {
			public void actionPerformed(ActionEvent e) { list.clearSelection(); }
		});
		list.addListSelectionListener(e -> updateButtons());
		header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
		header.setFont(header.getFont().deriveFont(Font.BOLD));
		footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
		footer.setFont(footer.getFont().deriveFont(footer.getFont().getSize2D() - 1f));

		undo.setToolTipText("Step back (also Edit ▸ Undo, " + (IJ.isMacOSX() ? "⌘Z" : "Ctrl+Z") + ")");
		redo.setToolTipText("Step forward (" + (IJ.isMacOSX() ? "⌘⇧Z or ⌘⌥Z" : "Ctrl+Shift+Z or Ctrl+Alt+Z") + ")");
		undo.addActionListener(e -> run(engine::undoActive));
		redo.addActionListener(e -> run(engine::redoActive));
		keep.addActionListener(e -> {
			for (int k : selectedOrCurrent()) engine.togglePin(imageID, k);
		});
		copy.addActionListener(e -> {
			final int id = imageID;
			final int[] ks = selectedOrCurrent();
			run(() -> { for (int k : ks) { ImagePlus c = engine.copyOf(id, k); if (c != null) c.show(); } });
		});
		clear.addActionListener(e -> {
			final int id = imageID;
			final int[] ks = list.getSelectedIndices();
			if (ks.length > 0) run(() -> deleteSteps(id, ks));
			else run(() -> {
				if (IJ.showMessageWithCancel("Undo History", "Forget every step of this image except the current one?\n"
						+ "The image stays as it is."))
					engine.clear(id);
			});
		});
		macro.addActionListener(e -> {
			final int id = imageID;
			final int[] ks = list.getSelectedIndices();
			run(() -> Macros.create(id, ks));
		});
		options.addActionListener(e -> run(Undo_History::showOptions));
		onOff.addActionListener(e -> run(() -> new Undo_History().run("toggle")));

		JPanel buttons = new JPanel(new GridLayout(2, 3, 4, 4));
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
		for (JButton b : new JButton[] {undo, redo, keep, copy, clear, macro}) buttons.add(b);
		JPanel south = new JPanel(new BorderLayout());
		south.add(buttons, BorderLayout.NORTH);
		south.add(footer, BorderLayout.SOUTH);

		JPanel north = new JPanel(new BorderLayout());
		north.add(header, BorderLayout.CENTER);
		JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6));
		top.add(onOff);
		top.add(options);
		north.add(top, BorderLayout.EAST);
		getContentPane().add(north, BorderLayout.NORTH);
		getContentPane().add(new JScrollPane(list), BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);
		setSize(420, 480);
		Point loc = Prefs.getLocation(LOC);
		if (loc != null) setLocation(loc); else setLocationRelativeTo(null);

		engine.addChangeListener(refresher);
		timer = new Timer(400, e -> refresh());
		timer.start();
		WindowManager.addWindow(this);
		addWindowListener(new WindowAdapter() {
			public void windowClosed(WindowEvent e) {
				Prefs.saveLocation(LOC, getLocation());
				timer.stop();
				engine.removeChangeListener(refresher);
				WindowManager.removeWindow(HistoryWindow.this);
				instance = null;
			}
		});
	}

	private static void run(Runnable r) {
		new Thread(r, "UndoHistory-action").start();
	}

	/** The selected steps, or the current one when none is selected. */
	private int[] selectedOrCurrent() {
		int[] ks = list.getSelectedIndices();
		if (ks.length > 0) return ks;
		for (int i = 0; i < model.size(); i++) if (model.get(i).current) return new int[] {i};
		return new int[0];
	}

	/**
	 * Deletes steps, removing their effect: the later steps run again from their macro code. Asks first when that
	 * takes more than undoing the last step, and offers "Forget only" when a later step cannot run again exactly.
	 */
	static void deleteSteps(int imageID, int[] ks) {
		Engine e = Engine.get();
		Engine.DeletePlan p = e.planDelete(imageID, ks);
		if (p == null) {
			IJ.showStatus("Undo History: the opened state cannot be deleted");
			return;
		}
		boolean forgetOnly = false;
		String what = p.steps.length == 1 ? "step " + p.steps[0] : p.steps.length + " steps";
		if (!p.notExact.isEmpty()) {
			boolean someHaveNoCode = false;
			StringBuilder list = new StringBuilder();
			for (int i = 0; i < p.notExact.size(); i++) {
				if (i == 8) { list.append("\n   … and ").append(p.notExact.size() - 8).append(" more"); break; }
				list.append("\n   ").append(p.notExact.get(i));
			}
			for (String s : p.notExact) someHaveNoCode |= s.endsWith("(no macro code)");
			GenericDialog gd = new GenericDialog("Undo History");
			gd.addMessage("Deleting " + what + " removes its effect: the " + p.replays + " later step"
					+ (p.replays == 1 ? "" : "s") + " run again from their macro code.\n"
					+ "These cannot run again exactly:" + list + "\n \n"
					+ "Forget only: the deleted steps' memory is freed, but their effect stays in the later steps.");
			if (someHaveNoCode) {
				gd.setOKLabel("Forget only");
			} else {
				gd.enableYesNoCancel("Run again anyway", "Forget only");
			}
			gd.showDialog();
			if (gd.wasCanceled()) return;
			forgetOnly = someHaveNoCode || !gd.wasOKed();
		} else if (p.replays > 0) {
			if (!IJ.showMessageWithCancel("Undo History", "Delete " + what + "?\n \n"
					+ "Its effect is removed: the image goes back to the step before, and the " + p.replays + " later step"
					+ (p.replays == 1 ? "" : "s") + " run again from their macro code."))
				return;
		}
		String error = e.deleteSteps(imageID, p, forgetOnly);
		if (error != null) IJ.error("Undo History", "Could not delete " + what + ":\n" + error);
	}

	private void updateButtons() {
		boolean sel = list.getSelectedIndices().length > 0;
		clear.setText(sel ? "Delete" : "Clear");
		clear.setToolTipText(sel ? "Delete the selected steps and their effect (also the Delete key)"
				: "Forget every step of this image except the current one (the image stays as it is)");
		keep.setToolTipText("Keep " + (sel ? "the selected steps" : "the current step")
				+ ": a kept step is never dropped when memory runs short");
		copy.setToolTipText("Open " + (sel ? "each selected step" : "the current step") + " as a new image");
		macro.setToolTipText("Make a macro of " + (sel ? "the selected steps" : "every step up to the current one")
				+ ", to run on any image");
	}

	private void refresh() {
		if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
		ImagePlus imp = WindowManager.getCurrentImage();
		Engine.View v = imp == null ? null : engine.view(imp.getID());
		int id = imp == null ? 0 : imp.getID();
		boolean running = Engine.isRunning(), paused = Engine.isPaused();
		int stamp = java.util.Objects.hash(running, paused, v == null ? 0 : java.util.Objects.hash(id, v.rows.size(), v.dropped, rowsSignature(v)));
		onOff.setText(running ? "Stop" : paused ? "Resume" : "Start");
		onOff.setToolTipText(running ? "Pause, or stop and forget the history (Edit ▸ Start/Stop Undo History)"
				: paused ? "Resume recording: what changed while paused becomes one step"
				: "Start recording a history for every open image (Edit ▸ Start/Stop Undo History)");
		String mem = "Memory " + mb(engine.usedBytes()) + " / " + gb(engine.store.ramBudget)
				+ (engine.store.diskEnabled ? " · Disk " + mb(engine.diskBytes()) + " / " + gb(engine.store.diskBudget) : "");
		footer.setText(running ? mem : paused ? "Paused: Edit ▸ Undo is ImageJ's single undo · " + mem
				: "Stopped: Edit ▸ Undo is ImageJ's single undo");
		footer.setToolTipText(engine.trackedImages() + " image(s) tracked"
				+ (engine.store.sessionDirPath() != null ? " · spill folder: " + engine.store.sessionDirPath() : ""));
		if (stamp == shownStamp && id == imageID) return;
		int[] keepSelected = id == imageID ? list.getSelectedIndices() : new int[0];
		shownStamp = stamp;
		imageID = id;
		model.clear();
		boolean enabled = running && v != null && v.untracked == null;
		for (JButton b : new JButton[] {undo, redo, keep, copy, clear, macro}) b.setEnabled(enabled);
		list.setEnabled(running);
		if (!running && !paused) { header.setText("<html>Undo History is stopped<br><span style='font-weight:normal'>Start records every open image from now on</span></html>"); return; }
		if (imp == null) { header.setText(paused ? "Undo History is paused" : "No image open"); return; }
		if (v == null) { header.setText(imp.getTitle() + ": not tracked"); return; }
		if (v.untracked != null) {
			header.setText("<html>" + esc(v.title) + "<br><span style='font-weight:normal'>Not tracked: " + esc(v.untracked) + "</span></html>");
			return;
		}
		String dropped = v.dropped > 0 ? " · " + v.dropped + " older step(s) dropped" : "";
		String problem = v.problem == null ? "" : "<br><span style='font-weight:normal;color:#b00020'>" + esc(v.problem) + "</span>";
		String state = paused ? " · <b>paused</b>" : "";
		header.setText("<html>" + esc(v.title) + "<br><span style='font-weight:normal'>" + (v.rows.size() - 1)
				+ " step(s)" + dropped + state + "</span>" + problem + "</html>");
		int current = -1;
		for (int i = 0; i < v.rows.size(); i++) {
			model.addElement(v.rows.get(i));
			if (v.rows.get(i).current) current = i;
		}
		list.clearSelection();
		for (int k : keepSelected) if (k < model.size()) list.addSelectionInterval(k, k);
		if (current >= 0) list.ensureIndexIsVisible(current);
		updateButtons();
	}

	private static int rowsSignature(Engine.View v) {
		int h = 1;
		for (Engine.Row r : v.rows)
			h = 31 * h + java.util.Objects.hash(r.label, r.time, r.current, r.pinned, r.saved);
		return 31 * h + java.util.Objects.hashCode(v.problem);
	}

	private static String mb(long bytes) { return IJ.d2s(bytes / 1048576.0, bytes < 10 * 1048576 ? 1 : 0) + " MB"; }

	private static String gb(long bytes) { return IJ.d2s(bytes / 1073741824.0, 1) + " GB"; }

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private final class RowRenderer extends JLabel implements ListCellRenderer<Engine.Row> {
		RowRenderer() {
			setOpaque(true);
			setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		}

		public Component getListCellRendererComponent(JList<? extends Engine.Row> l, Engine.Row r, int index,
				boolean selected, boolean focus) {
			String marker = r.current ? "▶ " : "    ";
			String tags = (r.saved ? "  [saved]" : "") + (r.pinned && index > 0 && !r.saved ? "  [kept]" : "");
			setText(marker + index + "  " + r.label + tags + "   " + r.time);
			setToolTipText(index == 0 ? "The state the image had when it was opened"
					: r.code.isEmpty() ? "No macro code for this step"
					: "<html><pre>" + esc(r.code.trim()) + "</pre></html>");
			Font f = l.getFont();
			setFont(r.current ? f.deriveFont(Font.BOLD) : r.future ? f.deriveFont(Font.ITALIC) : f);
			Color fg = r.future ? Color.GRAY : l.getForeground();
			if (selected) {
				setBackground(l.getSelectionBackground());
				setForeground(l.getSelectionForeground());
			} else {
				setBackground(r.current ? new Color(0xDCE8F8) : l.getBackground());
				setForeground(r.current ? Color.BLACK : fg);
			}
			return this;
		}
	}
}
