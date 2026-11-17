package undohistory;

import ij.Prefs;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** A single configurable row; the overflow menu always retains access to customization. */
final class HistoryToolbar extends JPanel {
    private final Map<String,JComponent> actions=new LinkedHashMap<>();
    private final List<String> order=new ArrayList<>();
    private final List<JComponent> placed=new ArrayList<>();
    private final JButton more=new JButton(">>");
    private static final String DEFAULT="undo,redo,|,reference,restore,|,go,preview,copy,|,keep,macro,delete,clear,|,options";
    HistoryToolbar(Map<String,JComponent> buttons){
        actions.putAll(buttons);for(JComponent action:actions.values())action.setInheritsPopupMenu(true);more.setInheritsPopupMenu(true);setLayout(null);setBorder(BorderFactory.createEmptyBorder(3,6,3,6));
        readOrder(Prefs.get(Engine.PREF+"toolbarOrder",DEFAULT));
        for(Map.Entry<String,JComponent> entry:actions.entrySet())if(entry.getValue() instanceof JButton){
            JButton button=(JButton)entry.getValue();String custom=Prefs.get(Engine.PREF+"toolbarLabel."+entry.getKey(),"");
            if(!custom.isEmpty()){button.putClientProperty("customLabel",custom);if(!Prefs.get(Engine.PREF+"buttonStyle","both").equals("icons"))button.setText(custom);}
        }
        more.setToolTipText("More actions and Customize toolbar…");more.getAccessibleContext().setAccessibleName("More toolbar actions");
        more.setMargin(new Insets(3,7,3,7));more.addActionListener(e->showOverflow());
        setComponentPopupMenu(configurationMenu());rebuild();
    }
    private void readOrder(String value){order.clear();Set<String> seen=new HashSet<>();for(String id:value.split(","))if(id.equals("|")||actions.containsKey(id)&&seen.add(id))order.add(id);}
    private void rebuild(){removeAll();placed.clear();for(String id:order){JComponent c;if(id.equals("|")){c=new JSeparator(SwingConstants.VERTICAL);c.setPreferredSize(new Dimension(10,26));}else c=actions.get(id);placed.add(c);add(c);}add(more);revalidate();repaint();}
    @Override public Dimension getPreferredSize(){int height=36;for(JComponent c:actions.values())height=Math.max(height,c.getPreferredSize().height+6);return new Dimension(200,height);}
    @Override public void doLayout(){
        int x=6,height=getHeight()-6,end=getWidth()-more.getPreferredSize().width-10;boolean overflow=false;
        for(JComponent c:placed){Dimension d=c.getPreferredSize();boolean fits=!overflow&&x+d.width<=end;c.setVisible(fits);if(fits){c.setBounds(x,3,d.width,height);x+=d.width+3;}else overflow=true;}
        more.setBounds(Math.max(0,getWidth()-more.getPreferredSize().width-4),3,more.getPreferredSize().width,height);
    }
    private JPopupMenu configurationMenu(){JPopupMenu menu=new JPopupMenu();JMenuItem edit=new JMenuItem("Customize toolbar…");edit.addActionListener(e->customize());menu.add(edit);return menu;}
    private void showOverflow(){JPopupMenu menu=new JPopupMenu();for(int i=0;i<order.size();i++)if(!placed.get(i).isVisible()){
        String id=order.get(i);if(id.equals("|")){if(menu.getComponentCount()>0)menu.addSeparator();continue;}JComponent action=actions.get(id);
        if(action instanceof JButton){JButton button=(JButton)action;String label=(String)button.getClientProperty("customLabel");if(label==null)label=(String)button.getClientProperty("actionLabel");JMenuItem item=new JMenuItem(label,button.getIcon());item.setEnabled(button.isEnabled());item.addActionListener(e->button.doClick());menu.add(item);}
        else if(action instanceof JComboBox){JComboBox<?> combo=(JComboBox<?>)action;JMenu choices=new JMenu("Reset image reference");choices.setEnabled(combo.isEnabled());for(int j=0;j<combo.getItemCount();j++){final int index=j;JRadioButtonMenuItem choice=new JRadioButtonMenuItem(combo.getItemAt(j).toString(),j==combo.getSelectedIndex());choice.addActionListener(e->combo.setSelectedIndex(index));choices.add(choice);}menu.add(choices);}
    }if(menu.getComponentCount()>0)menu.addSeparator();JMenuItem edit=new JMenuItem("Customize toolbar…");edit.addActionListener(e->customize());menu.add(edit);menu.show(more,0,more.getHeight());}
    void customize(){
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(this),"Customize Undo History toolbar",Dialog.ModalityType.MODELESS);
        DefaultListModel<String> model=new DefaultListModel<>();for(String id:order)model.addElement(id);
        JList<String> list=new JList<>(model);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){super.getListCellRendererComponent(l,v,i,s,f);setText(label(v.toString()));return this;}});
        JComboBox<String> available=new JComboBox<>(actions.keySet().toArray(new String[0]));available.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){super.getListCellRendererComponent(l,v,i,s,f);setText(v==null?"":label(v.toString()));return this;}});
        JPanel controls=new JPanel();controls.setLayout(new BoxLayout(controls,BoxLayout.Y_AXIS));controls.add(available);
        Runnable apply=()->{order.clear();for(int i=0;i<model.size();i++)order.add(model.get(i));Prefs.set(Engine.PREF+"toolbarOrder",String.join(",",order));Prefs.savePreferences();rebuild();};
        JButton add=new JButton("Insert action");add.addActionListener(e->{String id=(String)available.getSelectedItem();if(id!=null&&!model.contains(id)){int i=list.getSelectedIndex();model.add(i<0?model.size():i+1,id);apply.run();}});controls.add(add);
        JButton separator=new JButton("Insert separator");separator.addActionListener(e->{int i=list.getSelectedIndex();model.add(i<0?model.size():i+1,"|");apply.run();});controls.add(separator);
        JButton remove=new JButton("Hide / remove");remove.addActionListener(e->{int i=list.getSelectedIndex();if(i>=0){model.remove(i);if(!model.isEmpty())list.setSelectedIndex(Math.min(i,model.size()-1));apply.run();}});controls.add(remove);
        for(int delta:new int[]{-1,1}){JButton move=new JButton(delta<0?"Move left / up":"Move right / down");move.addActionListener(e->{int i=list.getSelectedIndex(),to=i+delta;if(i>=0&&to>=0&&to<model.size()){String id=model.remove(i);model.add(to,id);list.setSelectedIndex(to);apply.run();}});controls.add(move);}
        JButton rename=new JButton("Edit button label…");rename.addActionListener(e->{String id=list.getSelectedValue();if(id==null||!(actions.get(id) instanceof JButton))return;JButton button=(JButton)actions.get(id);String text=JOptionPane.showInputDialog(dialog,"Button label (blank restores its default):",label(id));if(text==null)return;text=text.trim();Prefs.set(Engine.PREF+"toolbarLabel."+id,text);button.putClientProperty("customLabel",text.isEmpty()?null:text);button.getAccessibleContext().setAccessibleName(text.isEmpty()?(String)button.getClientProperty("actionLabel"):text);if(!Prefs.get(Engine.PREF+"buttonStyle","both").equals("icons"))button.setText(text.isEmpty()?(String)button.getClientProperty("actionLabel"):text);Prefs.savePreferences();list.repaint();revalidate();repaint();});controls.add(rename);
        JButton reset=new JButton("Restore default toolbar");reset.addActionListener(e->{readOrder(DEFAULT);model.clear();for(String id:order)model.addElement(id);apply.run();});controls.add(reset);
        JPanel body=new JPanel(new BorderLayout(8,8));body.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));body.add(new JScrollPane(list),BorderLayout.CENTER);body.add(controls,BorderLayout.EAST);
        JLabel hint=new JLabel("Changes apply immediately. Right-click the toolbar or use >> to edit it again.");body.add(hint,BorderLayout.NORTH);
        JButton done=new JButton("Done");done.addActionListener(e->dialog.dispose());JPanel bottom=new JPanel(new FlowLayout(FlowLayout.RIGHT));bottom.add(done);body.add(bottom,BorderLayout.SOUTH);
        dialog.add(body);dialog.setSize(720,460);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    private String label(String id){if(id.equals("|"))return "──────── separator ────────";JComponent component=actions.get(id);if(component instanceof JButton){Object custom=component.getClientProperty("customLabel");return String.valueOf(custom==null?component.getClientProperty("actionLabel"):custom);}return "Reset image reference";}
}
