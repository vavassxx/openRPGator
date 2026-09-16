package rpg.engine.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;

public final class OpenRpgatorDocumentsProvider extends DocumentsProvider {
    private File root;
    private static final String ROOT_ID="openrpgator";
    @Override public boolean onCreate(){ root=new AppStorage(getContext()).rootFile(); return true; }
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor c=new MatrixCursor(projection==null?new String[]{Document.COLUMN_ROOT_ID,Document.COLUMN_DOCUMENT_ID,Document.COLUMN_TITLE,Document.COLUMN_FLAGS,Document.COLUMN_MIME_TYPES}:projection);
        MatrixCursor.RowBuilder r=c.newRow();
        r.add(Document.COLUMN_ROOT_ID,ROOT_ID).add(Document.COLUMN_DOCUMENT_ID,ROOT_ID).add(Document.COLUMN_TITLE,"openRPGator").add(Document.COLUMN_FLAGS,Document.FLAG_SUPPORTS_CREATE|Document.FLAG_LOCAL_ONLY).add(Document.COLUMN_MIME_TYPES,"*/*");
        return c;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{return queryFile(id,projection);}
    @Override public Cursor queryChildDocuments(String parentId,String[] projection,String sortOrder)throws FileNotFoundException{
        File p=fileFor(parentId); MatrixCursor c=new MatrixCursor(projection==null?defaultProjection():projection);
        File[] files=p.listFiles(); if(files!=null) for(File f:files) add(c,f); return c;
    }
    private Cursor queryFile(String id,String[] projection)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(projection==null?defaultProjection():projection);add(c,fileFor(id));return c;}
    private void add(MatrixCursor c,File f){int flags=f.isDirectory()?Document.FLAG_DIR_SUPPORTS_CREATE|Document.FLAG_SUPPORTS_WRITE:Document.FLAG_SUPPORTS_WRITE; c.newRow().add(Document.COLUMN_DOCUMENT_ID,idFor(f)).add(Document.COLUMN_DISPLAY_NAME,f.getName()).add(Document.COLUMN_SIZE,f.isFile()?f.length():0).add(Document.COLUMN_MIME_TYPE,f.isDirectory()?Document.MIME_TYPE_DIR:"application/octet-stream").add(Document.COLUMN_LAST_MODIFIED,f.lastModified()).add(Document.COLUMN_FLAGS,flags);}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(fileFor(id),mode.contains("w")?ParcelFileDescriptor.MODE_READ_WRITE:ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public String createDocument(String parentId,String mime,String displayName)throws FileNotFoundException{File p=fileFor(parentId),f=new File(p,displayName);try{if(Document.MIME_TYPE_DIR.equals(mime)){if(!f.mkdirs())throw new Exception();}else if(!f.createNewFile())throw new Exception();return idFor(f);}catch(Exception e){throw new FileNotFoundException(e.toString());}}
    @Override public void deleteDocument(String id)throws FileNotFoundException{File f=fileFor(id);if(f.isDirectory()){File[] x=f.listFiles();if(x!=null)for(File q:x)q.delete();}if(!f.delete())throw new FileNotFoundException("Cannot delete "+f);}
    private String[] defaultProjection(){return new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_SIZE,Document.COLUMN_MIME_TYPE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_FLAGS};}
    private String idFor(File f){return f.equals(root)?ROOT_ID:f.getAbsolutePath().substring(root.getAbsolutePath().length()+1);}
    private File fileFor(String id)throws FileNotFoundException{if(ROOT_ID.equals(id))return root;File f=new File(root,id);try{if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()))throw new Exception();}catch(Exception e){throw new FileNotFoundException("Invalid document id");}return f;}
}
