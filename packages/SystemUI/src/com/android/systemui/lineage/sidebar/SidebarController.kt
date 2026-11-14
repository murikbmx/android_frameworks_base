/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.sidebar

import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.window.WindowContainerTransaction
import android.window.WindowOrganizer
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Edge sidebar: a thin handle on the left edge opening a panel of app shortcuts that launch in
 * floating (freeform) windows. Enabled from the Launcher3 home settings.
 */
@SysUISingleton
class SidebarController
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    @Main private val mainHandler: Handler,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
) : CoreStartable {

    private val windowManager = context.getSystemService(WindowManager::class.java)!!
    private val launcherApps = context.getSystemService(LauncherApps::class.java)!!
    private val resolver = context.contentResolver
    private val density = context.resources.displayMetrics.density

    private val handleWidth = (20 * density).toInt()
    private val handleHeight = (104 * density).toInt()

    private var enabled = false
    private var handleView: SidebarHandleView? = null
    private var handleParams: LayoutParams? = null
    private var panelView: SidebarPanelView? = null
    private var moveStartY = 0

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() = refresh()
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) = refresh()
        }

    private val configCallbacks =
        object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                closePanel()
                handleParams?.let {
                    it.y = handleTop()
                    moveStartY = it.y
                    handleView?.let { view -> windowManager.updateViewLayout(view, it) }
                }
            }

            @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
        }

    override fun start() {
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(SidebarSettings.KEY_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        keyguardStateController.addCallback(keyguardCallback)
        userTracker.addCallback(userCallback, mainExecutor)
        context.registerComponentCallbacks(configCallbacks)
        refresh()
    }

    private val userId: Int
        get() = userTracker.userId

    private fun refresh() {
        val wasEnabled = enabled
        enabled = SidebarSettings.isEnabled(resolver, userId)
        if (enabled && !wasEnabled) ensureFreeformSupport()
        val lockscreen = keyguardStateController.isShowing && !keyguardStateController.isOccluded
        if (enabled && !lockscreen) showHandle() else hideAll()
    }

    /** Freeform windows need the feature or the developer setting (read by the system at boot). */
    private fun ensureFreeformSupport() {
        if (isFreeformSupported()) return
        Log.i(TAG, "Enabling freeform window support, effective after reboot")
        Settings.Global.putInt(
            resolver,
            Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
            1,
        )
    }

    private fun isFreeformSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(
                resolver,
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                0,
            ) != 0

    private fun screenBounds(): Rect = windowManager.maximumWindowMetrics.bounds

    private fun handleTop(): Int {
        val height = screenBounds().height()
        val center = (SidebarSettings.handleY(resolver, userId) * height).toInt()
        return (center - handleHeight / 2).coerceIn(0, height - handleHeight)
    }

    private fun showHandle() {
        if (handleView != null) return
        val view =
            SidebarHandleView(
                context,
                object : SidebarHandleView.Callbacks {
                    override fun onOpen() = openPanel()

                    override fun onMove(dy: Float) {
                        val params = handleParams ?: return
                        params.y =
                            (moveStartY + dy.toInt()).coerceIn(
                                0,
                                screenBounds().height() - handleHeight,
                            )
                        handleView?.let { windowManager.updateViewLayout(it, params) }
                    }

                    override fun onMoveFinished() {
                        val params = handleParams ?: return
                        val height = screenBounds().height().toFloat()
                        SidebarSettings.setHandleY(
                            resolver,
                            userId,
                            (params.y + handleHeight / 2f) / height,
                        )
                        moveStartY = params.y
                    }
                },
            )
        val params =
            LayoutParams(
                    handleWidth,
                    handleHeight,
                    LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    LayoutParams.FLAG_NOT_FOCUSABLE or
                        LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = 0
                    y = handleTop()
                    title = "LineageSidebarHandle"
                    layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0)
                }
        moveStartY = params.y
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                handleView = view
                handleParams = params
            }
            .onFailure { Log.e(TAG, "Unable to add sidebar handle", it) }
    }

    private fun hideAll() {
        closePanel(animate = false)
        handleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        handleView = null
        handleParams = null
    }

    private fun openPanel() {
        if (panelView != null) return
        val view =
            SidebarPanelView(
                context,
                object : SidebarPanelView.Callbacks {
                    override fun onLaunch(app: SidebarApp) = launchFreeform(app.component)

                    override fun onAppsChanged(apps: List<ComponentName>) {
                        SidebarSettings.setApps(resolver, userId, apps)
                    }

                    override fun loadAllApps(onLoaded: (List<SidebarApp>) -> Unit) {
                        bgExecutor.execute {
                            val all = queryApps().sortedBy { it.label.toString().lowercase() }
                            mainExecutor.execute { onLoaded(all) }
                        }
                    }

                    override fun onClosed() = removePanel()
                },
            )
        val params =
            LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "LineageSidebarPanel"
                    layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0)
                }
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "Unable to add sidebar panel", it)
                return
            }
        panelView = view
        handleView?.dimmed = true
        val centerY = (handleParams?.y ?: handleTop()) + handleHeight / 2
        bgExecutor.execute {
            val byComponent = queryApps().associateBy { it.component }
            val apps = SidebarSettings.apps(resolver, userId).mapNotNull { byComponent[it] }
            mainExecutor.execute { panelView?.show(apps, centerY) }
        }
    }

    private fun closePanel(animate: Boolean = true) {
        val view = panelView ?: return
        if (animate) view.close() else removePanel()
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panelView = null
        handleView?.dimmed = false
    }

    private fun queryApps(): List<SidebarApp> =
        runCatching {
                launcherApps.getActivityList(null, userTracker.userHandle).map {
                    SidebarApp(it.componentName, it.label, it.getBadgedIcon(0))
                }
            }
            .getOrDefault(emptyList())

    /**
     * Keeps at most [MAX_MINI_WINDOWS] mini windows: before opening a new one, the oldest floating
     * windows go back to fullscreen at the bottom of recents (minimized, not closed).
     */
    private fun limitMiniWindows(launchingPackage: String) {
        runCatching {
                val freeform =
                    ActivityTaskManager.getInstance()
                        .getTasks(MAX_TASKS_QUERY)
                        .filter {
                            it.configuration.windowConfiguration.windowingMode ==
                                WINDOWING_MODE_FREEFORM
                        }
                // Already open in a mini window: it is just brought to front
                if (freeform.any { it.baseActivity?.packageName == launchingPackage }) return
                // getTasks() is ordered from the most recent task
                val toMinimize = freeform.drop(MAX_MINI_WINDOWS - 1)
                if (toMinimize.isEmpty()) return
                val wct = WindowContainerTransaction()
                toMinimize.forEach {
                    wct.setWindowingMode(it.token, WINDOWING_MODE_FULLSCREEN)
                    wct.reorder(it.token, /* onTop= */ false)
                }
                WindowOrganizer().applyTransaction(wct)
            }
            .onFailure { Log.w(TAG, "Unable to limit mini windows", it) }
    }

    private fun launchFreeform(component: ComponentName) {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
        val options = ActivityOptions.makeBasic()
        if (isFreeformSupported()) {
            limitMiniWindows(component.packageName)
            val screen = screenBounds()
            val width = (screen.width() * 0.82f).toInt()
            val height = (screen.height() * 0.55f).toInt()
            val left = screen.left + (screen.width() - width) / 2
            val top = screen.top + (screen.height() - height) / 3
            options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM)
            options.setLaunchBounds(Rect(left, top, left + width, top + height))
        }
        runCatching {
                context.startActivityAsUser(intent, options.toBundle(), userTracker.userHandle)
            }
            .onFailure { Log.e(TAG, "Unable to launch $component", it) }
    }

    private companion object {
        private const val MAX_MINI_WINDOWS = 2
        private const val MAX_TASKS_QUERY = 20
        const val TAG = "SidebarController"
    }
}
