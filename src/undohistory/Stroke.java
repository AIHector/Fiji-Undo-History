package undohistory;

import ij.Prefs;
import ij.gui.ImageCanvas;
import ij.gui.Toolbar;

import java.awt.Color;
import java.awt.event.MouseEvent;

/**
 * A paint stroke written as macro code, since ImageJ's Recorder records none. A flood fill becomes
 * {@code floodFill(x, y)}, which repeats it exactly. A brush or pencil stroke becomes a line of the tool's width
 * drawn along the mouse path, which is close to the original but not identical (ImageJ's brush is round).
 */
final class Stroke {

	final ImageCanvas canvas;
	final boolean fill, approximate;
	private final int width;
	private final Color color;
	private final StringBuilder xs = new StringBuilder(), ys = new StringBuilder();
	private int points, lastX = Integer.MIN_VALUE, lastY;

	private Stroke(ImageCanvas canvas, boolean fill, int width) {
		this.canvas = canvas;
		this.fill = fill;
		this.approximate = !fill;
		this.width = width;
		this.color = Toolbar.getForegroundColor();
	}

	/** A stroke for the painting tools, or null for any other tool. */
	static Stroke begin(String tool, ImageCanvas c, MouseEvent e) {
		if (tool == null || e.isAltDown() || e.isPopupTrigger() || e.getButton() != MouseEvent.BUTTON1) return null;
		String t = tool.toLowerCase();
		Stroke s;
		if (t.contains("flood")) s = new Stroke(c, true, 1);
		else if (t.contains("brush")) s = new Stroke(c, false, (int) Prefs.get("brush.width", 5));
		else if (t.contains("pencil")) s = new Stroke(c, false, (int) Prefs.get("pencil.width", 1));
		else return null;
		s.add(e);
		return s;
	}

	void add(MouseEvent e) {
		int x = canvas.offScreenX(e.getX()), y = canvas.offScreenY(e.getY());
		if (x == lastX && y == lastY) return;
		if (fill && points > 0) return;
		xs.append(points > 0 ? "," : "").append(x);
		ys.append(points > 0 ? "," : "").append(y);
		lastX = x;
		lastY = y;
		points++;
	}

	String code() {
		String rgb = "setForegroundColor(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ");\n";
		if (fill) return rgb + "floodFill(" + xs + ", " + ys + ");\n";
		String px = points == 1 ? xs + "," + xs : xs.toString(), py = points == 1 ? ys + "," + ys : ys.toString();
		return "// paint stroke, drawn as a line: close to the original, not identical\n" + rgb
				+ "setLineWidth(" + width + ");\n"
				+ "makeSelection(\"polyline\", newArray(" + px + "), newArray(" + py + "));\n"
				+ "run(\"Draw\", \"slice\");\n"
				+ "run(\"Select None\");\n";
	}
}
