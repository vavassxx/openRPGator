package rpg.engine.android;

import android.content.Context;
import android.net.Uri;

import java.io.*;
import java.util.Arrays;
import java.util.List;

/**
 * App-specific external storage. The app data root is {@link Context#getExternalFilesDir}
 * with a {@link Context#getFilesDir()} fallback; no SAF tree selection is required.
 *
 * The folder is delivered *outside* the app through {@link OpenRpgatorDocumentsProvider}
 * (system picker sidebar entry + count as a browsable document root), and
 * {@link #providerTreeUri()} lets the picker land directly inside it.
 */
public final class AppStorage {
    private final Context context;

    public AppStorage(Context context){ this.context=context.getApplicationContext(); }

    // ── Root ─────────────────────────────────────────────────────
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

    /**
     * SAF tree URI for the app data folder, used as {@code EXTRA_INITIAL_URI} when opening
     * the system picker so it lands directly inside the application folder.
     */
    public Uri providerTreeUri(){
        return android.provider.DocumentsContract.buildTreeDocumentUri(
                OpenRpgatorDocumentsProvider.AUTHORITY, rootFile().getAbsolutePath());
    }

    // ── Data dir layout ─────────────────────────────────────────
    // data/host      server content: *.rmap maps, *.lua scripts, *.pak packs (same as desktop)
    // data/pakcache  client-side cache of packs downloaded from remote servers
    public File dataDir(){
        File d = new File(rootFile(), "data");
        if(!d.exists()) d.mkdirs();
        return d;
    }
    public File hostDir(){
        File d = new File(dataDir(), "host");
        if(!d.exists()) d.mkdirs();
        return d;
    }
    public File pakCacheDir(){
        File d = new File(dataDir(), "pakcache");
        if(!d.exists()) d.mkdirs();
        return d;
    }

    // ── Logs ─────────────────────────────────────────────────────
    public File privateLogFile(String name){ File d=new File(rootFile(),"logs"); d.mkdirs(); return new File(d,name); }
    public OutputStream createLog(String name) throws IOException { return new FileOutputStream(privateLogFile(name),true); }

    // ── Maps (server host folder) ────────────────────────────────
    private File mapsDir(){ return hostDir(); }
    public void saveMap(String name, byte[] data) throws IOException { try(FileOutputStream out=new FileOutputStream(new File(mapsDir(),safeName(name)))){out.write(data);} }
    public byte[] loadMap(String name) throws IOException { File f=new File(mapsDir(),safeName(name)); if(!f.isFile()) return null; try(InputStream in=new FileInputStream(f)){return readAll(in);} }
    public File mapFile(String name) { return new File(mapsDir(), safeName(name)); }
    public String[] mapNames() {
        File[] files = mapsDir().listFiles((dir, n) -> n.endsWith(".rmap"));
        if (files == null || files.length == 0) return new String[0];
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        return names;
    }

    /** Sorted {@code *.pak} files inside the host folder (server-streamed content). */
    public File[] hostPaks() {
        File[] files = hostDir().listFiles((dir, n) -> n.endsWith(".pak"));
        if (files == null || files.length == 0) return new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files;
    }

    /**
     * Sorted {@code *.pak} files for the client atlas: host packs first (local server content),
     * then packs cached from remote servers. Duplicate names prefer the host copy.
     */
    public File[] pakFiles() {
        List<File> out = new java.util.ArrayList<>();
        for (File f : hostPaks()) out.add(f);
        File[] cache = pakCacheDir().listFiles((dir, n) -> n.endsWith(".pak"));
        if (cache != null) {
            Arrays.sort(cache, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : cache) {
                boolean dup = false;
                for (File g : out) if (g.getName().equals(f.getName())) { dup = true; break; }
                if (!dup) out.add(f);
            }
        }
        return out.toArray(new File[0]);
    }

    private static String safeName(String n){return n==null||n.trim().isEmpty()?"map.rmap":n.replaceAll("[\\\\/:*?\"<>|]","_");}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}
}