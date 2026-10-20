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

package com.android.systemui.keyguard

import android.app.StatusBarManager
import android.content.Context
import android.os.Binder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Disables quick settings, including the quick quick settings row, while a secure lock screen is
 * showing and [Settings.Secure.LOCKSCREEN_QS_DISABLED] is enabled, so toggles like airplane mode
 * can't be used before the device is unlocked.
 */
@SysUISingleton
class LockscreenQsDisabler
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val statusBarService: IStatusBarService,
    private val keyguardRepository: KeyguardRepository,
    private val secureSettingsRepository: SecureSettingsRepository,
    private val selectedUserInteractor: SelectedUserInteractor,
) : CoreStartable {

    private val token = Binder()

    override fun start() {
        scope.launch {
            combine(
                    secureSettingsRepository.boolSetting(
                        Settings.Secure.LOCKSCREEN_QS_DISABLED,
                        false,
                    ),
                    keyguardRepository.isKeyguardShowing,
                    keyguardRepository.isKeyguardDismissible,
                ) { isDisabled, isKeyguardShowing, isKeyguardDismissible ->
                    isDisabled && isKeyguardShowing && !isKeyguardDismissible
                }
                .distinctUntilChanged()
                .collect { disableQs -> setQsDisabled(disableQs) }
        }
    }

    private suspend fun setQsDisabled(disableQs: Boolean) {
        val flags =
            if (disableQs) StatusBarManager.DISABLE2_QUICK_SETTINGS
            else StatusBarManager.DISABLE2_NONE
        withContext(bgDispatcher) {
            try {
                statusBarService.disable2ForUser(
                    flags,
                    token,
                    context.packageName,
                    selectedUserInteractor.getSelectedUserId(),
                )
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to set disable2 flags $flags", e)
            }
        }
    }

    private companion object {
        const val TAG = "LockscreenQsDisabler"
    }
}
