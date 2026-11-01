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

package com.android.server.biometrics.sensors.fingerprint;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_PANIC;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Binder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.view.WindowManagerGlobal;

import com.android.internal.os.BackgroundThread;
import com.android.internal.widget.LockPatternUtils;

/**
 * A designated enrolled fingerprint that, when matched, never unlocks anything. Instead the
 * device silently turns the screen off and requires the primary credential
 * (pattern / PIN / password); biometrics stay disabled until it is entered. Nothing on the
 * lock screen reveals it: further fingerprint touches look like a non-matching finger.
 */
public final class PanicFingerprint {
    private static final String TAG = "FingerprintAuth";

    private PanicFingerprint() {}

    /** Whether {@code fingerId} is the panic fingerprint of {@code userId}. */
    public static boolean isPanicFinger(@NonNull Context context, int userId, int fingerId) {
        if (fingerId == LockPatternUtils.PANIC_FINGERPRINT_NONE) {
            return false;
        }
        // Called from HAL binder callbacks: use our own identity for LockSettings access.
        final long identity = Binder.clearCallingIdentity();
        try {
            return new LockPatternUtils(context).getPanicFingerprintId(userId) == fingerId;
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unable to read state", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Clears the panic fingerprint if the removed fingerprint was the panic one. */
    public static void onFingerRemoved(@NonNull Context context, int userId, int fingerId) {
        if (!isPanicFinger(context, userId, fingerId)) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            new LockPatternUtils(context).setPanicFingerprintId(
                    LockPatternUtils.PANIC_FINGERPRINT_NONE, userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unable to clear state", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Silently turns the screen off and requires the primary credential for all users. */
    public static void lockdown(@NonNull Context context) {
        BackgroundThread.getHandler().post(() -> {
            new LockPatternUtils(context).requireStrongAuth(
                    STRONG_AUTH_REQUIRED_AFTER_PANIC, UserHandle.USER_ALL);
            final PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null) {
                pm.goToSleep(SystemClock.uptimeMillis(),
                        PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN, 0 /* flags */);
            }
            try {
                WindowManagerGlobal.getWindowManagerService().lockNow(null /* options */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to lock", e);
            }
        });
    }
}
