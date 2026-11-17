package undohistory;

import ij.*;
import ij.process.*;
import ij.plugin.frame.Recorder;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Real recorder output and sorted-table actions in a separate ImageJ, without system input events. */
public final class TableFeaturesTest {
    static Engine engine;static int passed;
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);passed++;System.out.println("PASS "+text);}
    static void waitFor(BooleanSupplier test)throws Exception{for(int i=0;i<180;i++){if(test.getAsBoolean())return;Thread.sleep(40);}if(engine!=null&&WindowManager.getCurrentImage()!=null){Engine.View v=view(WindowManager.getCurrentImage());if(v!=null)for(Engine.Row r:v.rows)System.out.println("DEBUG "+r.label+" code="+r.macroCode);System.out.println("RECORDER "+Recorder.getInstance().getText());}throw new AssertionError("Timed out");}
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Engine.View view(ImagePlus image){return engine.view(image.getID());}
    static int rows(ImagePlus image){Engine.View v=view(image);return v==null?0:v.rows.size();}
    static JButton button(Container root,String label){for(Component c:root.getComponents()){if(c instanceof JButton&&label.equals(((JButton)c).getText()))return (JButton)c;if(c instanceof Container){JButton found=button((Container)c,label);if(found!=null)return found;}}return null;}
    static JMenuItem menu(JComponent root,String label){for(Component c:root.getComponents()){if(c instanceof JMenuItem&&label.equals(((JMenuItem)c).getText()))return (JMenuItem)c;if(c instanceof JMenu){JMenuItem found=menu(((JMenu)c).getPopupMenu(),label);if(found!=null)return found;}}return null;}
    static void screenshot(Component component,File path)throws Exception{final Component target=component instanceof JFrame?((JFrame)component).getContentPane():component;EventQueue.invokeAndWait(()->{BufferedImage image=new BufferedImage(target.getWidth(),target.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();target.paint(g);g.dispose();try{ImageIO.write(image,"png",path);}catch(IOException ex){throw new RuntimeException(ex);}});}
    public static void main(String[] args)throws Exception{
      try{
        new ImageJ(ImageJ.NO_SHOW);
        check(RecordingControl.enabled(),"macro recording is enabled with fresh preferences");
        Prefs.set(Engine.PREF+"testMemoryMB",64);Prefs.set(Engine.PREF+"useDisk",false);Prefs.set(Engine.PREF+"maxSteps",100);
        ByteProcessor bp=new ByteProcessor(120,80);for(int i=0;i<bp.getPixelCount();i++)bp.set(i,(i*23)%251);
        ImagePlus image=new ImagePlus("Table features test",bp);image.show();
        new Undo_History().run("start");engine=Engine.get();waitFor(()->Recorder.getInstance()!=null);
        check(!Recorder.getInstance().isVisible(),"default recorder starts in the background");
        waitFor(()->rows(image)==1);
        byte[] original=((byte[])image.getProcessor().getPixels()).clone();
        new Executer("Invert",image);waitFor(()->rows(image)==2);
        byte[] inverted=((byte[])image.getProcessor().getPixels()).clone();
        IJ.run(image,"Gaussian Blur...","sigma=1.25");waitFor(()->rows(image)==3);
        check(view(image).rows.get(2).macroCode.contains("sigma=1.25"),"exact numeric command options reach history");
        // Native Recorder receives these instructions even though there is no image update or menu command.
        int before=rows(image);long bytes=engine.usedBytes();
        Recorder.recordString("setOption(\"BlackBackground\", true);\nsetForegroundColor(12, 34, 56);\n//setTool(\"oval\");\n");
        waitFor(()->rows(image)==before+3);
        check(view(image).rows.get(before).shared&&view(image).rows.get(before+1).macroCode.equals("setForegroundColor(12, 34, 56);\n"),"each shared instruction has its own context row and exact code");
        check(view(image).rows.get(before+2).macroCode.startsWith("//setTool"),"recorder comments are preserved without inventing executable commands");
        check(engine.usedBytes()==bytes,"recorder-only rows share image planes instead of duplicating them");
        Thread.sleep(800);check(rows(image)==before+3,"recorder instructions are not duplicated by polling");
        int n=rows(image);new Executer("Copy",image);waitFor(()->rows(image)==n+1);
        check(view(image).rows.get(n).recorderOnly&&view(image).rows.get(n).macroCode.contains("Copy"),"a menu command with unchanged image state is retained");
        EventQueue.invokeAndWait(HistoryWindow::showWindow);HistoryWindow found=null;for(Window w:Window.getWindows())if(w instanceof HistoryWindow)found=(HistoryWindow)w;
        final HistoryWindow history=found;HistoryTable table=(HistoryTable)field(history,"list");HistoryToolbar toolbar=(HistoryToolbar)field(history,"toolbar");JButton go=(JButton)field(history,"go");
        waitFor(()->table.getRowCount()==rows(image));
        screenshot(history,new File(args[0],"history-table-default.png"));
        check(table.getColumnCount()==6&&table.getColumnName(0).equals("Number")&&table.getColumnName(5).equals("Dimension"),"all six requested columns appear");
        check(!((TableRowSorter<?>)table.getRowSorter()).isSortable(3),"Preview is movable but cannot be sorted");
        for(int column:new int[]{0,1,2,4,5}){final int c=column;EventQueue.invokeAndWait(()->table.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(c,SortOrder.ASCENDING))));EventQueue.invokeAndWait(()->table.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(c,SortOrder.DESCENDING))));}
        check(true,"all other columns support ascending and descending sorting");
        EventQueue.invokeAndWait(()->table.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(0,SortOrder.DESCENDING))));
        check(table.convertRowIndexToModel(0)==table.getRowCount()-1,"descending number sort changes the visible order");
        EventQueue.invokeAndWait(()->table.setSelectedIndex(1));long selected=view(image).rows.get(1).key;
        EventQueue.invokeAndWait(go::doClick);waitFor(()->engine.cursorOf(image.getID())==1);
        check(Arrays.equals(inverted,(byte[])image.getProcessor().getPixels()),"Go to step restores the correct snapshot after sorting");
        EventQueue.invokeAndWait(()->table.setSelectedIndices(new int[]{1,2}));MacroExport exported=engine.exportMacro(image.getID(),new long[]{view(image).rows.get(2).key,selected});
        check(exported.code.indexOf("run(\"Invert")<exported.code.indexOf("run(\"Gaussian Blur"),"macro export remains chronological despite descending display and selection order");
        EventQueue.invokeAndWait(()->table.moveColumn(3,0));
        check(table.convertColumnIndexToModel(0)==3,"Preview can be dragged to another column position");
        EventQueue.invokeAndWait(()->menu(table.columnsMenu().getPopupMenu(),"Macro").doClick());
        check(table.getColumnCount()==5,"column menu can hide Macro");
        EventQueue.invokeAndWait(()->menu(table.columnsMenu().getPopupMenu(),"Macro").doClick());
        check(table.getColumnCount()==6,"column menu can restore Macro");
        int cursor=engine.cursorOf(image.getID()),count=rows(image),open=WindowManager.getImageCount();
        BufferedImage thumbnail=engine.thumbnailOf(image.getID(),selected);
        check(thumbnail!=null&&thumbnail.getWidth()<=256&&thumbnail.getHeight()<=256,"default per-step preview cache is bounded");
        int v=table.convertRowIndexToView(1),c=table.convertColumnIndexToView(3);Rectangle rect=table.getCellRect(v,c,true);
        EventQueue.invokeAndWait(()->table.dispatchEvent(new MouseEvent(table,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,rect.x+8,rect.y+8,0,false)));
        waitFor(()->{try{return field(table,"hoverPopup")!=null;}catch(Exception ex){return false;}});
        check(engine.cursorOf(image.getID())==cursor&&WindowManager.getImageCount()==open,"hover opens an enlarged preview without restoring or opening an image");
        EventQueue.invokeAndWait(()->table.dispatchEvent(new MouseEvent(table,MouseEvent.MOUSE_EXITED,System.currentTimeMillis(),0,0,0,0,false)));
        Path output=Paths.get(args[0]);Files.createDirectories(output);
        for(String format:new String[]{"TIFF","PNG","JPEG","BMP","GIF","ICO"}){
            ImagePlus detached=engine.snapshotOf(image.getID(),selected);File path=output.resolve("export-step."+format.toLowerCase(Locale.ROOT)).toFile();SnapshotExport.save(detached,path,format,64);detached.flush();
            check(path.length()>0,format+" image export writes a file");
            if(format.equals("TIFF")){ImagePlus restored=IJ.openImage(path.toString());check(restored.getBitDepth()==8&&Arrays.equals(inverted,(byte[])restored.getProcessor().getPixels()),"TIFF export retains the selected historical pixels");restored.flush();}
            if(format.equals("ICO")){byte[] data=Files.readAllBytes(path.toPath());int offset=(data[18]&255)|((data[19]&255)<<8);BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(data,offset,data.length-offset));check(data[2]==1&&data[4]==1&&decoded.getWidth()==64&&decoded.getHeight()==64,"ICO has a valid image directory and decodable 64-pixel image");}
        }
        check(engine.cursorOf(image.getID())==cursor&&rows(image)==count&&Arrays.equals(inverted,(byte[])image.getProcessor().getPixels()),"all exports leave live pixels, history and cursor unchanged");
        ImageStack dataStack=new ImageStack(3,2);short[] one={0,1000,2000,3000,4000,(short)65000},two={1,2,3,4,5,6};dataStack.addSlice(new ShortProcessor(3,2,one.clone(),null));dataStack.addSlice(new ShortProcessor(3,2,two.clone(),null));
        ImagePlus scientific=new ImagePlus("16-bit export test",dataStack);File dataFile=output.resolve("export-16bit-stack.tif").toFile();SnapshotExport.save(scientific,dataFile,"TIFF",0);ImagePlus loaded=IJ.openImage(dataFile.toString());
        check(loaded.getBitDepth()==16&&loaded.getStackSize()==2&&Arrays.equals(one,(short[])loaded.getStack().getPixels(1))&&Arrays.equals(two,(short[])loaded.getStack().getPixels(2)),"TIFF export preserves all 16-bit values and stack planes");loaded.flush();scientific.flush();

        engine.setThumbnails(false);EventQueue.invokeAndWait(table::thumbnailsChanged);
        check(engine.thumbnailOf(image.getID(),selected)==null&&view(image).rows.get(1).thumbnail==null&&table.getRowHeight()==26,"turning off previews releases cached images and reduces row height");
        engine.setThumbnails(true);EventQueue.invokeAndWait(table::thumbnailsChanged);
        check(engine.thumbnailOf(image.getID(),selected)!=null&&table.getRowHeight()==52,"previews can be regenerated from retained snapshots");
        engine.goTo(image.getID(),rows(image)-1);Thread.sleep(400);
        EventQueue.invokeAndWait(()->{history.setSize(640,600);history.validate();});
        JButton more=(JButton)field(toolbar,"more");
        check(more.isVisible()&&toolbar.getHeight()<70,"narrow windows retain one toolbar row and the >> overflow button");
        EventQueue.invokeAndWait(more::doClick);check(MenuSelectionManager.defaultManager().getSelectedPath().length>0,"overflow opens its action menu");EventQueue.invokeAndWait(()->MenuSelectionManager.defaultManager().clearSelectedPath());
        EventQueue.invokeAndWait(toolbar::customize);JDialog custom=null;for(Window w:Window.getWindows())if(w instanceof JDialog&&"Customize Undo History toolbar".equals(((JDialog)w).getTitle()))custom=(JDialog)w;
        final JDialog dialog=custom;
        EventQueue.invokeAndWait(()->button(dialog,"Insert separator").doClick());check(Prefs.get(Engine.PREF+"toolbarOrder","").endsWith("|"),"customization inserts a persistent separator");
        JList<?> sequence=null;for(Component child:((JPanel)dialog.getContentPane().getComponent(0)).getComponents())if(child instanceof JScrollPane)sequence=(JList<?>)((JScrollPane)child).getViewport().getView();final JList<?> items=sequence;
        EventQueue.invokeAndWait(()->{items.setSelectedIndex(items.getModel().getSize()-1);button(dialog,"Hide / remove").doClick();});
        check(!Prefs.get(Engine.PREF+"toolbarOrder","").endsWith("|"),"customization removes a separator");
        EventQueue.invokeAndWait(()->{items.setSelectedIndex(0);button(dialog,"Move right / down").doClick();});
        check(Prefs.get(Engine.PREF+"toolbarOrder","").startsWith("redo,undo"),"toolbar actions can be reordered persistently");
        EventQueue.invokeAndWait(()->{items.setSelectedIndex(0);button(dialog,"Hide / remove").doClick();});
        check(!Prefs.get(Engine.PREF+"toolbarOrder","").contains("redo"),"toolbar actions can be hidden");
        EventQueue.invokeAndWait(()->button(dialog,"Done").doClick());
        screenshot(history,output.resolve("table-toolbar-candidate.png").toFile());
        EventQueue.invokeAndWait(history::dispose);EventQueue.invokeAndWait(HistoryWindow::showWindow);
        HistoryWindow reopened=null;for(Window w:Window.getWindows())if(w instanceof HistoryWindow&&w.isVisible())reopened=(HistoryWindow)w;HistoryTable table2=(HistoryTable)field(reopened,"list");HistoryToolbar toolbar2=(HistoryToolbar)field(reopened,"toolbar");
        check(table2.convertColumnIndexToModel(0)==3&&table2.getRowSorter().getSortKeys().get(0).getSortOrder()==SortOrder.DESCENDING,"column order and sort direction survive reopening");
        check(((JButton)field(toolbar2,"more")).isVisible()&&!((java.util.List<?>)field(toolbar2,"order")).contains("redo"),"hidden toolbar actions survive reopening and customization remains accessible");
        check(MacroCapture.instructions("x=newArray(1,2);\nprint(\"a;b\");\n").size()==2,"instruction splitting preserves quoted semicolons and supporting definitions");
        image.changes=false;image.close();System.out.println("RESULT: "+passed+" table and recorder checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
