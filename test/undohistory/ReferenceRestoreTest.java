package undohistory;

import ij.*;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Reference reset survives selection, deletion, clearing and branching; appearance modes remain accessible. */
public final class ReferenceRestoreTest {
    static Engine engine;
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    static void pause(int ms)throws Exception{Thread.sleep(ms);EventQueue.invokeAndWait(()->{});}
    static void waitFor(BooleanSupplier condition)throws Exception{for(int i=0;i<150;i++){if(condition.getAsBoolean())return;pause(40);}throw new AssertionError("Timed out");}
    static Object field(Object target,String name)throws Exception{Field f=HistoryWindow.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static byte[] pixels(ImagePlus image){return ((byte[])image.getProcessor().getPixels()).clone();}
    static int rows(ImagePlus image){Engine.View view=engine.view(image.getID());return view==null?0:view.rows.size();}
    static void command(ImagePlus image,String name,int expected)throws Exception{
        WindowManager.setCurrentWindow(image.getWindow());new Executer(name,image);waitFor(()->rows(image)==expected);pause(200);
    }
    static void screenshot(HistoryWindow window,File target)throws Exception{
        EventQueue.invokeAndWait(()->{
            Container content=window.getContentPane();
            BufferedImage image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();content.paint(g);g.dispose();
            try{ImageIO.write(image,"png",target);}catch(Exception ex){throw new RuntimeException(ex);}
        });
    }
    static void appearance(HistoryWindow window,JButton settings,String label)throws Exception{
        EventQueue.invokeAndWait(()->{
            settings.doClick();MenuElement[] path=MenuSelectionManager.defaultManager().getSelectedPath();
            JPopupMenu popup=(JPopupMenu)path[0];
            for(Component item:popup.getComponents())if(item instanceof JMenu&&((JMenu)item).getText().equals("Button appearance"))
                for(Component choice:((JMenu)item).getMenuComponents())if(choice instanceof JMenuItem&&((JMenuItem)choice).getText().equals(label))((JMenuItem)choice).doClick();
            MenuSelectionManager.defaultManager().clearSelectedPath();
        });
    }
    public static void main(String[] args)throws Exception{
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set(Engine.PREF+"testMemoryMB",64);Prefs.set(Engine.PREF+"useDisk",false);
        Prefs.set(Engine.PREF+"recordMacro",false);Prefs.set(Engine.PREF+"buttonStyle","both");Prefs.set(Engine.PREF+"keepClosed",0);
        Prefs.set(Engine.PREF+"trimOnSave",false);new Undo_History().run("start");engine=Engine.get();
        ByteProcessor data=new ByteProcessor(96,96);for(int i=0;i<data.getPixelCount();i++)data.set(i,(i*37)%251);
        ImagePlus image=new ImagePlus("Reference restoration and button layout",data);image.show();waitFor(()->rows(image)==1);
        final int id=image.getID();byte[] original=pixels(image);
        check(engine.view(id).hasOriginal&&!engine.view(id).hasSaved,"new unsaved image has original reference and no invented saved state");
        EventQueue.invokeAndWait(HistoryWindow::showWindow);HistoryWindow found=null;
        for(Window w:Window.getWindows())if(w instanceof HistoryWindow)found=(HistoryWindow)w;
        final HistoryWindow window=found;
        JButton restore=(JButton)field(window,"restore"),go=(JButton)field(window,"go"),settings=(JButton)field(window,"options");
        JComboBox<?> target=(JComboBox<?>)field(window,"referenceTarget");JList<?> list=(JList<?>)field(window,"list");
        waitFor(restore::isEnabled);
        check(go.isEnabled()&&restore.isEnabled(),"original reset and Go to step are available without selecting an older row");
        EventQueue.invokeAndWait(()->target.setSelectedIndex(1));
        check(!restore.isEnabled()&&restore.getToolTipText().contains("Save the image first"),"missing saved state has a precise explanation");
        EventQueue.invokeAndWait(()->target.setSelectedIndex(0));
        command(image,"Invert",2);byte[] saved=pixels(image);
        check(new FileSaver(image).saveAsTiff(new File(args[0],"reference-saved.tif").getAbsolutePath()),"actual TIFF save succeeds");
        waitFor(()->engine.view(id).hasSaved);pause(250);
        check(engine.view(id).rows.get(1).saved,"successful save supplies saved reference");
        command(image,"Find Edges",3);byte[] edited=pixels(image);
        waitFor(()->list.getModel().getSize()==3);
        EventQueue.invokeAndWait(()->list.setSelectionInterval(0,2));
        check(restore.isEnabled()&&!go.isEnabled(),"multi-selection does not disable original reset");
        EventQueue.invokeAndWait(restore::doClick);waitFor(()->Arrays.equals(original,pixels(image)));pause(500);
        check(engine.cursorOf(id)==0,"Restore resets original independently of selected rows");
        EventQueue.invokeAndWait(()->target.setSelectedIndex(1));waitFor(restore::isEnabled);
        EventQueue.invokeAndWait(restore::doClick);waitFor(()->Arrays.equals(saved,pixels(image)));pause(500);
        check(!image.changes,"saved reference restores exact pixels and saved status");
        engine.stepImage(id,1);check(Arrays.equals(edited,pixels(image)),"redo can return to later edits after reference restore");
        long savedKey=engine.view(id).rows.get(1).key;
        engine.deletePlanned(id,engine.planDeletion(id,new long[]{savedKey}));
        check(engine.view(id).hasSaved&&rows(image)==2,"deleting saved history row retains saved reference");
        engine.restoreReference(id,true);check(Arrays.equals(saved,pixels(image))&&!image.changes,"saved reset survives row deletion");
        engine.clear(id);check(rows(image)==1&&engine.view(id).hasOriginal&&engine.view(id).hasSaved,"clear history keeps both reference states");
        engine.restoreReference(id,false);check(Arrays.equals(original,pixels(image))&&image.changes,"original reset survives clear and is unsaved after a later save");
        engine.restoreReference(id,true);check(Arrays.equals(saved,pixels(image))&&!image.changes,"saved reset survives clear");
        engine.goTo(id,0);command(image,"Smooth",2);
        check(engine.view(id).hasSaved,"discarding a redo branch keeps saved reference");
        byte[] secondSaved=pixels(image);pause(200);
        check(new FileSaver(image).saveAsTiff(new File(args[0],"reference-saved-newer.tif").getAbsolutePath()),"second actual save succeeds");
        waitFor(()->{Engine.View v=engine.view(id);for(Engine.Row row:v.rows)if(row.current)return row.saved;return false;});
        command(image,"Invert",rows(image)+1);engine.restoreReference(id,true);
        check(Arrays.equals(secondSaved,pixels(image)),"last saved reference updates to newest successful save");
        image.getProcessor().set(0,(image.getProcessor().get(0)+1)%256);
        engine.restoreReference(id,true);
        check(Arrays.equals(secondSaved,pixels(image)),"Restore reapplies an already-current reference instead of silently doing nothing");
        pause(500);EventQueue.invokeAndWait(()->list.clearSelection());
        check(restore.isEnabled(),"restore remains usable after reset without a row selection");
        check(restore.getIcon()!=null&&!restore.getText().isEmpty(),"default appearance uses icons and text");
        screenshot(window,new File(args[0],"history-layout-both.png"));
        appearance(window,settings,"Icons only");
        check(restore.getText().isEmpty()&&restore.getIcon()!=null&&restore.getAccessibleContext().getAccessibleName().equals("Restore"),"icons-only mode keeps accessible button names");
        check(!restore.getToolTipText().isEmpty(),"icons-only Restore retains its explanation");
        screenshot(window,new File(args[0],"history-layout-icons.png"));
        appearance(window,settings,"Text only");
        check(restore.getIcon()==null&&restore.getText().equals("Restore"),"text-only mode uses readable labels");
        screenshot(window,new File(args[0],"history-layout-text.png"));
        appearance(window,settings,"Icons and text");
        check(Prefs.get(Engine.PREF+"buttonStyle","").equals("both"),"appearance choice is saved");
        engine.clear(id);pause(500);EventQueue.invokeAndWait(restore::doClick);pause(500);
        check(Arrays.equals(secondSaved,pixels(image)),"reference button still works after history clear and style changes");
        image.changes=false;image.close();pause(700);
        check(engine.usedBytes()==0,"closing an unretained history releases anchor planes and state planes");
        check(!restore.isEnabled(),"Restore disables when there is no working image");
        ImagePlus loaded=IJ.openImage(new File(args[0],"reference-saved.tif").getAbsolutePath());loaded.show();waitFor(()->rows(loaded)==1);
        check(engine.view(loaded.getID()).hasSaved,"opened file supplies a saved reference before any new save");
        command(loaded,"Invert",2);Prefs.set(Engine.PREF+"trimOnSave",true);
        check(new FileSaver(loaded).saveAsTiff(new File(args[0],"reference-trimmed.tif").getAbsolutePath()),"save with automatic history trim succeeds");
        waitFor(()->rows(loaded)==1);engine.restoreReference(loaded.getID(),false);
        check(Arrays.equals(saved,pixels(loaded)),"original reference survives automatic trim on save");
        loaded.changes=false;loaded.close();pause(500);Prefs.set(Engine.PREF+"trimOnSave",false);
        ImagePlus rgb=new ImagePlus("Reference across image replacement",new ij.process.ColorProcessor(64,48));
        Prefs.set(Engine.PREF+"keepClosed",5); // default handover policy for commands that close and replace a window
        rgb.getProcessor().setColor(new Color(190,30,70));rgb.getProcessor().fill();rgb.show();waitFor(()->rows(rgb)==1);
        int[] rgbOriginal=((int[])rgb.getProcessor().getPixels()).clone();command(rgb,"Invert",2);
        WindowManager.setCurrentWindow(rgb.getWindow());IJ.run("Make Composite");
        waitFor(()->{ImagePlus current=WindowManager.getImage("Reference across image replacement");return current!=null&&current.isComposite()&&rows(current)==3;});
        ImagePlus composite=WindowManager.getImage("Reference across image replacement");
        engine.clear(composite.getID());engine.restoreReference(composite.getID(),false);pause(500);
        ImagePlus restored=WindowManager.getImage("Reference across image replacement");
        check(restored!=null&&restored.getBitDepth()==24&&Arrays.equals(rgbOriginal,(int[])restored.getProcessor().getPixels()),"original reference follows a replacement image and restores its original type and pixels");
        Prefs.set(Engine.PREF+"keepClosed",0);restored.changes=false;restored.close();pause(700);
        check(engine.usedBytes()==0,"reference ownership is released after image replacement and close");
        System.out.println(passed+" reference reset and layout checks passed");System.exit(0);
      }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
