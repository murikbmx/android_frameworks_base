/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Unlimited Google Photos backup: make Google Photos believe it runs on a
 * Google Pixel XL (marlin), the last device with "Original quality" unlimited
 * storage. 1:1 port of the De-Vanced/ReVanced Google Photos "Spoof features"
 * patch (SpoofBuildInfoPatch + SpoofFeaturesPatch), applied in-framework to the
 * com.google.android.apps.photos processes only, so the stock Play Store APK
 * can be used unmodified.
 */

package com.android.internal.util;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** @hide */
@RavenwoodKeepWholeClass
public final class GooglePhotosHooks {

    private static final String TAG = "GooglePhotosHooks";

    private static final String PACKAGE_PHOTOS = "com.google.android.apps.photos";

    /** Set to false to disable the spoof without rebuilding. */
    private static final String PROP_ENABLED = "persist.sys.gphotos_unlimited";

    /** Exactly the Build fields the De-Vanced/ReVanced SpoofBuildInfoPatch rewrites. */
    private static final Map<String, String> BUILD_PROPS = Map.of(
            "BRAND", "google",
            "MANUFACTURER", "Google",
            "DEVICE", "marlin",
            "PRODUCT", "marlin",
            "HARDWARE", "marlin",
            "ID", "QP1A.191005.007.A3",
            "MODEL", "Pixel XL",
            "FINGERPRINT",
                    "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");

    /** SpoofFeaturesPatch featuresToEnable (Pixel XL preload → unlimited storage). */
    private static final Set<String> FEATURES_ENABLE = Set.of(
            "com.google.android.apps.photos.NEXUS_PRELOAD",
            "com.google.android.apps.photos.nexus_preload");

    /*
     * SpoofFeaturesPatch featuresToDisable: PIXEL_EXPERIENCE plus every newer-Pixel
     * marker (PIXEL_2017.._PRELOAD, PIXEL_20xx[_MIDYEAR]_EXPERIENCE). Written as a
     * pattern so future years are covered too. Everything else passes through.
     */
    private static final Pattern FEATURES_DISABLE = Pattern.compile(
            "com\\.google\\.android\\.(apps\\.photos\\.PIXEL_20(1[7-9]|[2-9]\\d)\\w*_PRELOAD"
            + "|feature\\.PIXEL(_20\\d\\d(_MIDYEAR)?)?_EXPERIENCE)");

    private static volatile boolean sIsPhotos = false;

    private GooglePhotosHooks() {}

    private static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    /** Called from Instrumentation right before Application#attach. */
    public static void onNewApplication(Context context) {
        if (context == null) return;
        final String packageName = context.getPackageName();
        if (!PACKAGE_PHOTOS.equals(packageName)) return;
        // Only the app's own processes, not other apps hosting its code.
        final String processName = Application.getProcessName();
        if (processName == null || !processName.startsWith(PACKAGE_PHOTOS)) return;
        if (!isEnabled()) return;

        sIsPhotos = true;
        BUILD_PROPS.forEach((k, v) -> setField(Build.class, k, v));
    }

    /**
     * Called from ApplicationPackageManager#hasSystemFeature.
     *
     * @return null to fall through to the real implementation.
     */
    public static Boolean hasSystemFeature(String name) {
        if (!sIsPhotos || name == null) return null;
        if (FEATURES_ENABLE.contains(name)) return Boolean.TRUE;
        if (FEATURES_DISABLE.matcher(name).matches()) return Boolean.FALSE;
        return null;
    }

    private static void setField(Class<?> clazz, String key, String value) {
        try {
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to spoof " + clazz.getSimpleName() + "." + key, e);
        }
    }
}
