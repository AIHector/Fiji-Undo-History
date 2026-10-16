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
			keep = new JButton("Keep"), copy = new JButton("Copy"),
			clear = new JButton("Clear"), options = new JButton("Options…");
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
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new RowRenderer());
		list.setToolTipText("Click a step to bring the image back to it. Later steps stay available until you make a new change.");
		list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				int k = list.locationToIndex(e.getPoint());
				if (k < 0 || !list.getCellBounds(k, k).contains(e.getPoint())) return;
				final int id = imageID;
				run(() -> engine.goTo(id, k));
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
		undo.addActionListener(e -> run(engine::undoActive));
		redo.addActionListener(e -> run(engine::redoActive));
		keep.addActionListener(e -> engine.togglePin(imageID, selectedOrCurrent()));
		copy.addActionListener(e -> {
			final int id = imageID, k = selectedOrCurrent();
			run(() -> { ImagePlus c = engine.copyOf(id, k); if (c != null) c.show(); });
		});
		clear.addActionListener(e -> {
			if (IJ.showMessageWithCancel("Undo History", "Forget every step of this image except the current one?"))
				engine.clear(imageID);
		});
		options.addActionListener(e -> run(Undo_History::showOptions));

		JPanel buttons = new JPanel(new GridLayout(2, 3, 4, 4));
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
		for (JButton b : new JButton[] {undo, redo, keep, copy, clear, options}) buttons.add(b);
		JPanel south = new JPanel(new BorderLayout());
		south.add(buttons, BorderLayout.NORTH);
		south.add(footer, BorderLayout.SOUTH);

		getContentPane().add(header, BorderLayout.NORTH);
		getContentPane().add(new JScrollPane(list), BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);
		setSize(380, 460);
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
		int stamp = v == null ? 0 : java.util.Objects.hash(id, v.rows.size(), v.dropped, rowsSignature(v));
		String mem = "Memory " + mb(engine.usedBytes()) + " / " + gb(engine.store.ramBudget)
				+ (engine.store.diskEnabled ? " · Disk " + mb(engine.diskBytes()) + " / " + gb(engine.store.diskBudget) : "");
		footer.setText(mem);
		footer.setToolTipText(engine.trackedImages() + " image(s) tracked"
				+ (engine.store.sessionDirPath() != null ? " · spill folder: " + engine.store.sessionDirPath() : ""));
		if (stamp == shownStamp && id == imageID) return;
		shownStamp = stamp;
		imageID = id;
		model.clear();
		boolean enabled = v != null && v.untracked == null;
		for (JButton b : new JButton[] {undo, redo, keep, copy, clear}) b.setEnabled(enabled);
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
		if (current >= 0) list.ensureIndexIsVisible(current);
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
