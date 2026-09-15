package rpg.engine.android;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;

/** User-selectable root for generated application data. */
public final class AppStorage {
    private static final String PREF = "openrpgator.storage.v1";
    private static final String KEY = "tree_uri";
    private final Context context;
    public AppStorage(Context context){ this.context=context.getApplicationContext(); }
    public Uri root(){ String s=context.getSharedPreferences(PREF,0).getString(KEY,null); return s==null?null:Uri.parse(s); }
    public void setRoot(Uri uri){ context.getSharedPreferences(PREF,0).edit().putString(KEY,uri.toString()).apply(); }
    public String description(){ Uri u=root(); return u==null?"App-private storage (default)":u.toString(); }
    public OutputStream createLog(String name) throws IOException {
        Uri tree=root();
        if(tree==null) return context.openFileOutput(name,Context.MODE_APPEND);
        Uri file=DocumentsContract.createDocument(context.getContentResolver(),tree,"text/plain",name);
        if(file==null) throw new IOException("Could not create log file");
        return context.getContentResolver().openOutputStream(file,"wa");
    }
}
