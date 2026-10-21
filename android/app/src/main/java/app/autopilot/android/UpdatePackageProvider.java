package app.autopilot.android;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** The installer receives a grant to one verified APK, never a filesystem path or directory. */
public final class UpdatePackageProvider extends ContentProvider {
    static Uri uri(Context context) { return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates").path("/current.apk").build(); }
    @Override public boolean onCreate() { return true; }
    private void requireUri(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !uri(getContext()).equals(uri)) throw new FileNotFoundException("UPDATE_INVALID_URI");
    }
    private File apk(Uri uri) throws FileNotFoundException {
        requireUri(uri);
        try { return ((AutopilotApp) getContext().getApplicationContext()).updates.fileForRead(); }
        catch (IOException error) { throw new FileNotFoundException("UPDATE_NOT_READY"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("UPDATE_READ_ONLY");
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {
        try { requireUri(uri); return "application/vnd.android.package-archive"; }
        catch (FileNotFoundException error) { throw new IllegalArgumentException("UPDATE_INVALID_URI"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = apk(uri); String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? "Autopilot-update.apk" : OpenableColumns.SIZE.equals(columns[i]) ? file.length() : null;
            result.addRow(row); return result;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("UPDATE_READ_ONLY"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("UPDATE_READ_ONLY"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("UPDATE_READ_ONLY"); }
}
