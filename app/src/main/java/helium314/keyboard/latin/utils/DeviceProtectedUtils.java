/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;

public final class DeviceProtectedUtils {

    static final String TAG = DeviceProtectedUtils.class.getSimpleName();
    private static SharedPreferences prefs;

    private static SharedPreferences wrappedPrefs;
    // Open Keyboard: everyone gets the wrapper, so "Custom" apps see and change their own copy (see AppProfiles)
    public static SharedPreferences getSharedPreferences(final Context context) {
        if (wrappedPrefs != null) return wrappedPrefs;
        final SharedPreferences raw = getRawSharedPreferences(context);
        if (raw.getAll() == null) return raw; // compose previews
        wrappedPrefs = helium314.keyboard.latin.AppProfiles.INSTANCE.wrap(context, raw);
        return wrappedPrefs;
    }

    private static SharedPreferences getRawSharedPreferences(final Context context) {
        if (prefs != null)
            return prefs;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            prefs = getDefaultSharedPreferences(context);
            return prefs;
        }
        final Context deviceProtectedContext = getDeviceProtectedContext(context);
        prefs = getDefaultSharedPreferences(deviceProtectedContext);
        if (prefs.getAll() == null)
            return prefs; // happens for compose previews
        if (prefs.getAll().isEmpty()) {
            Log.i(TAG, "Device encrypted storage is empty, copying values from credential encrypted storage");
            deviceProtectedContext.moveSharedPreferencesFrom(context, android.preference.PreferenceManager.getDefaultSharedPreferencesName(context));
        }
        return prefs;
    }

    // keep this private to avoid accidental use of device protected context anywhere in the app
    private static Context getDeviceProtectedContext(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context;
        final Context ctx = context.isDeviceProtectedStorage() ? context : context.createDeviceProtectedStorageContext();
        if (ctx == null) return context; // happens for compose previews
        else return ctx;
    }

    private static SharedPreferences getDefaultSharedPreferences(Context context) {
        // from androidx.preference.PreferenceManager
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public static File getFilesDir(final Context context) {
        return getDeviceProtectedContext(context).getFilesDir();
    }

    private DeviceProtectedUtils() {
        // This utility class is not publicly instantiable.
    }
}
