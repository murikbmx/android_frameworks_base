/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.PathParser;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Styles an app icon like the launcher does: monochrome when themed icons are enabled, and
 * cropped to the selected icon shape. The settings are read from the launcher's customization
 * provider, the same one the wallpaper & style picker uses.
 */
final class LockedAppIconStyler {
    private static final String TAG = "LockedAppIconStyler";

    private static final String GRID_CONTROL_AUTHORITY_SUFFIX = ".grid_control";
    private static final String PATH_ICON_THEMED = "icon_themed";
    private static final String PATH_SHAPE_OPTIONS = "shape_options";
    private static final String COL_BOOLEAN_VALUE = "boolean_value";
    private static final String COL_PATH = "path";
    private static final String COL_IS_DEFAULT = "is_default";

    // Launcher icon shape paths are defined in a 100x100 viewport
    private static final float SHAPE_PATH_SIZE = 100f;
    private static final int FALLBACK_ICON_SIZE_PX = 192;
    private static final long QUERY_TIMEOUT_MS = 500;

    private LockedAppIconStyler() {}

    /**
     * Returns the icon styled like the launcher, or the original icon if the launcher doesn't
     * provide its settings or the icon can't be styled.
     */
    @Nullable
    static Drawable apply(@NonNull Context context, @Nullable Drawable icon) {
        if (!(icon instanceof AdaptiveIconDrawable)) {
            return icon;
        }
        try {
            final String authority = getLauncherAuthority(context);
            if (authority == null) {
                return icon;
            }
            final LauncherIconSettings settings = queryLauncherWithTimeout(context, authority);
            if (settings == null) {
                return icon;
            }
            final boolean themed = settings.themed;
            final String shapePath = settings.shapePath;
            if (!themed && shapePath == null) {
                return icon;
            }

            AdaptiveIconDrawable adaptiveIcon = (AdaptiveIconDrawable) icon;
            if (themed) {
                adaptiveIcon = createThemedIcon(context, adaptiveIcon);
            }
            if (shapePath == null) {
                return adaptiveIcon;
            }
            return cropToShape(context, adaptiveIcon, shapePath);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to style icon, using the original one", e);
            return icon;
        }
    }

    @Nullable
    private static String getLauncherAuthority(Context context) {
        final ComponentName home =
                context.getPackageManager().getHomeActivities(new ArrayList<ResolveInfo>());
        return home != null ? home.getPackageName() + GRID_CONTROL_AUTHORITY_SUFFIX : null;
    }

    /** Icon settings read from the launcher. */
    private static final class LauncherIconSettings {
        boolean themed;
        @Nullable String shapePath;
    }

    /**
     * Reads the icon settings on a background thread. The caller may run on the system server's
     * main thread, so it must never block on the launcher for long.
     */
    @Nullable
    private static LauncherIconSettings queryLauncherWithTimeout(Context context,
            String authority) {
        final FutureTask<LauncherIconSettings> task =
                new FutureTask<>(() -> queryLauncher(context, authority));
        BackgroundThread.getExecutor().execute(task);
        try {
            return task.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            task.cancel(/* mayInterruptIfRunning= */ false);
            Slog.w(TAG, "Failed to get launcher icon settings in time", e);
            return null;
        }
    }

    /**
     * Reads the icon settings from the launcher provider. An unstable client is used so the
     * launcher process dying doesn't take the caller down with it.
     */
    @Nullable
    private static LauncherIconSettings queryLauncher(Context context, String authority) {
        try (ContentProviderClient client =
                context.getContentResolver().acquireUnstableContentProviderClient(authority)) {
            if (client == null) {
                return null;
            }
            final LauncherIconSettings settings = new LauncherIconSettings();
            try (Cursor cursor = client.query(buildUri(authority, PATH_ICON_THEMED),
                    null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int index = cursor.getColumnIndex(COL_BOOLEAN_VALUE);
                    settings.themed = index >= 0 && isTrue(cursor.getString(index));
                }
            }
            try (Cursor cursor = client.query(buildUri(authority, PATH_SHAPE_OPTIONS),
                    null, null, null, null)) {
                settings.shapePath = cursor != null ? getSelectedShapePath(cursor) : null;
            }
            return settings;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to query launcher icon settings from " + authority, e);
            return null;
        }
    }

    @Nullable
    private static String getSelectedShapePath(Cursor cursor) {
        final int pathIndex = cursor.getColumnIndex(COL_PATH);
        final int isDefaultIndex = cursor.getColumnIndex(COL_IS_DEFAULT);
        if (pathIndex < 0 || isDefaultIndex < 0) {
            return null;
        }
        while (cursor.moveToNext()) {
            if (isTrue(cursor.getString(isDefaultIndex))) {
                final String path = cursor.getString(pathIndex);
                return TextUtils.isEmpty(path) ? null : path;
            }
        }
        return null;
    }

    private static Uri buildUri(String authority, String path) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(path).build();
    }

    // Cursor values may be stored as 0/1 or as boolean strings
    private static boolean isTrue(@Nullable String value) {
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    /** Same as the launcher's themed adaptive icon: monochrome layer on a solid background. */
    private static AdaptiveIconDrawable createThemedIcon(Context context,
            AdaptiveIconDrawable icon) {
        final Drawable monochrome = icon.getMonochrome();
        if (monochrome == null) {
            return icon;
        }
        final boolean isNight = context.getResources().getConfiguration().isNightModeActive();
        final int bgColor = context.getColor(isNight
                ? android.R.color.system_accent2_800 : android.R.color.system_accent1_100);
        final int fgColor = context.getColor(isNight
                ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
        final Drawable foreground = monochrome.mutate();
        foreground.setTint(fgColor);
        return new AdaptiveIconDrawable(new ColorDrawable(bgColor), foreground);
    }

    private static Drawable cropToShape(Context context, AdaptiveIconDrawable icon,
            String shapePath) {
        final Path shape = PathParser.createPathFromPathData(shapePath);
        int size = Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        if (size <= 0) {
            size = FALLBACK_ICON_SIZE_PX;
        }
        final Matrix matrix = new Matrix();
        matrix.setScale(size / SHAPE_PATH_SIZE, size / SHAPE_PATH_SIZE);
        shape.transform(matrix);

        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        // Draw the shape as an anti-aliased mask, then draw the full-bleed layers into it
        final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawPath(shape, maskPaint);
        final Paint layerPaint = new Paint();
        layerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        final int saveCount = canvas.saveLayer(null, layerPaint);
        // Setting the bounds lays out the layers with their extra full-bleed insets
        icon.setBounds(0, 0, size, size);
        final Drawable background = icon.getBackground();
        if (background != null) {
            background.draw(canvas);
        }
        final Drawable foreground = icon.getForeground();
        if (foreground != null) {
            foreground.draw(canvas);
        }
        canvas.restoreToCount(saveCount);
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
