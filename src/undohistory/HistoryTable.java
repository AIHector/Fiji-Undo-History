package undohistory;

import ij.Prefs;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** View indices never escape this table: every history action uses stable model identities. */
final class HistoryTable extends JTable {
    static final String[] NAMES={"Number","Step","Macro","Preview","Time","Dimension"};
    private final DefaultListModel<Engine.Row> rows;
    private final Map<Integer,TableColumn> columns=new LinkedHashMap<>();
    private final TableRowSorter<TableModel> sorter;
    private final Map<Long,BufferedImage> cache=new HashMap<>();
    private final Set<Long> loading=new HashSet<>();
    private final java.util.concurrent.ExecutorService loader=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"UndoHistory-thumbnails");t.setDaemon(true);return t;});
    private int imageID;
    private java.util.function.Consumer<MouseEvent> popupHandler;
    private boolean popupShown;
    private long generation;
    private boolean configuring;
    private final javax.swing.Timer hover;
    private Popup hoverPopup;
    private int hoverRow=-1;
    private final javax.swing.Timer saveTimer;

    HistoryTable(DefaultListModel<Engine.Row> rows) {
        this.rows=rows;
        setModel(new AbstractTableModel(){
            public int getRowCount(){return rows.size();}
            public int getColumnCount(){return NAMES.length;}
            public String getColumnName(int c){return NAMES[c];}
            public Class<?> getColumnClass(int c){return c==0?Long.class:c==3?Icon.class:c==4?Long.class:String.class;}
            public Object getValueAt(int r,int c){Engine.Row row=rows.get(r);switch(c){
                case 0:return number(r);case 1:return row.label;case 2:return row.macroCode==null?"":row.macroCode.trim();
                case 3:return null;case 4:return row.timestamp;default:return row.dimensions;
            }}
        });
        rows.addListDataListener(new ListDataListener(){
            public void intervalAdded(ListDataEvent e){changed();}public void intervalRemoved(ListDataEvent e){changed();}public void contentsChanged(ListDataEvent e){changed();}
            private void changed(){((AbstractTableModel)getModel()).fireTableDataChanged();}
        });
        setAutoResizeMode(AUTO_RESIZE_OFF);setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setRowSelectionAllowed(true);setColumnSelectionAllowed(false);setFillsViewportHeight(true);
        setRowHeight(Thumbnail.enabled()?52:26);setShowVerticalLines(true);setGridColor(new Color(0xE0E4EA));
        getTableHeader().setReorderingAllowed(true);
        int[] widths={75,220,330,80,105,280};
        for(int i=0;i<NAMES.length;i++){TableColumn column=getColumnModel().getColumn(i);column.setIdentifier(NAMES[i]);column.setPreferredWidth(widths[i]);columns.put(i,column);}
        sorter=new TableRowSorter<>(getModel());sorter.setSortable(3,false);setRowSorter(sorter);
        setDefaultRenderer(Object.class,new Cell());setDefaultRenderer(Integer.class,new Cell());setDefaultRenderer(Long.class,new Cell());setDefaultRenderer(Icon.class,new Cell());
        restoreColumns();
        saveTimer=new javax.swing.Timer(350,e->saveColumns());saveTimer.setRepeats(false);
        getColumnModel().addColumnModelListener(new TableColumnModelListener(){
            public void columnAdded(TableColumnModelEvent e){saveLater();} public void columnRemoved(TableColumnModelEvent e){saveLater();}
            public void columnMoved(TableColumnModelEvent e){if(e.getFromIndex()!=e.getToIndex())saveLater();}
            public void columnMarginChanged(ChangeEvent e){saveLater();}public void columnSelectionChanged(ListSelectionEvent e){}
        });
        sorter.addRowSorterListener(e->{hideHover();saveSort();});restoreSort();
        getTableHeader().addMouseListener(new MouseAdapter(){
            public void mousePressed(MouseEvent e){headerPopup(e);}public void mouseReleased(MouseEvent e){headerPopup(e);}
            private void headerPopup(MouseEvent e){if(e.isPopupTrigger()){JPopupMenu popup=new JPopupMenu();popup.add(columnsMenu());popup.show(getTableHeader(),e.getX(),e.getY());}}
        });
        hover=new javax.swing.Timer(450,e->showHover());hover.setRepeats(false);
        addMouseMotionListener(new MouseMotionAdapter(){public void mouseMoved(MouseEvent e){int view=rowAtPoint(e.getPoint()),column=columnAtPoint(e.getPoint());
            int target=view>=0&&column>=0&&convertColumnIndexToModel(column)==3?convertRowIndexToModel(view):-1;
            if(target!=hoverRow){hideHover();hoverRow=target;if(target>=0)hover.restart();}
        }});
        addMouseListener(new MouseAdapter(){public void mouseExited(MouseEvent e){hideHover();hoverRow=-1;}public void mousePressed(MouseEvent e){hideHover();}});
        getSelectionModel().addListSelectionListener(e->hideHover());
        setToolTipText("Right-click for step actions");
    }
    void setPopupHandler(java.util.function.Consumer<MouseEvent> handler){popupHandler=handler;}
    @Override protected void processMouseEvent(MouseEvent event){
        if(event.getID()==MouseEvent.MOUSE_PRESSED)popupShown=false;
        if(event.isPopupTrigger()||SwingUtilities.isRightMouseButton(event)||event.isControlDown()&&SwingUtilities.isLeftMouseButton(event)){
            hideHover();
            if(event.isPopupTrigger()&&!popupShown&&popupHandler!=null){popupShown=true;popupHandler.accept(event);}
            event.consume();return;
        }
        super.processMouseEvent(event);
    }
    void setSelectionInterval(int from,int to){clearSelection();addSelectionInterval(from,to);}
    Long number(int index){Engine.Row row=rows.get(index);if(row.shared)return null;return row.number-(Prefs.get(Engine.PREF+"numberFromSaved",false)?row.savedNumber:0);}
    void setImageID(int id){
        if(imageID!=id){imageID=id;generation++;cache.clear();loading.clear();hideHover();}
        Set<Long> retained=new HashSet<>();for(int i=0;i<rows.size();i++)retained.add(rows.get(i).key);cache.keySet().retainAll(retained);
    }
    void thumbnailsChanged(){generation++;cache.clear();loading.clear();setRowHeight(Thumbnail.enabled()?52:26);hideHover();repaint();}
    void close(){generation++;hover.stop();saveTimer.stop();hideHover();loader.shutdownNow();saveColumns();}
    void addListSelectionListener(ListSelectionListener listener){getSelectionModel().addListSelectionListener(listener);}
    int[] getSelectedIndices(){int[] indices=getSelectedRows();for(int i=0;i<indices.length;i++)indices[i]=convertRowIndexToModel(indices[i]);Arrays.sort(indices);return indices;}
    int getSelectedIndex(){int selected=getSelectedRow();return selected<0?-1:convertRowIndexToModel(selected);}
    boolean isSelectedIndex(int index){int view=convertRowIndexToView(index);return view>=0&&isRowSelected(view);}
    void setSelectedIndex(int index){clearSelection();addSelectionInterval(index,index);}
    void addSelectionInterval(int from,int to){for(int i=from;i<=to;i++){int view=convertRowIndexToView(i);if(view>=0)addRowSelectionInterval(view,view);}}
    void setSelectedIndices(int[] indices){clearSelection();for(int index:indices)addSelectionInterval(index,index);}
    List<Engine.Row> getSelectedValuesList(){List<Engine.Row> selected=new ArrayList<>();for(int index:getSelectedIndices())if(index<rows.size())selected.add(rows.get(index));return selected;}
    void ensureIndexIsVisible(int index){int view=convertRowIndexToView(index);if(view>=0)scrollRectToVisible(getCellRect(view,0,true));}
    int rowAt(Point point){int view=rowAtPoint(point);return view<0?-1:convertRowIndexToModel(view);}

    JMenu columnsMenu(){
        JMenu menu=new JMenu("Columns");
        for(int i=0;i<NAMES.length;i++){final int index=i;JCheckBoxMenuItem choice=new JCheckBoxMenuItem(NAMES[i],visible(i));
            choice.addActionListener(e->{if(choice.isSelected())addColumn(columns.get(index));else if(getColumnCount()>1)removeColumn(columns.get(index));saveColumns();});menu.add(choice);}
        menu.addSeparator();JCheckBoxMenuItem saved=new JCheckBoxMenuItem("Number from last saved state",Prefs.get(Engine.PREF+"numberFromSaved",false));
        saved.addActionListener(e->{Prefs.set(Engine.PREF+"numberFromSaved",saved.isSelected());((AbstractTableModel)getModel()).fireTableDataChanged();Prefs.savePreferences();});menu.add(saved);
        JMenuItem reset=new JMenuItem("Restore default columns");reset.addActionListener(e->{configuring=true;for(TableColumn c:columns.values())if(visible(c.getModelIndex()))removeColumn(c);for(TableColumn c:columns.values())addColumn(c);configuring=false;saveColumns();});menu.add(reset);return menu;
    }
    private boolean visible(int index){for(int i=0;i<getColumnCount();i++)if(getColumnModel().getColumn(i).getModelIndex()==index)return true;return false;}
    private void restoreColumns(){
        configuring=true;String order=Prefs.get(Engine.PREF+"tableColumns","0,1,2,3,4,5");
        while(getColumnCount()>0)removeColumn(getColumnModel().getColumn(0));Set<Integer> seen=new HashSet<>();
        for(String token:order.split(","))try{int i=Integer.parseInt(token);if(columns.containsKey(i)&&seen.add(i))addColumn(columns.get(i));}catch(NumberFormatException ignored){}
        if(getColumnCount()==0)addColumn(columns.get(1));
        for(int i=0;i<NAMES.length;i++){int width=(int)Prefs.get(Engine.PREF+"columnWidth."+i,columns.get(i).getPreferredWidth());columns.get(i).setPreferredWidth(Math.max(35,Math.min(1600,width)));}
        configuring=false;
    }
    private void saveLater(){if(!configuring&&saveTimer!=null)saveTimer.restart();}
    private void saveColumns(){if(configuring)return;StringJoiner order=new StringJoiner(",");for(int i=0;i<getColumnCount();i++){TableColumn c=getColumnModel().getColumn(i);order.add(""+c.getModelIndex());Prefs.set(Engine.PREF+"columnWidth."+c.getModelIndex(),c.getWidth());}Prefs.set(Engine.PREF+"tableColumns",order.toString());Prefs.savePreferences();}
    private void saveSort(){StringJoiner keys=new StringJoiner(",");for(RowSorter.SortKey key:sorter.getSortKeys())keys.add(key.getColumn()+":"+key.getSortOrder().name());Prefs.set(Engine.PREF+"tableSort",keys.toString());}
    private void restoreSort(){List<RowSorter.SortKey> keys=new ArrayList<>();for(String token:Prefs.get(Engine.PREF+"tableSort","").split(","))try{String[] parts=token.split(":");int i=Integer.parseInt(parts[0]);if(i>=0&&i<NAMES.length&&i!=3)keys.add(new RowSorter.SortKey(i,SortOrder.valueOf(parts[1])));}catch(RuntimeException ignored){}sorter.setSortKeys(keys);}
    private BufferedImage thumbnail(Engine.Row row){
        if(!Thumbnail.enabled()||row.shared)return null;BufferedImage image=cache.get(row.key);if(image!=null)return image;
        if(row.thumbnail!=null){cache.put(row.key,row.thumbnail);return row.thumbnail;}
        if(loading.add(row.key)){final int id=imageID;final long request=generation;loader.execute(()->{
            BufferedImage result=null;try{result=Engine.get().thumbnailOf(id,row.key);}catch(RuntimeException ignored){}
            final BufferedImage loaded=result;SwingUtilities.invokeLater(()->{if(request!=generation)return;loading.remove(row.key);if(loaded!=null)cache.put(row.key,loaded);repaint();});
        });}return null;
    }
    private void hideHover(){if(hover!=null)hover.stop();if(hoverPopup!=null){hoverPopup.hide();hoverPopup=null;}}
    private void showHover(){
        if(!isShowing()||hoverRow<0||hoverRow>=rows.size())return;Engine.Row row=rows.get(hoverRow);BufferedImage image=thumbnail(row);if(image==null)return;
        JLabel label=new JLabel(new ImageIcon(image));label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.GRAY),BorderFactory.createEmptyBorder(6,6,6,6)));
        label.setOpaque(true);label.setBackground(Color.WHITE);int view=convertRowIndexToView(hoverRow);if(view<0)return;
        Point point=getLocationOnScreen();Rectangle rect=getCellRect(view,0,true);Dimension screen=Toolkit.getDefaultToolkit().getScreenSize();
        hoverPopup=PopupFactory.getSharedInstance().getPopup(this,label,Math.max(0,Math.min(point.x+120,screen.width-image.getWidth()-16)),Math.max(0,Math.min(point.y+rect.y+rect.height,screen.height-image.getHeight()-16)));hoverPopup.show();
    }
    @Override public String getToolTipText(MouseEvent e){int row=rowAt(e.getPoint()),col=columnAtPoint(e.getPoint());if(row<0||col<0)return null;Engine.Row value=rows.get(row);if(convertColumnIndexToModel(col)==3)return Thumbnail.enabled()?null:"Previews are disabled in Settings";
        String text=convertColumnIndexToModel(col)==2?(value.macroCode==null?"No recorded macro code":value.macroCode):value.label+"\n"+value.note+(value.recorderOnly?"\nRecorded action; restoring this row restores the retained image, not external effects.":"");
        return "<html>"+text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\n","<br>")+"</html>";}
    private final class Cell extends DefaultTableCellRenderer{
        public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int view,int column){
            super.getTableCellRendererComponent(owner,value,selected,focus,view,column);setIcon(null);int index=convertRowIndexToModel(view),kind=convertColumnIndexToModel(column);Engine.Row row=rows.get(index);
            setFont(owner.getFont().deriveFont(row.current?Font.BOLD:row.future?Font.ITALIC:Font.PLAIN));setHorizontalAlignment(kind==0?SwingConstants.RIGHT:SwingConstants.LEFT);
            if(!selected){setBackground(row.current?new Color(0xDCE8F8):index%2==0?Color.WHITE:new Color(0xF6F8FA));setForeground(row.future?Color.GRAY:owner.getForeground());}
            if(kind==1)setText((row.current?"▶ ":"")+row.label+(row.shared?" [shared context]":"")+(row.recorderOnly?" [recorded action]":"")+(row.saved?" [saved]":row.pinned&&index>0?" [kept]":"")+(!row.included?" [excluded]":"")+(!row.note.isEmpty()?" [note]":""));
            if(kind==2){setText(value.toString().replace('\n',' '));setFont(new Font(Font.MONOSPACED,Font.PLAIN,owner.getFont().getSize()));}
            if(kind==3){setText(row.shared?"—":Thumbnail.enabled()?"…":"Off");BufferedImage image=thumbnail(row);if(image!=null){setText("");setIcon(new ImageIcon(Thumbnail.fit(image,44)));}setHorizontalAlignment(SwingConstants.CENTER);}
            if(kind==4)setText(row.time);if(kind==5&&row.shared)setText("Shared setting · image unchanged");return this;
        }
    }
}
