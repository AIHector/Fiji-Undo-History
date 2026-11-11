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

/** Select rows for actions; preview and restore deliberately have different effects. */
final class HistoryWindow extends JFrame {
    private static HistoryWindow instance;
    private static final String LOC = Engine.PREF + "window.loc";
    private final Engine engine = Engine.get();
    private final DefaultListModel<Engine.Row> model = new DefaultListModel<>();
    private final JList<Engine.Row> list = new HistoryList();
    private final JLabel header = new JLabel(" "), footer = new JLabel(" ");
    private final JCheckBox recordMacro = new JCheckBox("Record macro");
    private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"), go = new JButton("Restore"),
            preview = new JButton("Preview"), copy = new JButton("Open image"), keep = new JButton("Keep"),
            macro = new JButton("Create macro…"), delete = new JButton("Delete"), clear = new JButton("Clear"),
            options = new JButton("Options…"), record = new JButton("Recorder…");
    private final Runnable refresher = this::refresh;
    private final javax.swing.Timer timer;
    private int imageID, shownStamp = -1;
    private boolean busy, restoreProblem;
    private JPopupMenu lastPopup;

    static void showWindow() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(HistoryWindow::showWindow); return; }
        if (instance == null) instance = new HistoryWindow();
        instance.setVisible(true); instance.toFront(); instance.refresh();
    }

    private HistoryWindow() {
        super("Undo History"); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(e -> updateButtons());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    int index = rowAt(e.getPoint());
                    if (index >= 0) act(imageID, model.get(index).key, "go");
                }
            }
        });
        header.setBorder(BorderFactory.createEmptyBorder(6,8,4,8));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        footer.setBorder(BorderFactory.createEmptyBorder(4,8,6,8));
        footer.setFont(footer.getFont().deriveFont(footer.getFont().getSize2D()-1));
        undo.setToolTipText("Restore the previous state"); redo.setToolTipText("Restore the next available state");
        go.setToolTipText("Restore one selected state in the working image");
        preview.setToolTipText("Inspect a detached snapshot without changing the working image or history");
        copy.setToolTipText("Open one selected state as a new editable image");
        keep.setToolTipText("Protect this checkpoint from automatic history trimming");
        delete.setToolTipText("Delete selected restore points; the baseline stays. Later snapshots retain their contents.");
        macro.setToolTipText("Preview macro code from selected operations in history order");
        recordMacro.setToolTipText("Capture code for new history steps. Unchecking retains earlier code; an independently opened Recorder can continue separately.");
        record.setToolTipText("Show ImageJ's Macro Recorder; otherwise recording can remain in the background");
        undo.addActionListener(e -> { final int id=imageID; run(() -> engine.stepImage(id,-1)); });
        redo.addActionListener(e -> { final int id=imageID; run(() -> engine.stepImage(id,1)); });
        go.addActionListener(e -> singleAction("go")); preview.addActionListener(e -> singleAction("preview"));
        copy.addActionListener(e -> singleAction("copy")); keep.addActionListener(e -> singleAction("keep"));
        delete.addActionListener(e -> deleteSelection(imageID, selectedKeys(false)));
        macro.addActionListener(e -> exportMacro(imageID, selectedKeys(true), false));
        clear.addActionListener(e -> {
            final int id=imageID;
            if (JOptionPane.showConfirmDialog(this,"Forget every restore point except the current state?",
                    "Clear history",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) run(() -> engine.clear(id));
        });
        options.addActionListener(e -> run(Undo_History::showOptions));
        recordMacro.addActionListener(e -> {
            recordMacro.setSelected(RecordingControl.setEnabled(recordMacro.isSelected()));
            updateButtons();
        });
        record.addActionListener(e -> { RecordingControl.showRecorder(); recordMacro.setSelected(RecordingControl.enabled()); });

        JPanel recording = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        recording.add(recordMacro); recording.add(record);
        JPanel north = new JPanel(new BorderLayout()); north.add(header,BorderLayout.NORTH);
        JLabel hint = new JLabel("Select rows for actions · Right-click for more · Double-click to restore");
        hint.setBorder(BorderFactory.createEmptyBorder(0,8,4,8));
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D()-1));
        north.add(hint,BorderLayout.CENTER); north.add(recording,BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new GridLayout(0,3,4,4));
        buttons.setBorder(BorderFactory.createEmptyBorder(4,6,2,6));
        for (JButton button : new JButton[]{undo,redo,go,preview,copy,keep,macro,delete,clear,options}) buttons.add(button);
        JPanel south = new JPanel(new BorderLayout()); south.add(buttons,BorderLayout.NORTH);south.add(footer,BorderLayout.SOUTH);
        add(north,BorderLayout.NORTH);add(new JScrollPane(list),BorderLayout.CENTER);add(south,BorderLayout.SOUTH);
        setSize(560,560);setMinimumSize(new Dimension(470,330));
        Point location=Prefs.getLocation(LOC);if(location!=null)setLocation(location);else setLocationRelativeTo(null);
        engine.addChangeListener(refresher);timer=new javax.swing.Timer(400,e -> refresh());timer.start();
        WindowManager.addWindow(this);
        addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){
            Prefs.saveLocation(LOC,getLocation());timer.stop();engine.removeChangeListener(refresher);
            WindowManager.removeWindow(HistoryWindow.this);instance=null;
        }});
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
        int index=selectedOrCurrent();if(index>=0)act(imageID,model.get(index).key,action);
    }
    private void act(int id,long key,String action) {
        run(() -> {
            if("preview".equals(action)) { ImagePlus snapshot=engine.snapshotOf(id,key); SnapshotPreview.show(snapshot,snapshot.getTitle()); }
            else engine.rowAction(id,key,action);
        });
    }
    private int rowAt(Point point) {
        int index=list.locationToIndex(point);
        return index>=0&&list.getCellBounds(index,index).contains(point)?index:-1;
    }
    private int selectedOrCurrent() {
        int selected=list.getSelectedIndex();if(selected>=0&&selected<model.size())return selected;
        for(int i=0;i<model.size();i++)if(model.get(i).current)return i;return -1;
    }
    private long[] selectedKeys(boolean operationsOnly) {
        int[] indices=list.getSelectedIndices();
        if(indices.length==0){int current=selectedOrCurrent();indices=current<0?new int[0]:new int[]{current};}
        java.util.List<Long> keys=new ArrayList<>();
        for(int index:indices)if(index<model.size()&&(!operationsOnly||index>0))keys.add(model.get(index).key);
        long[] result=new long[keys.size()];for(int i=0;i<result.length;i++)result[i]=keys.get(i);return result;
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
        run(() -> confirmDeletion(id,engine.rangeKeys(id,anchor,range)));
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
        final long[] selected=selectedKeys(false), operations=selectedKeys(true);
        JPopupMenu popup=new JPopupMenu();lastPopup=popup;
        item(popup,"Preview this state",() -> act(id,row.key,"preview"),true);
        item(popup,"Restore this state",() -> act(id,row.key,"go"),!row.current||restoreProblem);
        item(popup,"Open as a new image",() -> act(id,row.key,"copy"),true);
        popup.addSeparator();
        JMenu copyMenu=new JMenu("Copy");popup.add(copyMenu);
        item(copyMenu,"Macro instruction",() -> exportMacro(id,new long[]{row.key},true),index>0);
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
        boolean allIncluded=true;for(Engine.Row chosen:list.getSelectedValuesList())if(!chosen.included)allIncluded=false;
        final boolean include=!allIncluded;
        JCheckBoxMenuItem included=new JCheckBoxMenuItem("Include selected in exported macro",allIncluded);
        included.setEnabled(operations.length>0);included.addActionListener(e -> run(() -> engine.setIncluded(id,operations,include)));popup.add(included);
        popup.addSeparator();
        item(popup,"Delete selected steps…",() -> deleteSelection(id,selected),operations.length>0);
        item(popup,"Delete steps before this…",() -> deleteRange(id,row.key,"before"),index>1);
        item(popup,"Delete steps after this…",() -> deleteRange(id,row.key,"after"),index+1<model.size());
        popup.show(list,event.getX(),event.getY());
    }
    static String details(Engine.Row row) {
        return row.label+"\nOriginal operation: "+row.originalLabel+"\nTime: "+row.time
                +"\nIncluded in macro: "+row.included+"\nCheckpoint: "+row.pinned+"\nNote: "+row.note
                +"\n\n"+(row.macroCode==null?"No recorded macro code.":row.macroCode);
    }

    private void updateButtons() {
        int current=-1;for(int i=0;i<model.size();i++)if(model.get(i).current)current=i;
        boolean enabled=!busy&&current>=0,single=enabled&&list.getSelectedIndices().length<=1;
        int selected=selectedOrCurrent();
        undo.setEnabled(enabled&&(current>0||restoreProblem));redo.setEnabled(enabled&&current+1<model.size());
        go.setEnabled(single&&selected>=0&&(selected!=current||restoreProblem));preview.setEnabled(single);copy.setEnabled(single);
        keep.setEnabled(single&&selected>0&&!model.get(selected).saved);
        keep.setText(selected>=0&&selected<model.size()&&model.get(selected).pinned?"Unkeep":"Keep");
        int deletable=selectedKeys(true).length;delete.setEnabled(enabled&&deletable>0);
        delete.setText(deletable>1?"Delete ("+deletable+")":"Delete");
        macro.setEnabled(enabled&&deletable>0);clear.setEnabled(enabled&&model.size()>1);
        recordMacro.setEnabled(!busy);record.setEnabled(!busy);options.setEnabled(!busy);list.setEnabled(!busy);
    }
    private void refresh() {
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        RecordingControl.sync();recordMacro.setSelected(RecordingControl.enabled());
        ImagePlus image=WindowManager.getCurrentImage();int id=image==null?0:image.getID();
        Engine.View view=image==null?null:engine.view(id);
        int stamp=view==null?0:Objects.hash(id,view.title,view.untracked,view.dropped,rowsSignature(view));
        footer.setText("Memory "+mb(engine.usedBytes())+" / "+gb(engine.store.ramBudget)
                +(engine.store.diskEnabled?" · Disk "+mb(engine.diskBytes())+" / "+gb(engine.store.diskBudget):""));
        if(stamp==shownStamp&&id==imageID)return;
        Set<Long> selected=new HashSet<>();if(id==imageID)for(Engine.Row row:list.getSelectedValuesList())selected.add(row.key);
        shownStamp=stamp;imageID=id;restoreProblem=view!=null&&view.problem!=null;model.clear();updateButtons();
        if(image==null){header.setText("No image open");return;}
        if(view==null||view.untracked!=null){header.setText(image.getTitle()+": not tracked");return;}
        header.setText("<html>"+esc(view.title)+"<br><span style='font-weight:normal'>"+(view.rows.size()-1)+" step(s)"
                +(view.dropped>0?" · "+view.dropped+" step(s) removed":"")+"</span>"
                +(view.problem!=null?"<br><span style='color:#b00020'>"+esc(view.problem)+"</span>":"")+"</html>");
        int current=-1;for(int i=0;i<view.rows.size();i++){model.addElement(view.rows.get(i));if(view.rows.get(i).current)current=i;}
        list.clearSelection();for(int i=0;i<model.size();i++)if(selected.contains(model.get(i).key))list.addSelectionInterval(i,i);
        if(selected.isEmpty()&&current>=0)list.ensureIndexIsVisible(current);updateButtons();
    }
    private static int rowsSignature(Engine.View view) {
        int hash=1;for(Engine.Row row:view.rows)hash=31*hash+Objects.hash(row.key,row.label,row.note,row.current,row.pinned,row.saved,row.macroCode,row.included);
        return 31*hash+Objects.hashCode(view.problem);
    }
    private static String mb(long bytes){return IJ.d2s(bytes/1048576.0,bytes<10*1048576?1:0)+" MB";}
    private static String gb(long bytes){return IJ.d2s(bytes/1073741824.0,1)+" GB";}
    private static String esc(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    private final class HistoryList extends JList<Engine.Row> {
        private boolean popupShown;
        HistoryList(){super(model);setToolTipText("Right-click for step actions");}
        @Override protected void processMouseEvent(MouseEvent event) {
            if(event.getID()==MouseEvent.MOUSE_PRESSED)popupShown=false;
            if(event.isPopupTrigger()||SwingUtilities.isRightMouseButton(event)) {
                if(event.isPopupTrigger()&&!popupShown){popupShown=true;showPopup(event);}event.consume();return;
            }
            super.processMouseEvent(event);
        }
        @Override public String getToolTipText(MouseEvent event) {
            int index=rowAt(event.getPoint());if(index<0)return null;Engine.Row row=model.get(index);
            return "<html>"+esc(row.label)+(row.note.isEmpty()?"":"<br>"+esc(row.note).replace("\n","<br>"))
                    +"<br>"+(row.included?"Included in macro":"Excluded from macro; retained as a comment")
                    +"<br>"+(row.macroCode==null?"No recorded code":"Recorded code available")+"</html>";
        }
    }
    private final class RowRenderer extends JLabel implements ListCellRenderer<Engine.Row> {
        RowRenderer(){setOpaque(true);setBorder(BorderFactory.createEmptyBorder(4,6,4,6));}
        @Override public Component getListCellRendererComponent(JList<? extends Engine.Row> owner,Engine.Row row,int index,boolean selected,boolean focus) {
            String tags=(row.macroCode!=null?" [macro]":"")+(!row.included?" [excluded]":"")
                    +(row.saved?" [saved]":"")+(row.pinned&&index>0&&!row.saved?" [kept]":"")+(!row.note.isEmpty()?" [note]":"");
            setText((row.current?"▶ ":"    ")+index+"  "+row.label+tags+"   "+row.time);
            Font font=owner.getFont();setFont(row.current?font.deriveFont(Font.BOLD):row.future?font.deriveFont(Font.ITALIC):font);
            setBackground(selected?owner.getSelectionBackground():row.current?new Color(0xDCE8F8):owner.getBackground());
            setForeground(selected?owner.getSelectionForeground():row.current?Color.BLACK:row.future?Color.GRAY:owner.getForeground());return this;
        }
    }
}
