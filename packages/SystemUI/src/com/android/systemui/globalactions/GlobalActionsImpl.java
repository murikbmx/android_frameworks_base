/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class GlobalActionsImpl implements GlobalActions, CommandQueue.Callbacks {

    private final Context mContext;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final CommandQueue mCommandQueue;
    private final GlobalActionsDialogLite mGlobalActionsDialog;
    private boolean mDisabled;
    private ShutdownUi mShutdownUi;
    private ShadeController mShadeController;
    private final ActivityStarter mActivityStarter;

    @Inject
    public GlobalActionsImpl(Context context, CommandQueue commandQueue,
            GlobalActionsDialogLite globalActionsDialog,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController,
            ShadeController shadeController,
            ShutdownUi shutdownUi,
            ActivityStarter activityStarter) {
        mContext = context;
        mGlobalActionsDialog = globalActionsDialog;
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(this);
        mShutdownUi = shutdownUi;
        mShadeController = shadeController;
        mActivityStarter = activityStarter;
    }

    @Override
    public void destroy() {
        mCommandQueue.removeCallback(this);
        mGlobalActionsDialog.destroy();
    }

    @Override
    public void showOrHideGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        if (showAfterUnlockIfNeeded()) return;
        mGlobalActionsDialog.showOrHideDialog(mKeyguardStateController.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned(), null /* view */,
                mContext.getDisplayId());
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        if (showAfterUnlockIfNeeded()) return;
        mGlobalActionsDialog.showDialog(mKeyguardStateController.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned(), null /* view */,
                mContext.getDisplayId());
    }

    /**
     * Asks the user to unlock first when the power menu is disabled on a secure lock screen, so
     * a stolen device can't be powered off right away. The menu is shown once unlocked.
     *
     * @return true if the menu will be shown after unlocking instead of right away.
     */
    private boolean showAfterUnlockIfNeeded() {
        if (!mKeyguardStateController.isShowing()
                || !mKeyguardStateController.isMethodSecure()
                || mKeyguardStateController.isUnlocked()
                || Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.LOCKSCREEN_POWER_MENU_DISABLED, 0,
                        UserHandle.USER_CURRENT) == 0) {
            return false;
        }
        mActivityStarter.dismissKeyguardThenExecute(() -> {
            mGlobalActionsDialog.showDialog(false /* keyguardShowing */,
                    mDeviceProvisionedController.isDeviceProvisioned(), null /* view */,
                    mContext.getDisplayId());
            return false;
        }, null /* cancelAction */, true /* afterKeyguardGone */);
        return true;
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason, boolean rebootCustom) {
        mShutdownUi.showShutdownUi(isReboot, reason, rebootCustom);
        mShadeController.instantCollapseShade();
    }
    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        if (displayId != mContext.getDisplayId() || disabled == mDisabled) return;
        mDisabled = disabled;
        if (disabled) {
            mGlobalActionsDialog.dismissDialog();
        }
    }
}
