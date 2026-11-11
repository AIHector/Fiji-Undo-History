package undohistory;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** The "Undo History" panel: every state of the active image; click one to go back (or forward) to it. */
final class HistoryWindow extends JFrame {

	private static HistoryWindow instance;
	private static final String LOC = Engine.PREF + "window.loc";

	private final Engine engine = Engine.get();
	private final DefaultListModel<Engine.Row> model = new DefaultListModel<>();
	private final JList<Engine.Row> list = new JList<>(model);
	private final JLabel header = new JLabel(" ");
	private final JLabel footer = new JLabel(" ");
	private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"),
			keep = new JButton("Keep"), copy = new JButton("Copy"), delete = new JButton("Delete"),
			clear = new JButton("Clear"), options = new JButton("Options…"),
			go = new JButton("Go to"), macro = new JButton("Create macro…"), record = new JButton("Record…");
	private final Runnable refresher = this::refresh;
	private final Timer timer;
	private int imageID;
	private boolean busy, restoreProblem;
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
		list.setToolTipText("Select steps for a macro with Shift or Cmd/Ctrl. Double-click one step, or use Go to, to restore it.");
		list.addListSelectionListener(e -> updateButtons());
		list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				int k = list.locationToIndex(e.getPoint());
				if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e) || k < 0
						|| !list.getCellBounds(k, k).contains(e.getPoint())) return;
				rowAction("go");
			}
		});
		header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
		header.setFont(header.getFont().deriveFont(Font.BOLD));
		footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
		footer.setFont(footer.getFont().deriveFont(footer.getFont().getSize2D() - 1f));

		undo.setToolTipText("Step back (also Edit ▸ Undo, " + (IJ.isMacOSX() ? "⌘Z" : "Ctrl+Z") + ")");
		redo.setToolTipText("Step forward (" + (IJ.isMacOSX() ? "⌘⌥Z" : "Ctrl+Alt+Z") + ")");
		keep.setToolTipText("Keep the selected step: it is never dropped when memory runs short");
		copy.setToolTipText("Open the selected step (or the current one) as a new image, leaving this one as it is");
		clear.setToolTipText("Forget all steps of this image except the current one");
		delete.setToolTipText("Delete the selected step and free its memory or disk space at once (the step you are on: "
			+ "the image goes back to the previous step first). The first step stays.");
		delete.addActionListener(e -> rowAction("delete"));
		go.addActionListener(e -> rowAction("go"));
		go.setToolTipText("Restore exactly one selected step; selecting rows alone does not change the image");
		undo.addActionListener(e -> { final int id = imageID; run(() -> engine.stepImage(id, -1)); });
		redo.addActionListener(e -> { final int id = imageID; run(() -> engine.stepImage(id, 1)); });
		keep.addActionListener(e -> rowAction("keep"));
		copy.addActionListener(e -> rowAction("copy"));
		clear.addActionListener(e -> {
			final int id = imageID;
			if (IJ.showMessageWithCancel("Undo History", "Forget every step of this image except the current one?"))
				run(() -> engine.clear(id));
		});
		options.addActionListener(e -> run(Undo_History::showOptions));
		record.setToolTipText("Open ImageJ's Macro Recorder in Macro mode before performing operations to export");
		record.addActionListener(e -> java.awt.EventQueue.invokeLater(() -> {
			if (ij.plugin.frame.Recorder.getInstance() == null) {
				Prefs.set("recorder.mode", "Macro");
				new ij.plugin.frame.Recorder();
			} else {
				ij.plugin.frame.Recorder.getInstance().setVisible(true);
				ij.plugin.frame.Recorder.getInstance().toFront();
				if (ij.plugin.frame.Recorder.scriptMode())
					IJ.showMessage("Undo History", "Choose Macro in the Recorder's Record menu before recording new steps.");
			}
		}));
		macro.setToolTipText("Preview and save selected operations in history order; missing code is marked explicitly");
		macro.addActionListener(e -> {
			final int id = imageID;
			final long[] keys = list.getSelectedValuesList().stream().mapToLong(r -> r.key).toArray();
			run(() -> {
				MacroExport export = engine.exportMacro(id, keys);
				SwingUtilities.invokeLater(() -> MacroPreview.show(export));
			});
		});

		JPanel buttons = new JPanel(new GridLayout(0, 3, 4, 4));
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
		for (JButton b : new JButton[] {undo, redo, go, keep, copy, delete, record, macro, clear, options}) buttons.add(b);
		JPanel south = new JPanel(new BorderLayout());
		south.add(buttons, BorderLayout.NORTH);
		south.add(footer, BorderLayout.SOUTH);

		JPanel north = new JPanel(new BorderLayout());
		north.add(header, BorderLayout.NORTH);
		JLabel hint = new JLabel("Select rows → Create macro · Double-click → Restore");
		hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
		hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1));
		north.add(hint, BorderLayout.SOUTH);
		getContentPane().add(north, BorderLayout.NORTH);
		getContentPane().add(new JScrollPane(list), BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);
		setSize(460, 520);
		setMinimumSize(new java.awt.Dimension(390, 300));
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

	private void run(Runnable r) {
		if (busy) return;
		busy = true; updateButtons();
		new Thread(() -> {
			try { r.run(); }
			catch (IllegalArgumentException e) { IJ.showMessage("Undo History", e.getMessage()); }
			catch (Throwable e) { IJ.handleException(e); }
			finally { SwingUtilities.invokeLater(() -> { busy = false; shownStamp = -1; refresh(); }); }
		}, "UndoHistory-action").start();
	}

	private void rowAction(String action) {
		final int id = imageID, index = selectedOrCurrent();
		if (index < 0 || list.getSelectedIndices().length > 1) return;
		final long key = model.get(index).key;
		run(() -> engine.rowAction(id, key, action));
	}

	private void updateButtons() {
		int current = -1;
		for (int i = 0; i < model.size(); i++) if (model.get(i).current) current = i;
		boolean enabled = !busy && current >= 0;
		boolean single = enabled && list.getSelectedIndices().length <= 1;
		int selected = selectedOrCurrent();
		undo.setEnabled(enabled && (current > 0 || restoreProblem));
		redo.setEnabled(enabled && current + 1 < model.size());
		go.setEnabled(single && selected >= 0 && (selected != current || restoreProblem));
		copy.setEnabled(single); keep.setEnabled(single && selected > 0);
		keep.setText(selected >= 0 && selected < model.size() && model.get(selected).pinned ? "Unkeep" : "Keep");
		delete.setEnabled(single && selected > 0);
		clear.setEnabled(enabled && model.size() > 1);
		macro.setEnabled(enabled && list.getSelectedIndices().length > 0);
		record.setEnabled(!busy); options.setEnabled(!busy); list.setEnabled(!busy);
	}

	private int selectedOrCurrent() {
		int k = list.getSelectedIndex();
		if (k >= 0) return k;
		for (int i = 0; i < model.size(); i++) if (model.get(i).current) return i;
		return -1;
	}

	private void refresh() {
		if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
		ImagePlus imp = WindowManager.getCurrentImage();
		Engine.View v = imp == null ? null : engine.view(imp.getID());
		int id = imp == null ? 0 : imp.getID();
		int stamp = v == null ? 0 : java.util.Objects.hash(id, v.title, v.untracked, v.rows.size(), v.dropped, rowsSignature(v));
		String mem = "Memory " + mb(engine.usedBytes()) + " / " + gb(engine.store.ramBudget)
				+ (engine.store.diskEnabled ? " · Disk " + mb(engine.diskBytes()) + " / " + gb(engine.store.diskBudget) : "");
		footer.setText(mem);
		footer.setToolTipText(engine.trackedImages() + " image(s) tracked"
				+ (engine.store.sessionDirPath() != null ? " · spill folder: " + engine.store.sessionDirPath() : ""));
		if (stamp == shownStamp && id == imageID) return;
		shownStamp = stamp;
		java.util.Set<Long> selectedKeys = new java.util.HashSet<>();
		if (imageID == id) for (Engine.Row row : list.getSelectedValuesList()) selectedKeys.add(row.key);
		imageID = id;
		restoreProblem = v != null && v.problem != null;
		model.clear();
		updateButtons();
		if (imp == null) { header.setText("No image open"); return; }
		if (!Engine.isRunning()) { header.setText("Not running — use Plugins ▸ Undo History ▸ Start Undo History"); return; }
		if (v == null) { header.setText(imp.getTitle() + ": not tracked"); return; }
		if (v.untracked != null) {
			header.setText("<html>" + esc(v.title) + "<br><span style='font-weight:normal'>Not tracked: " + esc(v.untracked) + "</span></html>");
			return;
		}
		String dropped = v.dropped > 0 ? " · " + v.dropped + " older step(s) dropped" : "";
		String problem = v.problem == null ? "" : "<br><span style='font-weight:normal;color:#b00020'>" + esc(v.problem) + "</span>";
		header.setText("<html>" + esc(v.title) + "<br><span style='font-weight:normal'>" + (v.rows.size() - 1)
				+ " step(s)" + dropped + "</span>" + problem + "</html>");
		int current = -1;
		for (int i = 0; i < v.rows.size(); i++) {
			model.addElement(v.rows.get(i));
			if (v.rows.get(i).current) current = i;
		}
		list.clearSelection();
		for (int i = 0; i < model.size(); i++) if (selectedKeys.contains(model.get(i).key)) list.addSelectionInterval(i, i);
		if (selectedKeys.isEmpty() && current >= 0) list.ensureIndexIsVisible(current);
		updateButtons();
	}

	private static int rowsSignature(Engine.View v) {
		int h = 1;
		for (Engine.Row r : v.rows)
			h = 31 * h + java.util.Objects.hash(r.key, r.label, r.time, r.current, r.pinned, r.saved, r.macroCode);
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
			String tags = (r.macroCode != null ? "  [macro]" : "") + (r.saved ? "  [saved]" : "") + (r.pinned && index > 0 && !r.saved ? "  [kept]" : "");
			setText(marker + index + "  " + r.label + tags + "   " + r.time);
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
