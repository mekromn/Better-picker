package com.mekromn.betterpicker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URLConnection;

public final class BetterPickerProvider extends ContentProvider {
    public static final String AUTHORITY = "com.mekromn.betterpicker.files";

    public static Uri uriFor(File file) {
        String encoded = Base64.encodeToString(
                file.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendPath(encoded)
                .build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 2 || !"file".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Unsupported URI: " + uri);
        }
        try {
            String encoded = uri.getPathSegments().get(1);
            String path = new String(
                    Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP),
                    java.nio.charset.StandardCharsets.UTF_8);
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            String rootPath = root.getPath();
            String targetPath = target.getPath();
            if (!(targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator))) {
                throw new FileNotFoundException("Path outside shared storage");
            }
            return target;
        } catch (Exception e) {
            if (e instanceof FileNotFoundException) throw (FileNotFoundException) e;
            throw new FileNotFoundException("Invalid path: " + e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        try {
            File file = fileFor(uri);
            String mime = URLConnection.guessContentTypeFromName(file.getName());
            return mime != null ? mime : "application/octet-stream";
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File file = fileFor(uri);
            String[] cols = projection != null ? projection : new String[]{
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
            };
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    row.add(col, file.getName());
                } else if (OpenableColumns.SIZE.equals(col)) {
                    row.add(col, file.length());
                } else {
                    row.add(col, null);
                }
            }
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.contains("w")) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
            if (mode.contains("t")) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            return fileFor(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
