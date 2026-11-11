package undohistory;

import ij.*;
import ij.process.ByteProcessor;
import ij.plugin.frame.Recorder;
import ij.plugin.filter.Analyzer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Behavioral tests for recording, range actions, context selection, preview and atomic batch deletion. */
public final class HistoryControlsTest {
    static Engine engine; static int passed;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
    static void waitFor(BooleanSupplier test)throws Exception{for(int i=0;i<150;i++){if(test.getAsBoolean())return;Thread.sleep(40);}throw new AssertionError("Timed out");}
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Engine.View view(ImagePlus image){return engine.view(image.getID());}
    static int rows(ImagePlus image){Engine.View v=view(image);return v==null?0:v.rows.size();}
    static byte[] pixels(ImagePlus image){return ((byte[])image.getProcessor().getPixels()).clone();}
    static void menu(ImagePlus image,String command,int expected)throws Exception{
        WindowManager.setTempCurrentImage(null);WindowManager.setCurrentWindow(image.getWindow());new Executer(command,image);
        try{waitFor(()->rows(image)==expected);}catch(AssertionError ex){
            System.out.println("Expected "+expected+" rows after "+command+", got "+rows(image));
            for(Engine.Row row:view(image).rows)System.out.println(row.label+" current="+row.current);
            throw ex;
        }
    }
    static void popup(JList<?> list,int index)throws Exception{
        EventQueue.invokeAndWait(()->{Rectangle r=list.getCellBounds(index,index);list.dispatchEvent(new MouseEvent(list,MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),InputEvent.BUTTON3_DOWN_MASK,12,r.y+r.height/2,1,true,MouseEvent.BUTTON3));
            list.dispatchEvent(new MouseEvent(list,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis()+1,0,
                    12,r.y+r.height/2,1,false,MouseEvent.BUTTON3));});
    }
    static JButton button(Container parent,String label){for(Component child:parent.getComponents()){
        if(child instanceof JButton&&label.equals(((JButton)child).getText()))return (JButton)child;
        if(child instanceof Container){JButton found=button((Container)child,label);if(found!=null)return found;}
    }return null;}
    static boolean menuContains(JComponent menu,String label){for(Component item:menu.getComponents()){
        if(item instanceof JMenu){if(menuContains(((JMenu)item).getPopupMenu(),label))return true;}
        if(item instanceof JMenuItem&&((JMenuItem)item).getText().equals(label))return true;
    }return false;}
    public static void main(String[] args)throws Exception{
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set(Engine.PREF+"recordMacro",false);Prefs.set(Engine.PREF+"testMemoryMB",64);
        Prefs.set(Engine.PREF+"useDisk",false);Prefs.set(Engine.PREF+"maxSteps",100);new Undo_History().run("start");engine=Engine.get();
        ByteProcessor source=new ByteProcessor(96,96);for(int i=0;i<96*96;i++)source.set(i,(i*37+i/96)%251);
        ImagePlus image=new ImagePlus("History controls test",source);image.show();waitFor(()->rows(image)==1);
        byte[] baseline=pixels(image);long baselineKey=view(image).rows.get(0).key;
        EventQueue.invokeAndWait(HistoryWindow::showWindow);HistoryWindow panel=null;
        for(Window window:Window.getWindows())if(window instanceof HistoryWindow)panel=(HistoryWindow)window;
        final HistoryWindow history=panel;
        JCheckBox recording=(JCheckBox)field(history,"recordMacro");JList<?> list=(JList<?>)field(history,"list");
        JButton preview=(JButton)field(history,"preview"),restore=(JButton)field(history,"go"),delete=(JButton)field(history,"delete");
        check(!recording.isSelected(),"recording checkbox starts off");
        EventQueue.invokeAndWait(recording::doClick);
        check(RecordingControl.enabled()&&Recorder.getInstance()!=null&&!Recorder.getInstance().isVisible(),"checkbox starts background Macro Recorder");
        menu(image,"Invert",2);long first=view(image).rows.get(1).key;String captured=view(image).rows.get(1).macroCode;byte[] inverted=pixels(image);
        check(captured!=null&&captured.contains("Invert"),"checked recording captures native menu instruction");
        EventQueue.invokeAndWait(recording::doClick);
        check(!RecordingControl.enabled()&&Recorder.getInstance()==null,"unchecking stops the owned background recorder");
        IJ.run(image,"Gaussian Blur...","sigma=1.75");waitFor(()->rows(image)==3);
        check(view(image).rows.get(2).macroCode==null,"unchecked recording also excludes explicit macro option capture");
        check(captured.equals(view(image).rows.get(1).macroCode),"stopping recording preserves prior code");
        EventQueue.invokeAndWait(recording::doClick);menu(image,"Find Edges",4);menu(image,"Sharpen",5);
        Engine.View initial=view(image);long excluded=initial.rows.get(2).key,anchor=initial.rows.get(3).key,last=initial.rows.get(4).key;
        byte[] sharpened=pixels(image);
        engine.annotate(image.getID(),excluded,"Background cleanup","Chosen after inspection\nKeep this note as a comment.");
        engine.setIncluded(image.getID(),new long[]{excluded},false);engine.rowAction(image.getID(),anchor,"keep");
        Engine.Row annotated=view(image).rows.get(2);
        check(annotated.label.equals("Background cleanup")&&annotated.originalLabel.equals("Gaussian Blur")&&!annotated.included&&!annotated.note.isEmpty(),"names and notes retain original operation and exclusion state");
        check(HistoryWindow.details(annotated).contains("sigma=")==false&&HistoryWindow.details(annotated).contains("Original operation: Gaussian Blur"),"step details preserve honest missing-code provenance");
        check(Arrays.equals(engine.rangeKeys(image.getID(),anchor,"through"),new long[]{first,excluded,anchor}),"through range includes anchor and excludes baseline");
        check(Arrays.equals(engine.rangeKeys(image.getID(),anchor,"after"),new long[]{last}),"after range excludes anchor");
        check(Arrays.equals(engine.rangeKeys(image.getID(),anchor,"before"),new long[]{first,excluded}),"before range excludes anchor and baseline");
        MacroExport recipe=engine.exportMacro(image.getID(),engine.rangeKeys(image.getID(),anchor,"through"));
        check(recipe.missing==0&&recipe.excluded==1&&recipe.code.contains("[excluded] Background cleanup")&&recipe.code.contains("// Keep this note"),"range recipe keeps excluded missing-code step as comments without a false draft stop");
        check(view(image).rows.get(3).pinned,"named checkpoint protection is retained");
        menu(image,"Measure",6);int cursor=engine.cursorOf(image.getID()),results=Analyzer.getResultsTable().size();byte[] working=pixels(image);
        int tracked=engine.trackedImages(),open=WindowManager.getImageCount();
        ImagePlus detached=engine.snapshotOf(image.getID(),baselineKey);BufferedImage rendered=SnapshotPreview.render(detached);detached.flush();rendered.flush();
        check(engine.cursorOf(image.getID())==cursor&&Arrays.equals(working,pixels(image))&&Analyzer.getResultsTable().size()==results,
                "preview construction leaves pixels, cursor and shared Results unchanged");
        check(engine.trackedImages()==tracked&&WindowManager.getImageCount()==open,"detached preview does not add a tracked image");
        waitFor(()->list.getModel().getSize()==6);EventQueue.invokeAndWait(()->list.setSelectedIndex(1));EventQueue.invokeAndWait(preview::doClick);
        final JFrame[] previewFrame={null};waitFor(()->{for(Window window:Window.getWindows())if(window instanceof JFrame&&String.valueOf(((JFrame)window).getTitle()).startsWith("History preview"))previewFrame[0]=(JFrame)window;return previewFrame[0]!=null;});
        check(engine.cursorOf(image.getID())==cursor&&Arrays.equals(working,pixels(image)),"Preview button shows snapshot without restoration");EventQueue.invokeAndWait(()->previewFrame[0].dispose());
        waitFor(restore::isEnabled);EventQueue.invokeAndWait(restore::doClick);waitFor(()->engine.cursorOf(image.getID())==1);
        check(Arrays.equals(inverted,pixels(image)),"Restore button explicitly restores selected pixels");engine.goTo(image.getID(),cursor);
        Thread.sleep(500);EventQueue.invokeAndWait(()->list.setSelectedIndices(new int[]{1,3}));popup(list,3);
        JPopupMenu popup=(JPopupMenu)field(history,"lastPopup");
        check(Arrays.equals(list.getSelectedIndices(),new int[]{1,3}),"right-click inside selection preserves multiple rows");
        check(menuContains(popup,"Through this step (inclusive)…")&&menuContains(popup,"Delete selected steps…")&&menuContains(popup,"Add or edit note…"),"context menu exposes ranges, batch deletion and notes");
        EventQueue.invokeAndWait(()->popup.setVisible(false));popup(list,2);
        check(Arrays.equals(list.getSelectedIndices(),new int[]{2})&&engine.cursorOf(image.getID())==cursor,"right-click outside selection selects clicked row without restoring it");
        JPopupMenu second=(JPopupMenu)field(history,"lastPopup");
        EventQueue.invokeAndWait(()->{
            BufferedImage screenshot=new BufferedImage(history.getWidth(),history.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=screenshot.createGraphics();history.paint(g);g.dispose();
            try{ImageIO.write(screenshot,"png",new File(args[0],"history-controls.png"));}catch(Exception ex){throw new RuntimeException(ex);}
            BufferedImage menuImage=new BufferedImage(second.getWidth(),second.getHeight(),BufferedImage.TYPE_INT_RGB);g=menuImage.createGraphics();second.paint(g);g.dispose();
            try{ImageIO.write(menuImage,"png",new File(args[0],"history-context-menu.png"));}catch(Exception ex){throw new RuntimeException(ex);}
            second.setVisible(false);list.setSelectedIndices(new int[]{0,2,3});
        });
        check(delete.isEnabled()&&delete.getText().contains("2"),"batch Delete counts operations and protects selected baseline");
        Engine.DeletionPlan plan=engine.planDeletion(image.getID(),new long[]{baselineKey,excluded,anchor});
        check(plan.count==2&&plan.checkpoints==1&&!plan.current,"deletion summary reports checkpoint and actual affected restore points");
        long memory=engine.usedBytes();check(engine.deletePlanned(image.getID(),plan)==2,"planned batch deletion removes two noncurrent states atomically");
        check(Arrays.equals(working,pixels(image))&&rows(image)==4&&view(image).rows.get(0).key==baselineKey,"noncurrent deletion preserves image and baseline");
        check(engine.usedBytes()<memory,"batch deletion releases unshared snapshot storage");
        int count=rows(image);boolean stale=false;try{engine.deleteMany(image.getID(),new long[]{first,anchor});}catch(IllegalArgumentException ex){stale=true;}
        check(stale&&rows(image)==count,"stale batch selection is rejected without partial deletion");
        menu(image,"Invert",5);Engine.View before=view(image);long measurement=before.rows.get(3).key,newCurrent=before.rows.get(4).key;
        check(engine.deleteMany(image.getID(),new long[]{first,measurement,newCurrent})==3,"batch deletion can include current state and separated earlier states");
        check(rows(image)==2&&view(image).rows.get(1).key==last&&engine.cursorOf(image.getID())==1&&Arrays.equals(sharpened,pixels(image)),"current deletion restores nearest earlier retained state");
        Engine.DeletionPlan stalePlan=engine.planDeletion(image.getID(),new long[]{last});engine.goTo(image.getID(),0);
        boolean changed=false;try{engine.deletePlanned(image.getID(),stalePlan);}catch(IllegalArgumentException ex){changed=true;}
        check(changed&&rows(image)==2&&Arrays.equals(baseline,pixels(image)),"changed current state invalidates an approved deletion plan");
        check(!engine.delete(image.getID(),0),"baseline remains undeletable");
        Thread.sleep(500);EventQueue.invokeAndWait(()->list.setSelectedIndex(1));EventQueue.invokeAndWait(delete::doClick);
        final JDialog[] confirmation={null};waitFor(()->{for(Window window:Window.getWindows())if(window instanceof JDialog&&"Delete history steps".equals(((JDialog)window).getTitle())&&window.isVisible())confirmation[0]=(JDialog)window;return confirmation[0]!=null;});
        EventQueue.invokeAndWait(()->button(confirmation[0],"Cancel").doClick());Thread.sleep(250);
        check(rows(image)==2&&engine.cursorOf(image.getID())==0&&Arrays.equals(baseline,pixels(image)),"cancelling batch confirmation changes neither history nor pixels");
        engine.goTo(image.getID(),1);EventQueue.invokeAndWait(()->{});
        image.setRoi(new ij.gui.Roi(2,2,15,15));waitFor(()->rows(image)==3);
        long selection=view(image).rows.get(2).key;engine.annotate(image.getID(),selection,null,"Retain this selection note");
        image.setRoi(new ij.gui.Roi(4,4,18,18));waitFor(()->rows(image)==4);
        check(view(image).rows.get(2).key==selection&&view(image).rows.get(2).note.contains("Retain"),"annotated selection is not overwritten by automatic coalescing");
        long excludedSelection=view(image).rows.get(3).key;engine.setIncluded(image.getID(),new long[]{excludedSelection},false);
        image.setRoi(new ij.gui.Roi(6,6,21,21));waitFor(()->rows(image)==5);
        check(view(image).rows.get(3).key==excludedSelection&&!view(image).rows.get(3).included,"excluded selection remains a distinct recoverable step");
        ij.ImageStack planes=new ij.ImageStack(32,32);
        for(int value:new int[]{10,220}){ByteProcessor plane=new ByteProcessor(32,32);plane.setValue(value);plane.fill();planes.addSlice(plane);}
        ImagePlus stackPreview=new ImagePlus("Detached stack",planes);stackPreview.setDimensions(1,2,1);
        SnapshotPreview.show(stackPreview,"Stack navigation test");
        final JFrame[] stackFrame={null};waitFor(()->{for(Window window:Window.getWindows())if(window instanceof JFrame&&String.valueOf(((JFrame)window).getTitle()).contains("Stack navigation test"))stackFrame[0]=(JFrame)window;return stackFrame[0]!=null;});
        JLabel stackView=(JLabel)field(stackFrame[0],"view");JSpinner slice=(JSpinner)field(stackFrame[0],"slice");
        waitFor(()->stackView.getIcon()!=null);int firstPlane=((BufferedImage)((ImageIcon)stackView.getIcon()).getImage()).getRGB(0,0)&255;
        EventQueue.invokeAndWait(()->slice.setValue(2));
        waitFor(()->stackView.getIcon()!=null&&(((BufferedImage)((ImageIcon)stackView.getIcon()).getImage()).getRGB(0,0)&255)!=firstPlane);
        int secondPlane=((BufferedImage)((ImageIcon)stackView.getIcon()).getImage()).getRGB(0,0)&255;
        check(firstPlane==10&&secondPlane==220,"preview navigation displays the selected stack plane");
        check(rows(image)==5&&WindowManager.getImageCount()==open&&engine.trackedImages()==tracked,"stack preview remains separate from live images and history");
        EventQueue.invokeAndWait(()->stackFrame[0].dispose());
        EventQueue.invokeAndWait(()->{if(recording.isSelected())recording.doClick();});
        image.changes=false;image.close();System.out.println("RESULT: "+passed+" history-control checks passed");System.exit(0);
      }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
