package rpg.engine.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;

/**
 * SAF DocumentsProvider that exposes the application data folder (<code>AppStorage.rootFile()</code>)
 * to the outside world — the system picker sidebar, file managers and other apps.
 *
 * Modeled on Termux {@code TermuxDocumentsProvider}: document ids are absolute file paths
 * (stable across time, so saved references keep working), the root advertises itself as
 * "openRPGator", and columns are filled by name so any caller projection works.
 */
public final class OpenRpgatorDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "rpg.engine.android.documents";
    private static final String ROOT_ID = "openrpgator";

    private File root;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS
    };

    @Override
    public boolean onCreate() {
        root = new AppStorage(getContext()).rootFile();
        return true;
    }

    // ── Roots ────────────────────────────────────────────────────
    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        if (root == null || !root.exists()) return c;

        int flags = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY
                  | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD;
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : c.getColumnNames()) {
            switch (col) {
                case Root.COLUMN_ROOT_ID: row.add(ROOT_ID); break;
                case Root.COLUMN_DOCUMENT_ID: row.add(getDocIdForFile(root)); break;
                case Root.COLUMN_TITLE: row.add("openRPGator"); break;
                case Root.COLUMN_SUMMARY: row.add("Application data"); break;
                case Root.COLUMN_FLAGS: row.add(flags); break;
                case Root.COLUMN_MIME_TYPES: row.add("*/*"); break;
                case Root.COLUMN_AVAILABLE_BYTES: row.add(root.getUsableSpace()); break;
                default: row.add(null);
            }
        }
        return c;
    }

    // ── Documents ────────────────────────────────────────────────
    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(c, documentId, null);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files != null) for (File f : files) includeFile(c, null, f);
        return c;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        LinkedList<File> pending = new LinkedList<>();
        pending.add(root);
        String q = query == null ? "" : query.toLowerCase();
        final int MAX = 50;
        while (!pending.isEmpty() && c.getCount() < MAX) {
            File f = pending.removeFirst();
            try {
                if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) continue;
            } catch (IOException e) {
                continue;
            }
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) for (File child : children) pending.add(child);
            } else if (f.getName().toLowerCase().contains(q)) {
                includeFile(c, null, f);
            }
        }
        return c;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return getMimeType(getFileForDocId(documentId));
    }

    // ── File operations ──────────────────────────────────────────
    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File f = new File(parent, displayName);
        int n = 2;
        while (f.exists()) f = new File(parent, displayName + " (" + n++ + ")");
        try {
            boolean ok = Document.MIME_TYPE_DIR.equals(mimeType) ? f.mkdir() : f.createNewFile();
            if (!ok) throw new FileNotFoundException("Failed to create " + f.getPath());
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create " + f.getPath());
        }
        return getDocIdForFile(f);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = getFileForDocId(documentId);
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        if (!f.delete()) throw new FileNotFoundException("Failed to delete " + documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File f = getFileForDocId(documentId);
        File renamed = new File(f.getParentFile(), displayName);
        if (!f.renameTo(renamed)) throw new FileNotFoundException("Failed to rename " + documentId);
        return getDocIdForFile(renamed);
    }

    // ── Helpers ──────────────────────────────────────────────────
    private static String getDocIdForFile(File file) { return file.getAbsolutePath(); }

    private File getFileForDocId(String documentId) throws FileNotFoundException {
        if (documentId == null) throw new FileNotFoundException("No document id");
        File f = new File(documentId);
        try {
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) throw new FileNotFoundException("Outside root");
        } catch (IOException e) {
            throw new FileNotFoundException("Invalid path");
        }
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        return f;
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /**
     * Adds a file row to the cursor, filling each requested column by name so the row is
     * correct regardless of the projection order the caller passes.
     */
    private void includeFile(MatrixCursor cursor, String documentId, File file) throws FileNotFoundException {
        if (documentId == null) {
            documentId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(documentId);
        }

        boolean isRoot = file.equals(root);
        String displayName = isRoot ? "openRPGator" : file.getName();
        String mime = getMimeType(file);
        long size = file.isDirectory() ? 0 : file.length();
        long modified = file.lastModified();

        int flags = 0;
        if (isRoot) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        else if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (file.getParentFile() != null && file.getParentFile().canWrite()) flags |= Document.FLAG_SUPPORTS_DELETE;
        flags |= Document.FLAG_SUPPORTS_RENAME;
        if (mime.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cursor.getColumnNames()) {
            switch (col) {
                case Document.COLUMN_DOCUMENT_ID: row.add(documentId); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(displayName); break;
                case Document.COLUMN_SIZE: row.add(size); break;
                case Document.COLUMN_MIME_TYPE: row.add(mime); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(modified); break;
                case Document.COLUMN_FLAGS: row.add(flags); break;
                default: row.add(null);
            }
        }
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}