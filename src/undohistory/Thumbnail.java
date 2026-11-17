package undohistory;

import ij.ImagePlus;
import ij.Prefs;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Small session-only display cache, independent of full-resolution restore data. */
final class Thumbnail {
    static final int SIZE = 256;
    static boolean enabled() { return Prefs.get(Engine.PREF + "thumbnails", true); }
    static BufferedImage capture(ImagePlus image) {
        if (!enabled()) return null;
        Image source = image.getImage();
        int w = image.getWidth(), h = image.getHeight();
        double scale = Math.min(1.0, (double) SIZE / Math.max(w, h));
        BufferedImage result = new BufferedImage(Math.max(1,(int)Math.round(w*scale)), Math.max(1,(int)Math.round(h*scale)), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source,0,0,result.getWidth(),result.getHeight(),null);g.dispose();
        return result;
    }
    static BufferedImage fit(BufferedImage image, int size) {
        double factor = Math.min(1, (double)size/Math.max(image.getWidth(),image.getHeight()));
        BufferedImage result = new BufferedImage(Math.max(1,(int)Math.round(image.getWidth()*factor)),Math.max(1,(int)Math.round(image.getHeight()*factor)),BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=result.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image,0,0,result.getWidth(),result.getHeight(),null);g.dispose();return result;
    }
}
