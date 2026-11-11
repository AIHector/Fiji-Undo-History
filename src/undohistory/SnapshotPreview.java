package undohistory;

import ij.ImagePlus;
import ij.plugin.frame.Recorder;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Detached display only: no ImageWindow, history movement, shared-table restoration or new tracked image. */
final class SnapshotPreview {
    /** Render only a detached snapshot. Its selection is copied into its private overlay for display. */
    static BufferedImage render(ImagePlus image) {
        if (image.getRoi() != null) {
            ij.gui.Overlay overlay = image.getOverlay() == null ? new ij.gui.Overlay() : image.getOverlay().duplicate();
            overlay.add((ij.gui.Roi)image.getRoi().clone()); image.deleteRoi(); image.setOverlay(overlay);
        }
        ImagePlus flattened = image.flatten();
        BufferedImage source = flattened.getBufferedImage();
        BufferedImage rendered = new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rendered.createGraphics(); graphics.drawImage(source,0,0,null); graphics.dispose();
        flattened.flush(); return rendered;
    }

    static void show(ImagePlus image, String name) {
        SwingUtilities.invokeLater(() -> new PreviewFrame(image,name).setVisible(true));
    }

    private static final class PreviewFrame extends JFrame {
        private final ImagePlus snapshot;
        private final JLabel view = new JLabel("Preparing preview…",SwingConstants.CENTER);
        private final JLabel status = new JLabel("Preview only — the working image is unchanged.");
        private final JSpinner channel, slice, time;
        private final ExecutorService renderer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task,"UndoHistory-preview"); thread.setDaemon(true); return thread;
        });
        private final AtomicLong generation = new AtomicLong();
        private volatile boolean closed;
        private BufferedImage displayed;

        PreviewFrame(ImagePlus image,String name) {
            super("History preview — "+name);snapshot=image;setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            channel=spinner(image.getNChannels(),"preview-channel");slice=spinner(image.getNSlices(),"preview-slice");time=spinner(image.getNFrames(),"preview-time");
            JPanel navigation=new JPanel(new FlowLayout(FlowLayout.LEFT));
            addNavigation(navigation,"Channel",channel,image.getNChannels());
            addNavigation(navigation,"Slice",slice,image.getNSlices());
            addNavigation(navigation,"Frame",time,image.getNFrames());
            if(navigation.getComponentCount()>0)add(navigation,BorderLayout.NORTH);
            view.setVerticalAlignment(SwingConstants.TOP);add(new JScrollPane(view),BorderLayout.CENTER);
            status.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));add(status,BorderLayout.SOUTH);
            setSize(Math.min(860,Math.max(480,image.getWidth()+40)),Math.min(700,Math.max(300,image.getHeight()+120)));
            setMinimumSize(new Dimension(480,300));setLocationByPlatform(true);
            addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosed(java.awt.event.WindowEvent event){
                closed=true;generation.incrementAndGet();if(displayed!=null)displayed.flush();
                // Wait for any in-flight rendering before releasing the private image arrays.
                renderer.execute(snapshot::flush);renderer.shutdown();
            }});
            requestRender();
        }
        private JSpinner spinner(int maximum,String name){
            JSpinner spinner=new JSpinner(new SpinnerNumberModel(1,1,Math.max(1,maximum),1));spinner.setName(name);
            spinner.addChangeListener(event -> requestRender());return spinner;
        }
        private void addNavigation(JPanel panel,String name,JSpinner spinner,int count){
            if(count>1){panel.add(new JLabel(name+":"));panel.add(spinner);panel.add(new JLabel("of "+count));}
        }
        private void requestRender(){
            if(closed||channel==null||slice==null||time==null)return;
            long request=generation.incrementAndGet();int c=(Integer)channel.getValue(),z=(Integer)slice.getValue(),t=(Integer)time.getValue();
            renderer.execute(() -> {
                if(closed||request!=generation.get())return;
                BufferedImage image;
                Recorder.suspendRecording();
                try {snapshot.setPosition(c,z,t);snapshot.updateImage();image=render(snapshot);}
                catch(RuntimeException | OutOfMemoryError error){SwingUtilities.invokeLater(() -> {if(!closed&&request==generation.get())status.setText("Could not render preview: "+error.getMessage());});return;}
                finally {Recorder.resumeRecording();}
                SwingUtilities.invokeLater(() -> {
                    if(closed||request!=generation.get()){image.flush();return;}
                    BufferedImage old=displayed;displayed=image;view.setText(null);view.setIcon(new ImageIcon(image));if(old!=null)old.flush();
                    status.setText("Preview only · Channel "+c+", slice "+z+", frame "+t+" · Working image unchanged");
                });
            });
        }
    }
}
