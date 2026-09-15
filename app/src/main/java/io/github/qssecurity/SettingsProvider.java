package io.github.qssecurity;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Compatibility shim for repositories upgraded from v1.3.
 *
 * v1.4+ no longer uses this ContentProvider for module settings; mode synchronization is handled
 * by libxposed RemotePreferences through QSApp/MainHook. The class remains intentionally so that
 * uploading the new source over an older GitHub repository also overwrites the stale v1.3 source
 * instead of leaving a Java file that references removed ModuleConfig constants.
 */
@Deprecated
public final class SettingsProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // Legacy provider disabled. Keep the component harmless if an old manifest still refers to it.
        return super.call(method, arg, extras);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
