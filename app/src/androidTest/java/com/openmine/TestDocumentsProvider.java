package com.openmine;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Real SAF provider installed only in the instrumentation APK. Android/JDK-only code is
 * intentional: this separate process must not depend on the target app's Kotlin runtime.
 */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.openmine.test.documents";
    public static final String ROOT_TITLE = "Open Mine test files";
    public static final String ROOT_ID = "open-mine-test-root";
    public static final String ROOT_DOCUMENT = "root";
    public static final String RECORD_NAME = "strict-source.omd";
    public static final String PROJECT_NAME = "project-source.txt";
    public static final String INVALID_NAME = "invalid-source.omd";
    public static final String RECORD_ID = "knowledge.saf-permission-fixture";
    public static final String RECORD_TITLE = "SAF permission fixture";
    public static final String SOURCE = "Open Mine SAF instrumentation fixture";
    public static final String PROJECT_TEXT = "Imported through Android DocumentsUI.\nUnicode survives: café — Δ — 日本語\n";
    public static final String RECORD_RAW = """
            [OPEN_MINE_OBJECT]
            OBJECT_VERSION: 1
            OBJECT_ID: knowledge.saf-permission-fixture
            OBJECT_TYPE: KNOWLEDGE
            OBJECT_STATUS: DRAFT
            OBJECT_TITLE: SAF permission fixture
            OBJECT_SUMMARY: A test-only record imported through the real Android document picker.
            OBJECT_TAGS: saf, test
            OBJECT_SOURCE: Open Mine SAF instrumentation fixture
            OBJECT_CREATED: 2026-10-07T00:00:00Z
            OBJECT_UPDATED: 2026-10-07T00:00:00Z

            [CONTEXT_INDEX]
            INDEX_KEYWORDS: safpermissionfixture
            INDEX_ALIASES: NONE
            INDEX_TRIGGERS: NONE
            INDEX_SCOPE: workspace
            INDEX_PRIORITY: 50

            [CONTENT]
            CONTENT_PURPOSE: Verify SAF import and export with ordinary URI permissions.
            CONTENT_FACTS: safpermissionfixture
            CONTENT_PROCEDURE: NONE
            CONTENT_CONSTRAINTS: Test fixture only; no model output or executed tools.
            CONTENT_EXAMPLES: NONE

            [RELATIONSHIPS]
            REL_PROJECTS: NONE
            REL_MODELS: NONE
            REL_SKILLS: NONE
            REL_TOOLS: NONE
            REL_KNOWLEDGE: NONE
            REL_MISSIONS: NONE

            [RETRIEVAL]
            RETRIEVAL_QUERY: safpermissionfixture
            RETRIEVAL_WHEN: Instrumentation verification
            RETRIEVAL_EXCLUDE: NONE

            [VERIFICATION]
            VERIFICATION_STATUS: DRAFT
            VERIFICATION_SOURCE: Android instrumentation
            VERIFICATION_NOTES: Test fixture; no production knowledge is fabricated.

            [END_OBJECT]
            """;

    private static final String[] ROOT_COLUMNS = {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    public static String documentId(String name) { return "document:" + name; }
    public static Uri uri(String name) { return DocumentsContract.buildDocumentUri(AUTHORITY, documentId(name)); }

    private File directory() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        File file = new File(context.getFilesDir(), "documents-provider-fixtures");
        if (!file.isDirectory() && !file.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
        return file;
    }

    @Override public boolean onCreate() {
        writeFixture(RECORD_NAME, RECORD_RAW);
        writeFixture(PROJECT_NAME, PROJECT_TEXT);
        writeFixture(INVALID_NAME, "This is not a valid Open Mine record.\n");
        return true;
    }

    private void writeFixture(String name, String text) {
        try (FileOutputStream output = new FileOutputStream(new File(directory(), name))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create test fixture", failure);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
                .add(DocumentsContract.Root.COLUMN_TITLE, ROOT_TITLE)
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_LOCAL_ONLY)
                .add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "text/plain\napplication/octet-stream")
                .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, directory().getUsableSpace());
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocument(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!ROOT_DOCUMENT.equals(parentId)) throw new FileNotFoundException("Not a directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] files = directory().listFiles();
        if (files == null) throw new FileNotFoundException("Cannot list test documents");
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) if (file.isFile()) addDocument(cursor, documentId(file.getName()));
        return cursor;
    }

    @Override public boolean isChildDocument(String parentId, String id) {
        if (!ROOT_DOCUMENT.equals(parentId)) return false;
        try { return file(id).isFile(); } catch (FileNotFoundException failure) { return false; }
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        File file = file(id);
        if (!file.isFile()) throw new FileNotFoundException("Document does not exist");
        if (isSource(file.getName()) && !"r".equals(mode)) throw new FileNotFoundException("Source fixtures are read-only");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        if (!ROOT_DOCUMENT.equals(parentId)) throw new FileNotFoundException("Not a directory");
        String name = displayName;
        int suffix = 2;
        while (file(documentId(name)).exists()) name = displayName + " (" + suffix++ + ")";
        File target = file(documentId(name));
        try {
            if (!target.createNewFile()) throw new IOException("Could not create export document");
        } catch (IOException failure) { throw missing("Cannot create export document", failure); }
        notifyChildren();
        return documentId(name);
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        File file = file(id);
        if (isSource(file.getName())) throw new FileNotFoundException("Source fixtures are read-only");
        if (!file.delete()) throw new FileNotFoundException("Could not delete export document");
        notifyChildren();
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = ROOT_DOCUMENT.equals(id);
        File file = root ? directory() : file(id);
        if (!file.exists()) throw new FileNotFoundException("Document does not exist");
        int flags = root ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : isSource(file.getName()) ? 0
                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        String mime = root ? DocumentsContract.Document.MIME_TYPE_DIR
                : file.getName().endsWith(".txt") || file.getName().endsWith(".omd") ? "text/plain" : "application/octet-stream";
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, root ? ROOT_TITLE : file.getName())
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
                .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                .add(DocumentsContract.Document.COLUMN_SIZE, root ? 0L : file.length())
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static boolean isSource(String name) { return RECORD_NAME.equals(name) || PROJECT_NAME.equals(name) || INVALID_NAME.equals(name); }

    private File file(String id) throws FileNotFoundException {
        if (id == null || !id.startsWith("document:")) throw new FileNotFoundException("Invalid document ID");
        String name = id.substring("document:".length());
        if (name.trim().isEmpty() || name.length() > 180 || name.startsWith(".")) throw new FileNotFoundException("Invalid document name");
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '/' || character == '\\' || character < 32) throw new FileNotFoundException("Invalid document name");
        }
        File file = new File(directory(), name);
        try {
            if (!directory().getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) throw new IOException("Unsafe document path");
        } catch (IOException failure) { throw missing("Unsafe document path", failure); }
        return file;
    }

    private static FileNotFoundException missing(String message, Exception cause) {
        FileNotFoundException failure = new FileNotFoundException(message);
        failure.initCause(cause);
        return failure;
    }

    private void notifyChildren() {
        Context context = getContext();
        if (context != null) context.getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOCUMENT), null);
    }
}
