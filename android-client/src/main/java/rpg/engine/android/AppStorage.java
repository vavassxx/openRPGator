package rpg.engine.android;

import android.content.Context;
import android.net.Uri;
import java.io.*;

/** App-specific external storage. No SAF tree selection is required. */
public final class AppStorage {
    private final Context context;
    public AppStorage(Context context){ this.context=context.getApplicationContext(); }
    public File rootFile(){
        File root=context.getExternalFilesDir(null);
        if(root==null) root=context.getFilesDir();
        if(!root.exists()) root.mkdirs();
        return root;
    }
    public Uri root(){ return Uri.fromFile(rootFile()); }
    public void setRoot(Uri ignored) { }
    public boolean setRootAndPersist(Uri ignored, int flags) { return isWritable(); }
    public String description(){ return rootFile().getAbsolutePath(); }
    public boolean isWritable(){
        try { File f=new File(rootFile(),".openrpgator-write-test");
            try(FileOutputStream out=new FileOutputStream(f)){ out.write(1); }
            return f.delete();
        } catch(Exception e){ return false; }
    }
    public File privateLogFile(String name){ File d=new File(rootFile(),"logs"); d.mkdirs(); return new File(d,name); }
    public OutputStream createLog(String name) throws IOException { return new FileOutputStream(privateLogFile(name),true); }
    public void saveMap(String name, byte[] data) throws IOException { File d=new File(rootFile(),"maps"); if(!d.exists()&&!d.mkdirs()) throw new IOException("Could not create maps directory"); try(FileOutputStream out=new FileOutputStream(new File(d,safeName(name)))){out.write(data);} }
    public byte[] loadMap(String name) throws IOException { File f=new File(new File(rootFile(),"maps"),safeName(name)); if(!f.isFile()) return null; try(InputStream in=new FileInputStream(f)){return readAll(in);} }
    private static String safeName(String n){return n==null||n.trim().isEmpty()?"map.rmap":n.replaceAll("[\\\\/:*?\"<>|]","_");}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}
}
