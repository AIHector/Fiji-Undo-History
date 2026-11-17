package undohistory;

import ij.Macro;
import ij.plugin.frame.Recorder;

/** Captures real options or Recorder output; a command label alone is never executable evidence. */
final class MacroCapture {
    private static long generation;
    private static final java.util.concurrent.atomic.AtomicLong ORDER = new java.util.concurrent.atomic.AtomicLong();
    static long nextOrder(){return ORDER.incrementAndGet();}
    static final class Entry { final String code;final long order=nextOrder();Entry(String code){this.code=code;} }
    static final int MAX_TEXT = 1024 * 1024;

    static final class Ticket {
        final String direct;
        final long generation, order = nextOrder();
        Ticket(String direct,long generation) { this.direct=direct;this.generation=generation; }
    }

    static synchronized void invalidate() { generation++; }

    static synchronized Ticket begin(String command) {
        if (!RecordingControl.enabled()) return null;
        String options = Macro.getOptions();
        // An actual option string is executable evidence; a command name on its own is not.
        String direct = options == null ? null : "run(\"" + quote(command) + "\", \"" + quote(options) + "\");\n";
        return new Ticket(direct,generation);
    }

    static synchronized String finish(Ticket t) {
        if (t == null || !RecordingControl.enabled()) return null;
        if (t.generation != generation) return null;
        // Native output is consumed from the image-routed journal, never from a global text suffix.
        return t.direct;
    }

    private static Recorder feedRecorder;
    private static String feedText = "", feedPending = "";
    private static int pendingOwner;
    private static int feedOwner;
    private static final java.util.Map<Integer,java.util.List<Entry>> routed = new java.util.HashMap<>();
    private static final java.util.Map<Integer,java.util.List<Entry>> consumed = new java.util.HashMap<>();
    private static final java.util.List<Entry> shared = new java.util.ArrayList<>();

    static synchronized void resetFeed() {
        feedRecorder = Recorder.getInstance();
        feedText = feedRecorder == null ? "" : feedRecorder.getText();
        ij.ImagePlus image = ij.WindowManager.getCurrentImage();
        feedOwner = image == null ? 0 : image.getID();
        feedPending="";routed.clear(); consumed.clear(); shared.clear();
    }

    /** Called before an image activation or command changes the owner of subsequent recorder output. */
    static synchronized void observe(int imageID) { readFeed(); feedOwner = imageID; }

    static synchronized String drain(int imageID) {
        readFeed();
        java.util.List<Entry> entries = routed.remove(imageID);
        consumed.put(imageID,entries);
        if(entries==null||entries.isEmpty())return null;StringBuilder code=new StringBuilder();for(Entry entry:entries)code.append(entry.code);return code.toString();
    }

    static synchronized java.util.List<Entry> drainShared() {
        readFeed();java.util.List<Entry> result = new java.util.ArrayList<>(shared);shared.clear();return result;
    }
    static synchronized long consumedOrder(int id,int index){java.util.List<Entry> entries=consumed.get(id);return entries!=null&&index<entries.size()?entries.get(index).order:nextOrder();}

    static synchronized void forget(int imageID) { routed.remove(imageID);consumed.remove(imageID);if(feedOwner==imageID)feedOwner=0; }

    private static void readFeed() {
        Recorder recorder = Recorder.getInstance();
        if (!RecordingControl.enabled() || recorder == null || Recorder.scriptMode()) { resetFeed(); return; }
        String after = recorder.getText();
        if (recorder != feedRecorder || after == null || !after.startsWith(feedText)) { resetFeed(); return; }
        String delta = after.substring(feedText.length());feedText = after;
        String pending=feedPending+delta;int continuationOwner=feedPending.isEmpty()?feedOwner:pendingOwner;
        java.util.List<String> complete=instructions(pending,false);int length=0;for(String instruction:complete)length+=instruction.length();
        feedPending=pending.substring(length);pendingOwner=complete.isEmpty()?continuationOwner:feedOwner;
        int index=0;
        for (String instruction : complete) {
            int owner=index++==0?continuationOwner:feedOwner;
            String text = instruction.trim();
            if (navigation(text)) { routeSelection(text); continue; }
            Entry entry=new Entry(instruction);
            if (isShared(text)) shared.add(entry);
            else if (owner != 0) routed.computeIfAbsent(owner,id -> new java.util.ArrayList<>()).add(entry);
        }
        if(!complete.isEmpty())pendingOwner=feedOwner;
    }

    static boolean navigation(String text) {return text.startsWith("selectImage(") || text.startsWith("selectWindow(");}
    private static void routeSelection(String text) {
        int first=text.indexOf('('),last=text.lastIndexOf(')');if(last<=first)return;
        String value=text.substring(first+1,last).trim();ij.ImagePlus image=null;
        if(value.startsWith("\"")&&value.endsWith("\"")) image=ij.WindowManager.getImage(value.substring(1,value.length()-1).replace("\\\"","\"").replace("\\\\","\\"));
        else try{image=ij.WindowManager.getImage(Integer.parseInt(value));}catch(NumberFormatException ignored){}
        if(image!=null&&Engine.supportsHistory(image))feedOwner=image.getID();
        else if(text.startsWith("selectImage("))feedOwner=0;
    }

    /** Conservative whitelist: image-local setOption variants must never be broadcast as global settings. */
    static boolean isShared(String code) {
        String text=code.trim();if(text.startsWith("//"))text=text.substring(2).trim();
        if(text.startsWith("setForegroundColor(")||text.startsWith("setBackgroundColor(")||text.startsWith("setTool("))return true;
        if(text.startsWith("setOption(")) {
            String option=firstString(text).toLowerCase(java.util.Locale.ROOT);
            return java.util.Arrays.asList("blackbackground","scaleconversions","calibrateconversions","bicubic","antialiasedtext","openusingplugins","disableundo","supportmacroundo","fullrange16bitinversions","copycolumnheaders","debugmode").contains(option);
        }
        return text.startsWith("run(") && java.util.Arrays.asList("Set Measurements...","Conversions...","Colors...","Line Width...").contains(firstString(text));
    }
    private static String firstString(String code){int a=code.indexOf('"');if(a<0)return "";int b=code.indexOf('"',a+1);return b>a?code.substring(a+1,b):"";}

    static String appended(String before, String after) {
        if (before == null || after == null || after.length() > MAX_TEXT || !after.startsWith(before)) return null;
        String code = after.substring(before.length()).trim();
        // Comments alone (e.g. opening an adjustment dialog) are not a recorded operation.
        boolean executable = false;
        for (String line : code.split("\\r?\\n"))
            if (!line.trim().isEmpty() && !line.trim().startsWith("//")) executable = true;
        return executable ? code + "\n" : null;
    }

    /** Split complete top-level instructions; keep quoted semicolons and multi-line definitions intact. */
    static java.util.List<String> instructions(String code) { return instructions(code,true); }
    static java.util.List<String> instructions(String code,boolean includeIncomplete) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int start=0, round=0, square=0, curly=0; boolean quoted=false, escaped=false, comment=false;
        for(int i=0;i<code.length();i++) {
            char c=code.charAt(i);
            if(comment){if(c=='\n'){comment=false;if(round==0&&square==0&&curly==0){result.add(code.substring(start,i+1));start=i+1;}}continue;}
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"'){quoted=true;continue;}
            if(c=='/'&&i+1<code.length()&&code.charAt(i+1)=='/'){comment=true;i++;continue;}
            if(c=='(')round++;if(c==')')round--;if(c=='[')square++;if(c==']')square--;if(c=='{')curly++;if(c=='}')curly--;
            if(c==';'&&round==0&&square==0&&curly==0){int end=i+1;if(end<code.length()&&code.charAt(end)=='\r')end++;if(end<code.length()&&code.charAt(end)=='\n')end++;result.add(code.substring(start,end));start=end;i=end-1;}
        }
        if(includeIncomplete&&start<code.length()&&!code.substring(start).trim().isEmpty())result.add(code.substring(start));
        return result;
    }

    static String describe(String code) {
        for (String line : code.split("\\r?\\n")) {
            String text = line.trim();
            if (text.startsWith("//")) text = text.substring(2).trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("run(\"")) {
                int end = text.indexOf('"', 5);
                if (end > 5) return text.substring(5, end);
            }
            int bracket = text.indexOf('(');
            String method = bracket > 0 ? text.substring(0, bracket) : text;
            String words = method.replaceAll("([a-z])([A-Z])", "$1 $2");
            return words.substring(0, 1).toUpperCase() + words.substring(1);
        }
        return "Recorded action";
    }

    static String quote(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
}
