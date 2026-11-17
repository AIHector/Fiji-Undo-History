package undohistory;

import ij.*;
import ij.process.*;
import ij.plugin.frame.Recorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Source routing, shared context and output-based thumbnail reuse in an isolated process. */
public final class ImageScopeTest {
    static Engine engine;static int passed;
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);passed++;System.out.println("PASS "+text);}
    static void waitFor(BooleanSupplier test)throws Exception{for(int i=0;i<180;i++){if(test.getAsBoolean())return;Thread.sleep(40);}throw new AssertionError("Timed out");}
    static Engine.View view(ImagePlus image){return engine.view(image.getID());}
    static int count(ImagePlus image){Engine.View view=view(image);return view==null?0:view.rows.size();}
    static int local(ImagePlus image){Engine.View v=view(image);if(v==null)return 0;int n=0;for(Engine.Row row:v.rows)if(!row.shared)n++;return n;}
    static boolean code(ImagePlus image,String text){for(Engine.Row row:view(image).rows)if(row.macroCode!=null&&row.macroCode.contains(text))return true;return false;}
    static Engine.Row current(ImagePlus image){for(Engine.Row row:view(image).rows)if(row.current)return row;throw new AssertionError("No current state");}
    static void activate(ImagePlus image)throws Exception{EventQueue.invokeAndWait(()->{
        image.getWindow().dispatchEvent(new WindowEvent(image.getWindow(),WindowEvent.WINDOW_ACTIVATED));
        WindowManager.setCurrentWindow(image.getWindow());
    });}
    public static void main(String[] args)throws Exception{
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set(Engine.PREF+"testMemoryMB",64);Prefs.set(Engine.PREF+"useDisk",false);Prefs.set(Engine.PREF+"maxSteps",100);
        ByteProcessor aPixels=new ByteProcessor(80,60);for(int i=0;i<aPixels.getPixelCount();i++)aPixels.set(i,i%251);
        ImagePlus a=new ImagePlus("Scope A",aPixels),b=new ImagePlus("Scope B",new ByteProcessor(64,48));a.show();b.show();
        new Undo_History().run("start");engine=Engine.get();waitFor(()->count(a)==1&&count(b)==1);
        Engine.Row aBaseline=current(a),bBaseline=current(b);
        activate(a);new Executer("Invert",a);waitFor(()->local(a)==2);Thread.sleep(500);
        check(local(b)==1&&code(a,"Invert")&&!code(b,"Invert"),"an edit on A records code only in A even with two images open");
        check(current(a).thumbnail!=aBaseline.thumbnail&&current(b).thumbnail==bBaseline.thumbnail,"only changed image output creates a new preview");
        int cursorA=engine.cursorOf(a.getID());Engine.Row beforeSelection=current(a);
        a.setRoi(2,3,10,12);waitFor(()->local(a)==3);
        check(current(a).thumbnail==beforeSelection.thumbnail,"selection-only state reuses the existing image-output preview");
        activate(b);new Executer("Copy",b);waitFor(()->local(b)==2);
        check(code(b,"Copy")&&!code(a,"Copy")&&engine.cursorOf(a.getID())==cursorA+1,"switching images routes a non-pixel command to the selected image");
        check(current(b).thumbnail==bBaseline.thumbnail,"a recorded Copy command reuses the same preview");
        // A rapid recorder block includes selection changes before the ticker gets a chance to poll.
        Recorder.recordString("selectImage(\"Scope A\");\nsetThreshold(11, 101);\nselectImage(\"Scope B\");\nsetThreshold(22, 202);\n");
        waitFor(()->code(a,"11, 101")&&code(b,"22, 202"));
        check(!code(a,"22, 202")&&!code(b,"11, 101"),"rapid interleaved selection lines route recorder-only instructions without cross-image contamination");
        check(!code(a,"selectImage(")&&!code(b,"selectImage("),"image-selection lines route capture without becoming image-history steps");
        activate(b);
        Recorder.recordString("selectImage(\"Scope A\");\nsetThreshold(31,");MacroCapture.observe(b.getID());
        Recorder.recordString(" 131);\nsetThreshold(42, 142);\n");
        waitFor(()->code(a,"31, 131")&&code(b,"42, 142"));
        check(!code(b,"31, 131")&&!code(a,"42, 142"),"a partial instruction retains its source across an image switch before the final append");
        int aLocal=local(a),bLocal=local(b),aCursor=engine.cursorOf(a.getID()),bCursor=engine.cursorOf(b.getID());
        engine.goTo(b.getID(),0);waitFor(()->engine.cursorOf(b.getID())==0);
        Recorder.recordString("setForegroundColor(7, 18, 29);\nsetOption(\"BlackBackground\", true);\n");
        waitFor(()->code(a,"7, 18, 29")&&code(b,"7, 18, 29"));
        check(local(a)==aLocal&&local(b)==bLocal&&engine.cursorOf(a.getID())==aCursor&&engine.cursorOf(b.getID())==0,"shared settings appear in both histories without adding restore points or moving either cursor");
        check(view(b).canRedo,"shared context preserves an inactive image's redo branch");
        Engine.Row shared=null;for(Engine.Row row:view(a).rows)if(row.shared&&row.macroCode.contains("7, 18, 29"))shared=row;
        check(shared!=null&&shared.thumbnail==null&&shared.dimensions.isEmpty(),"shared context creates no image preview or invented dimensions");
        MacroExport exported=engine.exportMacro(a.getID(),new long[]{shared.key});
        check(exported.code.contains("setForegroundColor(7, 18, 29)")&&exported.code.contains("Shared context"),"shared settings can be copied into an explicitly labelled macro export");
        // A local setOption must not be classified as global merely because it shares the function name.
        activate(a);Recorder.recordString("setOption(\"Changes\", false);\n");waitFor(()->code(a,"Changes"));
        check(!code(b,"Changes"),"image-local setOption variants are not broadcast as shared settings");
        engine.setThumbnails(false);engine.setThumbnails(true);
        java.awt.image.BufferedImage regenerated=engine.thumbnailOf(b.getID(),bBaseline.key);
        Engine.Row copy=null;for(Engine.Row row:view(b).rows)if(row.macroCode!=null&&row.macroCode.contains("Copy"))copy=row;
        check(engine.thumbnailOf(b.getID(),copy.key)==regenerated,"regenerating previews reuses one bitmap for identical output across rows");
        long previousNumber=current(a).number;engine.clear(a.getID());
        check(view(a).rows.get(0).current&&!view(a).rows.get(0).shared&&view(a).rows.get(0).number==previousNumber,"clearing image history keeps its retained baseline first and preserves stable numbering beside shared context");
        new Executer("Invert",a);waitFor(()->local(a)==2);long next=current(a).key;
        long[] recipe=engine.rangeKeys(a.getID(),next,"through");MacroExport range=engine.exportMacro(a.getID(),recipe);
        check(range.code.contains("setForegroundColor(7, 18, 29)")&&range.code.contains("Invert"),"macro ranges after clear include shared context and exclude the retained baseline");
        check(engine.imageRangeKeys(a.getID(),next,"before").length==0,"deletion ranges exclude shared context rows");
        int bRows=count(b);a.changes=false;a.close();activate(b);Recorder.recordString("selectImage(\"Scope A\");\nsetThreshold(99, 199);\n");Thread.sleep(500);
        check(count(b)==bRows,"a selection line for a closed image never creates a row in another image");
        ImageStack stack=new ImageStack(32,32);ByteProcessor low=new ByteProcessor(32,32),high=new ByteProcessor(32,32);low.setValue(10);low.fill();high.setValue(220);high.fill();stack.addSlice(low);stack.addSlice(high);
        ImagePlus planes=new ImagePlus("Scope stack",stack);planes.show();waitFor(()->local(planes)==1);activate(planes);
        Engine.Row first=current(planes);planes.setSlice(2);Recorder.recordString("setSlice(2);\n");waitFor(()->local(planes)>=2);
        Engine.Row second=current(planes);check(second.thumbnail!=first.thumbnail&&(second.thumbnail.getRGB(0,0)&255)==220,"changing the displayed plane creates a preview of the new output");
        engine.goTo(planes.getID(),0);check(planes.getCurrentSlice()==1,"restoring a captured plane returns to its displayed slice");
        b.changes=false;b.close();planes.changes=false;planes.close();System.out.println("RESULT: "+passed+" image-scope and preview-reuse checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
