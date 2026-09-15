package io.github.qssecurity;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class SettingsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (ModuleConfig.PROVIDER_METHOD_GET_MODE.equals(method) && getContext() != null) {
            int mode = getContext()
                    .getSharedPreferences(ModuleConfig.PREFS, 0)
                    .getInt(ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK);
            Bundle result = new Bundle();
            result.putInt(ModuleConfig.PROVIDER_RESULT_MODE, mode);
            return result;
        }
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
