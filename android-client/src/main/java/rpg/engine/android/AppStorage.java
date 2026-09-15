package rpg.engine.android;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;

/** User-selectable root for generated application data. */
public final class AppStorage {
    private static final String PREF = "openrpgator.storage.v1";
    private static final String KEY = "tree_uri";
    private final Context context;

    public AppStorage(Context context){ this.context=context.getApplicationContext(); }

    public Uri root(){
        String s=context.getSharedPreferences(PREF,0).getString(KEY,null);
        return s==null?null:Uri.parse(s);
    }

    public void setRoot(Uri uri){
        if(uri == null) {
            context.getSharedPreferences(PREF,0).edit().remove(KEY).commit();
            return;
        }
        context.getSharedPreferences(PREF,0).edit().putString(KEY,uri.toString()).commit();
    }

    /** Persists the SAF grant and remembers the selected tree. */
    public boolean setRootAndPersist(Uri uri, int flags) {
        if(uri == null) return false;
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            context.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
            // Some providers grant only a transient permission. We still remember the URI;
            // isWritable() will report whether it can actually be used.
        }
        setRoot(uri);
        return isWritable();
    }

    public String description(){
        Uri u=root();
        return u==null?"App-private storage (default)":u.toString();
    }

    /** Verifies that the selected tree is still writable. */
    public boolean isWritable() {
        Uri tree=root();
        if(tree==null) return true;
        try {
            Uri dir=ensureDirectory(tree,"logs");
            Uri file=findChild(dir,".openrpgator-write-test");
            if(file==null) file=DocumentsContract.createDocument(context.getContentResolver(),dir,"text/plain",".openrpgator-write-test");
            if(file==null) return false;
            try(OutputStream out=context.getContentResolver().openOutputStream(file,"w")){ if(out==null) return false; out.write(1); out.flush(); }
            DocumentsContract.deleteDocument(context.getContentResolver(),file);
            return true;
        } catch(Exception e) { return false; }
    }

    public File privateLogFile(String name){
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    /** Opens/creates an appendable log file under the selected root/logs directory. */
    public OutputStream createLog(String name) throws IOException {
        Uri tree=root();
        if(tree==null) return new FileOutputStream(privateLogFile(name), true);
        Uri dir = ensureDirectory(tree, "logs");
        Uri file = findChild(dir, name);
        if(file == null) file = DocumentsContract.createDocument(context.getContentResolver(),dir,"text/plain",name);
        if(file==null) throw new IOException("Could not create log file");
        final Uri logFile = file;
        try {
            OutputStream out = context.getContentResolver().openOutputStream(logFile,"wa");
            if(out!=null) return out;
        } catch(Exception ignored) {
            // Some DocumentsProvider implementations do not support append mode.
        }
        // Portable fallback: read the current document and rewrite it with the caller's bytes appended.
        // The returned stream is a buffer; close() commits the complete document.
        final byte[] previous;
        try(InputStream in=context.getContentResolver().openInputStream(logFile)) {
            previous = in==null ? new byte[0] : readAll(in);
        }
        return new FilterOutputStream(new ByteArrayOutputStream()) {
            @Override public void close() throws IOException {
                ByteArrayOutputStream buffer=(ByteArrayOutputStream)out;
                try(OutputStream target=context.getContentResolver().openOutputStream(logFile,"w")) {
                    if(target==null) throw new IOException("Could not open log file for rewrite");
                    target.write(previous);
                    buffer.writeTo(target);
                }
                super.close();
            }
        };
    }

    /** Saves a map under the selected root/maps directory. Existing files are replaced. */
    public void saveMap(String name, byte[] data) throws IOException {
        Uri tree=root();
        if(tree==null){
            File dir=new File(context.getFilesDir(),"maps");
            if(!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create maps directory");
            try(FileOutputStream out=new FileOutputStream(new File(dir,safeName(name)))){ out.write(data); }
            return;
        }
        Uri dir=ensureDirectory(tree,"maps");
        String fileName=safeName(name);
        Uri file=findChild(dir,fileName);
        if(file!=null) DocumentsContract.deleteDocument(context.getContentResolver(),file);
        file=DocumentsContract.createDocument(context.getContentResolver(),dir,"application/octet-stream",fileName);
        if(file==null) throw new IOException("Could not create map file");
        try(OutputStream out=context.getContentResolver().openOutputStream(file,"w")){
            if(out==null) throw new IOException("Could not open map file");
            out.write(data);
        }
    }

    /** Loads a map from root/maps. Returns null when the file does not exist. */
    public byte[] loadMap(String name) throws IOException {
        Uri tree=root();
        String fileName=safeName(name);
        if(tree==null){
            File file=new File(new File(context.getFilesDir(),"maps"),fileName);
            if(!file.isFile()) return null;
            return readAll(new FileInputStream(file));
        }
        Uri dir=ensureDirectory(tree,"maps");
        Uri file=findChild(dir,fileName);
        if(file==null) return null;
        try(InputStream in=context.getContentResolver().openInputStream(file)){
            if(in==null) throw new IOException("Could not open map file");
            return readAll(in);
        }
    }

    private Uri ensureDirectory(Uri parent,String name) throws IOException {
        Uri existing=findChild(parent,name);
        if(existing!=null) return existing;
        Uri created=DocumentsContract.createDocument(context.getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,name);
        if(created==null) throw new IOException("Could not create directory: "+name);
        return created;
    }

    private Uri findChild(Uri parent,String name) throws IOException {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getTreeDocumentId(parent));
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try(Cursor c=context.getContentResolver().query(children,projection,DocumentsContract.Document.COLUMN_DISPLAY_NAME+"=?",new String[]{name},null)){
            if(c!=null && c.moveToFirst()){
                String id=c.getString(0);
                return DocumentsContract.buildDocumentUriUsingTree(parent,id);
            }
            return null;
        }
    }

    private static String safeName(String name){
        if(name==null || name.trim().isEmpty()) return "map.rmap";
        return name.replaceAll("[\\\\/:*?\"<>|]","_");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))!=-1) out.write(buf,0,n);
        return out.toByteArray();
    }
}
