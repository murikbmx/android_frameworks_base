/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.lineage.sidebar

import android.content.ComponentName
import android.content.ContentResolver
import android.provider.Settings

/** Settings.Secure keys of the edge sidebar, shared with the Launcher3 toggle. */
object SidebarSettings {
    /** 1 when the sidebar is enabled (toggle in Launcher3 home settings). Default off. */
    const val KEY_ENABLED = "lineage_sidebar_enabled"

    /** Vertical position of the handle centre, as a fraction of the screen height. */
    const val KEY_HANDLE_Y = "lineage_sidebar_handle_y"

    /** Apps of the panel: flattened launcher activity component names separated by ';'. */
    const val KEY_APPS = "lineage_sidebar_apps"

    const val DEFAULT_HANDLE_Y = 0.68f
    const val MAX_APPS = 20
    const val VISIBLE_APPS = 8

    fun isEnabled(cr: ContentResolver, userId: Int): Boolean =
        Settings.Secure.getIntForUser(cr, KEY_ENABLED, 0, userId) == 1

    fun handleY(cr: ContentResolver, userId: Int): Float =
        Settings.Secure.getStringForUser(cr, KEY_HANDLE_Y, userId)?.toFloatOrNull()
            ?.coerceIn(0.1f, 0.9f) ?: DEFAULT_HANDLE_Y

    fun setHandleY(cr: ContentResolver, userId: Int, fraction: Float) {
        Settings.Secure.putStringForUser(cr, KEY_HANDLE_Y, fraction.toString(), userId)
    }

    fun apps(cr: ContentResolver, userId: Int): List<ComponentName> =
        Settings.Secure.getStringForUser(cr, KEY_APPS, userId)
            ?.split(';')
            ?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.distinct()
            ?.take(MAX_APPS) ?: emptyList()

    fun setApps(cr: ContentResolver, userId: Int, apps: List<ComponentName>) {
        Settings.Secure.putStringForUser(
            cr,
            KEY_APPS,
            apps.distinct().take(MAX_APPS).joinToString(";") { it.flattenToString() },
            userId,
        )
    }
}
