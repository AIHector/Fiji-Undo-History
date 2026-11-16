package undohistory;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.*;

/** Small vector icons: scale with the display and do not depend on an installed symbol font. */
final class ActionIcon implements Icon {
    private final String kind;
    ActionIcon(String kind) { this.kind = kind; }
    public int getIconWidth() { return 18; }
    public int getIconHeight() { return 18; }
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(component.isEnabled() ? component.getForeground() : new Color(155, 155, 155));
        if (kind.equals("undo") || kind.equals("redo")) {
            if (kind.equals("redo")) { g.translate(18, 0); g.scale(-1, 1); }
            g.draw(new Arc2D.Double(4, 5, 11, 10, -30, 230, Arc2D.OPEN));
            g.drawPolyline(new int[]{3, 3, 8}, new int[]{3, 8, 8}, 3);
        } else if (kind.equals("restore")) {
            g.drawOval(3, 3, 12, 12); g.drawLine(9, 5, 9, 9); g.drawLine(9, 9, 6, 11);
        } else if (kind.equals("preview")) {
            g.draw(new Ellipse2D.Double(1, 5, 16, 8)); g.fillOval(7, 7, 4, 4);
        } else if (kind.equals("go")) {
            g.drawLine(2, 9, 14, 9); g.drawPolyline(new int[]{9,14,9}, new int[]{4,9,14}, 3); g.drawLine(16, 3, 16, 15);
        } else if (kind.equals("copy")) {
            g.drawRect(3, 3, 9, 10); g.drawRect(7, 7, 8, 9);
        } else if (kind.equals("keep")) {
            g.drawLine(4, 2, 4, 16); g.drawPolyline(new int[]{4,14,11,14,4}, new int[]{3,3,6,9,9}, 5);
        } else if (kind.equals("macro")) {
            g.drawPolyline(new int[]{6,3,6}, new int[]{4,9,14}, 3); g.drawPolyline(new int[]{12,15,12}, new int[]{4,9,14}, 3); g.drawLine(10,3,8,15);
        } else if (kind.equals("delete") || kind.equals("clear")) {
            g.drawLine(3,5,15,5); g.drawLine(7,2,11,2); g.drawRect(5,5,8,11); g.drawLine(8,8,8,13); g.drawLine(10,8,10,13);
        } else if (kind.equals("record")) {
            g.drawRoundRect(2,3,14,12,3,3); g.fillOval(6,6,6,6);
        } else {
            g.drawLine(3,4,15,4);g.drawLine(3,9,15,9);g.drawLine(3,14,15,14);
            g.fillRect(6,2,3,4);g.fillRect(11,7,3,4);g.fillRect(5,12,3,4);
        }
        g.dispose();
    }
}
