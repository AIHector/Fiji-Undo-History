package undohistory;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.*;

/** Navigation, reference restoration, selected-step actions and recording each have their own place. */
final class HistoryWindow extends JFrame {
    private static HistoryWindow instance;
    private static final String LOC = Engine.PREF + "window.loc";
    private final Engine engine = Engine.get();
    private final DefaultListModel<Engine.Row> model = new DefaultListModel<>();
    private final HistoryTable list = new HistoryTable(model);
    private final HistoryToolbar toolbar;
    private final JLabel header = new JLabel(" "), footer = new JLabel(" "), selectionInfo = new JLabel("Current step");
    private final JCheckBox recordMacro = new JCheckBox("Record macro");
    private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"), go = new JButton("Go to step"),
            restore = new JButton("Restore"), preview = new JButton("Preview"), copy = new JButton("Open copy"), keep = new JButton("Keep checkpoint"),
            macro = new JButton("Create macro…"), delete = new JButton("Delete…"), clear = new JButton("Clear history…"),
            options = new JButton("Settings…"), record = new JButton("Recorder…");
    private final JComboBox<String> referenceTarget = new JComboBox<>(new String[]{"Original tracked state", "Last saved state"});
    private final Runnable refresher = this::refresh;
    private final javax.swing.Timer timer;
    private int imageID, shownStamp = -1;
    private boolean busy, restoreProblem, hasOriginal, hasSaved, canUndo, canRedo;
    private boolean thumbnailsShown=Thumbnail.enabled();
    private JPopupMenu lastPopup;

    static void showWindow() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(HistoryWindow::showWindow); return; }
        if (instance == null) instance = new HistoryWindow();
        instance.setVisible(true); instance.toFront(); instance.refresh();
    }

    private HistoryWindow() {
        super("Undo History"); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> updateButtons());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    int index = rowAt(e.getPoint());
                    if (index >= 0) {
                        int column=list.columnAtPoint(e.getPoint());int kind=column<0?-1:list.convertColumnIndexToModel(column);
                        if(kind==2)exportMacro(imageID,new long[]{model.get(index).key},false);
                        else if(!model.get(index).shared)act(imageID,model.get(index).key,kind==3?"preview":"go");
                    }
                }
            }
        });
        header.setBorder(BorderFactory.createEmptyBorder(6,8,4,8));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        footer.setBorder(BorderFactory.createEmptyBorder(4,8,6,8));
        footer.setFont(footer.getFont().deriveFont(footer.getFont().getSize2D()-1));
        undo.setToolTipText("Restore the previous state"); redo.setToolTipText("Restore the next available state");
        go.setToolTipText("Restore one selected state in the working image");
        restore.setToolTipText("Reset the working image to the original tracked state or the last saved state; row selection does not affect this action");
        referenceTarget.setToolTipText("Choose the reference state for Restore. Original means when Undo History began tracking this image.");
        referenceTarget.addActionListener(e -> updateButtons());
        preview.setToolTipText("Inspect a detached snapshot without changing the working image or history");
        copy.setToolTipText("Open one selected state as a new editable image");
        keep.setToolTipText("Protect this checkpoint from automatic history trimming");
        delete.setToolTipText("Delete selected restore points; the baseline stays. Later snapshots retain their contents.");
        macro.setToolTipText("Preview macro code from selected operations in history order");
        recordMacro.setToolTipText("Capture image operations and recorded actions by default. Unchecking retains earlier code; an independently opened Recorder can continue separately.");
        record.setToolTipText("Show ImageJ's Macro Recorder; otherwise recording can remain in the background");
        undo.addActionListener(e -> { final int id=imageID; run(() -> engine.stepImage(id,-1)); });
        redo.addActionListener(e -> { final int id=imageID; run(() -> engine.stepImage(id,1)); });
        go.addActionListener(e -> singleAction("go")); preview.addActionListener(e -> singleAction("preview"));
        copy.addActionListener(e -> singleAction("copy")); keep.addActionListener(e -> singleAction("keep"));
        delete.addActionListener(e -> deleteSelection(imageID, imageOperationKeys()));
        macro.addActionListener(e -> exportMacro(imageID, selectedKeys(true), false));
        clear.addActionListener(e -> {
            final int id=imageID;
            if (JOptionPane.showConfirmDialog(this,"Clear the step list except the current state?\nOriginal and last saved reference states will remain available in Reset image.",
                    "Clear history",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) run(() -> engine.clear(id));
        });
        options.addActionListener(e -> showSettings());
        restore.addActionListener(e -> { final int id=imageID; final boolean saved=referenceTarget.getSelectedIndex()==1; run(() -> engine.restoreReference(id,saved)); });
        recordMacro.addActionListener(e -> {
            recordMacro.setSelected(RecordingControl.setEnabled(recordMacro.isSelected()));
            updateButtons();
        });
        record.addActionListener(e -> { RecordingControl.showRecorder(); recordMacro.setSelected(RecordingControl.enabled()); });

        JButton[] all={undo,redo,restore,go,preview,copy,keep,macro,delete,clear,options,record};
        String[] kinds={"undo","redo","restore","go","preview","copy","keep","macro","delete","clear","options","record"};
        for(int i=0;i<all.length;i++) {
            all[i].putClientProperty("actionLabel",all[i].getText());
            all[i].putClientProperty("actionIcon",new ActionIcon(kinds[i]));
            all[i].setMargin(new Insets(5,8,5,8));
        }
        applyButtonStyle();
        LinkedHashMap<String,JComponent> tools=new LinkedHashMap<>();
        tools.put("undo",undo);tools.put("redo",redo);tools.put("reference",referenceTarget);tools.put("restore",restore);
        tools.put("go",go);tools.put("preview",preview);tools.put("copy",copy);tools.put("keep",keep);tools.put("macro",macro);
        tools.put("delete",delete);tools.put("clear",clear);tools.put("options",options);
        toolbar=new HistoryToolbar(tools);
        JPanel north=new JPanel(new BorderLayout());north.add(header,BorderLayout.NORTH);north.add(toolbar,BorderLayout.SOUTH);
        selectionInfo.setBorder(BorderFactory.createEmptyBorder(2,8,2,8));
        JPanel recording=new JPanel(new BorderLayout());recording.setBorder(BorderFactory.createEmptyBorder(4,8,2,8));recording.add(recordMacro,BorderLayout.WEST);recording.add(record,BorderLayout.EAST);
        JPanel actions=new JPanel(new BorderLayout());actions.add(selectionInfo,BorderLayout.NORTH);actions.add(recording,BorderLayout.SOUTH);
        JPanel south=new JPanel(new BorderLayout());south.add(actions,BorderLayout.CENTER);south.add(footer,BorderLayout.SOUTH);
        list.setPopupHandler(this::showPopup);
        add(north,BorderLayout.NORTH);add(new JScrollPane(list),BorderLayout.CENTER);add(south,BorderLayout.SOUTH);
        setSize(1120,640);setMinimumSize(new Dimension(560,360));
        Point location=Prefs.getLocation(LOC);if(location!=null)setLocation(location);else setLocationRelativeTo(null);
        engine.addChangeListener(refresher);timer=new javax.swing.Timer(400,e -> refresh());timer.start();
        WindowManager.addWindow(this);
        addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){
            Prefs.saveLocation(LOC,getLocation());list.close();timer.stop();engine.removeChangeListener(refresher);
            WindowManager.removeWindow(HistoryWindow.this);instance=null;
        }});
    }

    private static JPanel row() { return new JPanel(new FlowLayout(FlowLayout.LEFT,6,3)); }
    private JButton[] styledButtons() { return new JButton[]{undo,redo,restore,go,preview,copy,keep,macro,delete,clear,options,record}; }
    private void label(JButton button,String text) { button.putClientProperty("actionLabel",text);style(button); }
    private void style(JButton button) {
        String mode=Prefs.get(Engine.PREF+"buttonStyle","both"), text=(String)button.getClientProperty("customLabel");
        if(text==null)text=(String)button.getClientProperty("actionLabel");
        button.setText(mode.equals("icons")?"":text);
        button.setIcon(mode.equals("text")?null:(Icon)button.getClientProperty("actionIcon"));
        button.getAccessibleContext().setAccessibleName(text);
        if(button.getToolTipText()==null)button.setToolTipText(text);
    }
    private void applyButtonStyle() { for(JButton button:styledButtons())style(button);revalidate();repaint(); }
    private void showSettings() {
        JPopupMenu popup=new JPopupMenu();
        item(popup,"History settings…",() -> run(Undo_History::showOptions),!busy);
        JMenu display=new JMenu("Button appearance");popup.add(display);ButtonGroup group=new ButtonGroup();
        String[] modes={"both","text","icons"},names={"Icons and text","Text only","Icons only"};
        for(int i=0;i<modes.length;i++) {
            final String mode=modes[i];
            JRadioButtonMenuItem choice=new JRadioButtonMenuItem(names[i],Prefs.get(Engine.PREF+"buttonStyle","both").equals(mode));
            group.add(choice);display.add(choice);
            choice.addActionListener(e -> {Prefs.set(Engine.PREF+"buttonStyle",mode);Prefs.savePreferences();applyButtonStyle();});
        }
        popup.addSeparator();popup.add(list.columnsMenu());
        JCheckBoxMenuItem shared=new JCheckBoxMenuItem("Show shared context",Prefs.get(Engine.PREF+"showSharedContext",true));
        shared.addActionListener(e -> {Prefs.set(Engine.PREF+"showSharedContext",shared.isSelected());Prefs.savePreferences();shownStamp=-1;refresh();});popup.add(shared);
        JCheckBoxMenuItem thumbnails=new JCheckBoxMenuItem("Image previews",Thumbnail.enabled());
        thumbnails.addActionListener(e -> {boolean enabled=thumbnails.isSelected();list.thumbnailsChanged();run(() -> {engine.setThumbnails(enabled);SwingUtilities.invokeLater(list::thumbnailsChanged);});});popup.add(thumbnails);
        item(popup,"Customize toolbar…",toolbar::customize,true);
        popup.addSeparator();item(popup,"Clear history…",clear::doClick,clear.isEnabled());
        if(options.isShowing())popup.show(options,0,options.getHeight());
        else popup.show(toolbar,Math.max(0,toolbar.getWidth()-220),toolbar.getHeight());
    }

    private void run(Runnable action) {
        if(busy)return;busy=true;updateButtons();
        new Thread(() -> {
            try {action.run();}
            catch(IllegalArgumentException ex){SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,ex.getMessage(),"Undo History",JOptionPane.INFORMATION_MESSAGE));}
            catch(Throwable ex){IJ.handleException(ex);}
            finally{SwingUtilities.invokeLater(() -> {busy=false;shownStamp=-1;refresh();});}
        },"UndoHistory-action").start();
    }

    private void singleAction(String action) {
        if(list.getSelectedIndices().length>1)return;
        int index=selectedOrCurrent();if(index>=0&&!model.get(index).shared)act(imageID,model.get(index).key,action);
    }
    private void act(int id,long key,String action) {
        run(() -> {
            if("preview".equals(action)) { ImagePlus snapshot=engine.snapshotOf(id,key); SnapshotPreview.show(snapshot,snapshot.getTitle()); }
            else engine.rowAction(id,key,action);
        });
    }
    private int rowAt(Point point) {
        return list.rowAt(point);
    }
    private int selectedOrCurrent() {
        int selected=list.getSelectedIndex();if(selected>=0&&selected<model.size())return selected;
        for(int i=0;i<model.size();i++)if(model.get(i).current)return i;return -1;
    }
    private long[] selectedKeys(boolean operationsOnly) {
        int[] indices=list.getSelectedIndices();
        if(indices.length==0){int current=selectedOrCurrent();indices=current<0?new int[0]:new int[]{current};}
        java.util.List<Long> keys=new ArrayList<>();
        for(int index:indices)if(index<model.size()&&(!operationsOnly||index>0)&&(operationsOnly||!model.get(index).shared))keys.add(model.get(index).key);
        long[] result=new long[keys.size()];for(int i=0;i<result.length;i++)result[i]=keys.get(i);return result;
    }

    private long[] imageOperationKeys(){
        long[] keys=selectedKeys(false);java.util.List<Long> operations=new ArrayList<>();
        for(long key:keys)for(int i=1;i<model.size();i++)if(model.get(i).key==key&&!model.get(i).shared)operations.add(key);
        long[] result=new long[operations.size()];for(int i=0;i<result.length;i++)result[i]=operations.get(i);return result;
    }

    private void deleteSelection(int id,long[] keys) { run(() -> confirmDeletion(id,keys)); }
    private void confirmDeletion(int id,long[] keys) {
        if(keys.length==0)throw new IllegalArgumentException("There are no steps in this range.");
        Engine.DeletionPlan plan=engine.planDeletion(id,keys);
        if(plan.count==0)throw new IllegalArgumentException("The baseline is protected and cannot be deleted.");
        String message="Delete "+plan.count+" restore point(s)? The baseline will stay.\n"
                +(plan.current?"The current state is included: the image will first return to the nearest earlier retained state.\n":"The working image will stay as it is.\n")
                +(plan.checkpoints>0?"This includes "+plan.checkpoints+" kept or saved checkpoint(s).\n":"")
                +"Deleting restore points does not remove those operations from later snapshots.\nThis removal cannot be undone.";
        final boolean[] approved={false};
        try {SwingUtilities.invokeAndWait(() -> approved[0]=JOptionPane.showOptionDialog(this,message,"Delete history steps",
                JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE,null,new Object[]{"Delete steps","Cancel"},"Cancel")==0);}
        catch(Exception ex){throw new IllegalStateException(ex);}
        if(approved[0])engine.deletePlanned(id,plan);
    }

    private void exportMacro(int id,long[] keys,boolean clipboard) {
        run(() -> {
            MacroExport exported=engine.exportMacro(id,keys);
            if(clipboard)Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(exported.code),null);
            else SwingUtilities.invokeLater(() -> MacroPreview.show(exported));
        });
    }
    private void exportRange(int id,long anchor,String range) {
        run(() -> {
            MacroExport exported=engine.exportMacro(id,engine.rangeKeys(id,anchor,range));
            SwingUtilities.invokeLater(() -> MacroPreview.show(exported));
        });
    }
    private void deleteRange(int id,long anchor,String range) {
        run(() -> confirmDeletion(id,engine.imageRangeKeys(id,anchor,range)));
    }
    private void edit(Engine.Row row,int id,boolean note) {
        if(note){
            JTextArea input=new JTextArea(row.note,7,42);input.setLineWrap(true);input.setWrapStyleWord(true);
            if(JOptionPane.showConfirmDialog(this,new JScrollPane(input),"Note for "+row.label,JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
                String text=input.getText();run(() -> engine.annotate(id,row.key,null,text));
            }
        }else{
            String label=(String)JOptionPane.showInputDialog(this,"Step name:","Rename step",JOptionPane.PLAIN_MESSAGE,null,null,row.label);
            if(label!=null)run(() -> engine.annotate(id,row.key,label,null));
        }
    }
    private JMenuItem item(JComponent menu,String name,Runnable action,boolean enabled) {
        JMenuItem item=new JMenuItem(name);item.setEnabled(enabled);item.addActionListener(e -> action.run());menu.add(item);return item;
    }

    /** Captures the clicked row and selection before a refresh can reorder or remove rows. */
    private void showPopup(MouseEvent event) {
        if(busy)return;int index=rowAt(event.getPoint());if(index<0)return;
        if(!list.isSelectedIndex(index))list.setSelectedIndex(index);
        final int id=imageID;final Engine.Row row=model.get(index);
        final long[] selected=imageOperationKeys(), operations=selectedKeys(true);
        JPopupMenu popup=new JPopupMenu();lastPopup=popup;
        if(row.shared){
            item(popup,"Shared context — does not restore image or settings",() -> {},false);
            item(popup,"Copy macro instruction",() -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(row.macroCode),null),row.macroCode!=null);
            item(popup,"Export selected macro instructions…",() -> exportMacro(id,operations,false),operations.length>0);
            popup.show(list,event.getX(),event.getY());return;
        }
        JMenu imageExport=new JMenu("Export image at this step");popup.add(imageExport);
        SnapshotExport.addFormats(imageExport,this,engine,id,row.key);
        item(popup,"Preview this state",() -> act(id,row.key,"preview"),true);
        item(popup,"Restore this state",() -> act(id,row.key,"go"),!row.current||restoreProblem);
        item(popup,"Open as a new image",() -> act(id,row.key,"copy"),true);
        popup.addSeparator();
        JMenu copyMenu=new JMenu("Copy");popup.add(copyMenu);
        item(copyMenu,"Macro instruction",() -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(row.macroCode),null),row.macroCode!=null);
        item(copyMenu,"Selected macro instructions",() -> exportMacro(id,operations,true),operations.length>0);
        item(copyMenu,"Step details",() -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details(row)),null),true);
        JMenu export=new JMenu("Export macro");popup.add(export);
        item(export,"Selected instructions…",() -> exportMacro(id,operations,false),operations.length>0);
        item(export,"Through this step (inclusive)…",() -> exportRange(id,row.key,"through"),index>0);
        item(export,"After this step…",() -> exportRange(id,row.key,"after"),index+1<model.size());
        popup.addSeparator();
        item(popup,"Rename step…",() -> edit(row,id,false),true);
        item(popup,"Add or edit note…",() -> edit(row,id,true),true);
        JCheckBoxMenuItem checkpoint=new JCheckBoxMenuItem("Keep as checkpoint",row.pinned);
        checkpoint.setEnabled(index>0&&!row.saved);checkpoint.addActionListener(e -> act(id,row.key,"keep"));popup.add(checkpoint);
        boolean allIncluded=true;for(Engine.Row chosen:list.getSelectedValuesList())if(!chosen.shared&&!chosen.included)allIncluded=false;
        final boolean include=!allIncluded;
        JCheckBoxMenuItem included=new JCheckBoxMenuItem("Include selected image steps in exported macro",allIncluded);
        included.setEnabled(selected.length>0);included.addActionListener(e -> run(() -> engine.setIncluded(id,selected,include)));popup.add(included);
        popup.addSeparator();
        item(popup,"Delete selected steps…",() -> deleteSelection(id,selected),operations.length>0);
        item(popup,"Delete steps before this…",() -> deleteRange(id,row.key,"before"),index>1);
        item(popup,"Delete steps after this…",() -> deleteRange(id,row.key,"after"),index+1<model.size());
        popup.show(list,event.getX(),event.getY());
    }
    static String details(Engine.Row row) {
        return row.label+"\nOriginal operation: "+row.originalLabel+"\nTime: "+row.time
                +"\nIncluded in macro: "+row.included+"\nCheckpoint: "+row.pinned+"\nNote: "+row.note
                +"\nDimension: "+row.dimensions+"\n"+(row.recorderOnly?"Recorded action: image snapshot restoration does not undo external effects.":"")+"\n\n"+(row.macroCode==null?"No recorded macro code.":row.macroCode);
    }

    private void updateButtons() {
        int current=-1;for(int i=0;i<model.size();i++)if(model.get(i).current)current=i;
        boolean enabled=!busy&&current>=0,single=enabled&&list.getSelectedIndices().length<=1;
        int selected=selectedOrCurrent();
        single=single&&(selected<0||!model.get(selected).shared);
        undo.setEnabled(enabled&&canUndo);redo.setEnabled(enabled&&canRedo);
        go.setEnabled(single&&selected>=0);preview.setEnabled(single);copy.setEnabled(single);
        restore.setEnabled(enabled&&(referenceTarget.getSelectedIndex()==1?hasSaved:hasOriginal));
        referenceTarget.setEnabled(enabled);
        restore.setToolTipText(referenceTarget.getSelectedIndex()==1&&!hasSaved?"Save the image first to create a saved restore point":"Restore "+referenceTarget.getSelectedItem()+" independently of the selected rows; Undo/Redo retains available history");
        keep.setEnabled(single&&selected>0&&!model.get(selected).saved);
        label(keep,selected==0?"Baseline protected":selected>=0&&selected<model.size()&&model.get(selected).saved?"Saved checkpoint":
                selected>=0&&selected<model.size()&&model.get(selected).pinned?"Release checkpoint":"Keep checkpoint");
        int deletable=imageOperationKeys().length;delete.setEnabled(enabled&&deletable>0);
        label(delete,deletable>1?"Delete ("+deletable+")…":"Delete…");
        int count=list.getSelectedIndices().length;
        selectionInfo.setText(count>1?count+" selected steps":count==1?"Selected step":"Current step · select rows for more actions");
        macro.setEnabled(enabled&&selectedKeys(true).length>0);clear.setEnabled(enabled&&model.size()>1);
        recordMacro.setEnabled(!busy);record.setEnabled(!busy);options.setEnabled(!busy);list.setEnabled(!busy);
    }
    private void refresh() {
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        RecordingControl.sync();recordMacro.setSelected(RecordingControl.enabled());
        if(thumbnailsShown!=Thumbnail.enabled()){thumbnailsShown=Thumbnail.enabled();list.thumbnailsChanged();}
        ImagePlus image=WindowManager.getCurrentImage();int id=image==null?0:image.getID();
        Engine.View view=image==null?null:engine.view(id);
        int stamp=view==null?0:Objects.hash(id,view.title,view.untracked,view.dropped,view.hasOriginal,view.hasSaved,rowsSignature(view));
        footer.setText("Memory "+mb(engine.usedBytes())+" / "+gb(engine.store.ramBudget)
                +(engine.store.diskEnabled?" · Disk "+mb(engine.diskBytes())+" / "+gb(engine.store.diskBudget):""));
        if(stamp==shownStamp&&id==imageID)return;
        Set<Long> selected=new HashSet<>();if(id==imageID)for(Engine.Row row:list.getSelectedValuesList())selected.add(row.key);
        shownStamp=stamp;imageID=id;restoreProblem=view!=null&&view.problem!=null;
        hasOriginal=view!=null&&view.hasOriginal;hasSaved=view!=null&&view.hasSaved;canUndo=view!=null&&view.canUndo;canRedo=view!=null&&view.canRedo;model.clear();list.setImageID(id);updateButtons();
        if(image==null){header.setText("No image open");return;}
        if(view==null||view.untracked!=null){header.setText(image.getTitle()+(Engine.supportsHistory(image)?": not tracked":" · native tool"));return;}
        int sharedCount=0;for(Engine.Row row:view.rows)if(row.shared)sharedCount++;
        header.setText("<html>"+esc(view.title)+"<br><span style='font-weight:normal'>"+(view.rows.size()-sharedCount-1)+" image step(s)"+(sharedCount>0?" · "+sharedCount+" shared context row(s)":"")
                +(view.dropped>0?" · "+view.dropped+" step(s) removed":"")+"</span>"
                +(view.problem!=null?"<br><span style='color:#b00020'>"+esc(view.problem)+"</span>":"")+"</html>");
        int current=-1;for(int i=0;i<view.rows.size();i++){model.addElement(view.rows.get(i));if(view.rows.get(i).current)current=i;}
        list.setImageID(id);list.clearSelection();for(int i=0;i<model.size();i++)if(selected.contains(model.get(i).key))list.addSelectionInterval(i,i);
        if(selected.isEmpty()&&current>=0)list.ensureIndexIsVisible(current);updateButtons();
    }
    private static int rowsSignature(Engine.View view) {
        int hash=1;for(Engine.Row row:view.rows)hash=31*hash+Objects.hash(row.key,row.label,row.note,row.current,row.pinned,row.saved,row.macroCode,row.included,row.thumbnail,row.recorderOnly,row.shared,row.number,row.savedNumber);
        return 31*hash+Objects.hashCode(view.problem);
    }
    private static String mb(long bytes){return IJ.d2s(bytes/1048576.0,bytes<10*1048576?1:0)+" MB";}
    private static String gb(long bytes){return IJ.d2s(bytes/1073741824.0,1)+" GB";}
    private static String esc(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

}
