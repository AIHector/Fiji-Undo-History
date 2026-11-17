package undohistory;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.plugin.frame.Recorder;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Detached exports never restore the working image. TIFF preserves full data; display formats render one plane. */
final class SnapshotExport {
    static void addFormats(JMenu menu,Component parent,Engine engine,int id,long key){
        for(String format:new String[]{"TIFF (full image / stack)","PNG (rendered plane)","JPEG (rendered plane)","BMP (rendered plane)","GIF (rendered plane)"}){
            JMenuItem item=new JMenuItem(format+"…");item.addActionListener(e->choose(parent,engine,id,key,format.split(" ")[0],0));menu.add(item);
        }
        JMenu icons=new JMenu("Icon (.ico)");menu.add(icons);for(int size:new int[]{32,64,128,256}){JMenuItem item=new JMenuItem(size+" × "+size+"…");item.addActionListener(e->choose(parent,engine,id,key,"ICO",size));icons.add(item);}
    }
    private static void choose(Component parent,Engine engine,int id,long key,String format,int iconSize){
        String extension=format.equals("TIFF")?"tif":format.equals("JPEG")?"jpg":format.toLowerCase(Locale.ROOT);
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Export retained image state — "+format);chooser.setSelectedFile(new File("history-step."+extension));
        if(chooser.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return;File chosen=chooser.getSelectedFile();
        if(!chosen.getName().toLowerCase(Locale.ROOT).endsWith("."+extension))chosen=new File(chosen.getParentFile(),chosen.getName()+"."+extension);
        if(chosen.exists()&&JOptionPane.showConfirmDialog(parent,"Replace "+chosen.getName()+"?","Export image",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        final File destination=chosen;
        new Thread(()->{
            Recorder.suspendRecording();ImagePlus detached=null;
            try{detached=engine.snapshotOf(id,key);save(detached,destination,format,iconSize);SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(parent,"Exported "+destination.getAbsolutePath(),"Export image",JOptionPane.INFORMATION_MESSAGE));}
            catch(Exception|OutOfMemoryError ex){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(parent,"Could not export image: "+ex.getMessage(),"Export image",JOptionPane.ERROR_MESSAGE));}
            finally{if(detached!=null)detached.flush();Recorder.resumeRecording();}
        },"UndoHistory-export-image").start();
    }
    static void save(ImagePlus image,File destination,String format,int iconSize)throws IOException{
        Path target=destination.toPath().toAbsolutePath(),temporary=Files.createTempFile(target.getParent(),".history-export-","."+format.toLowerCase(Locale.ROOT));
        try{
            if(format.equals("TIFF")){
                FileSaver saver=new FileSaver(image);boolean success=image.getStackSize()>1?saver.saveAsTiffStack(temporary.toString()):saver.saveAsTiff(temporary.toString());
                if(!success)throw new IOException("TIFF writer failed");
            }else{
                BufferedImage rendered=SnapshotPreview.render(image);
                try{if(format.equals("ICO"))writeIcon(rendered,temporary,iconSize);else if(!ImageIO.write(rendered,format,temporary.toFile()))throw new IOException("No writer for "+format);}
                finally{rendered.flush();}
            }
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    /** Standard ICO container with one PNG-encoded square image and transparent letterboxing. */
    static void writeIcon(BufferedImage source,Path path,int size)throws IOException{
        if(size!=32&&size!=64&&size!=128&&size!=256)throw new IOException("Unsupported icon size");
        BufferedImage canvas=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB),fit=Thumbnail.fit(source,size);Graphics2D g=canvas.createGraphics();g.drawImage(fit,(size-fit.getWidth())/2,(size-fit.getHeight())/2,null);g.dispose();fit.flush();
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(canvas,"png",png);canvas.flush();byte[] data=png.toByteArray();
        try(OutputStream out=Files.newOutputStream(path)){
            little(out,0,2);little(out,1,2);little(out,1,2);out.write(size==256?0:size);out.write(size==256?0:size);out.write(0);out.write(0);
            little(out,1,2);little(out,32,2);little(out,data.length,4);little(out,22,4);out.write(data);
        }
    }
    private static void little(OutputStream out,int value,int bytes)throws IOException{for(int i=0;i<bytes;i++)out.write((value>>>(8*i))&255);}
}
