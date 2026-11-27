package com.theexpert9.pocketforge.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Opens web URLs strictly in an installed web browser (with Chrome Custom Tabs styling
 * when Chrome/Chromium is available), preventing Android App Links from hijacking URLs
 * like gemini.google.com into the native Gemini Android app.
 */
object BrowserLauncher {

    private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
    private const val EXTRA_CUSTOM_TABS_COLOR_SCHEME = "androidx.browser.customtabs.extra.COLOR_SCHEME"
    private const val COLOR_SCHEME_DARK = 2
    private const val IDE_SLATE_TOOLBAR_ARGB = 0xFF111827.toInt()

    private val PREFERRED_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android"
    )

    private val EXCLUDED_APP_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
        "com.openai.chatgpt",
        "com.anthropic.claude"
    )

    fun openInBrowser(context: Context, url: String): Result<String> = runCatching {
        val uri = Uri.parse(url)
        val browserPackage = resolveBrowserPackage(context)

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Request an in-app Chrome Custom Tab overlay when the browser supports it.
            val customTabsExtras = Bundle().apply {
                putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
                putInt(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, IDE_SLATE_TOOLBAR_ARGB)
                putInt(EXTRA_CUSTOM_TABS_COLOR_SCHEME, COLOR_SCHEME_DARK)
            }
            putExtras(customTabsExtras)
            if (browserPackage != null) {
                setPackage(browserPackage)
            }
        }

        context.startActivity(intent)
        browserPackage ?: "default browser"
    }

    private fun resolveBrowserPackage(context: Context): String? {
        val pm = context.packageManager

        // 1. Check preferred dedicated web browsers first
        for (pkg in PREFERRED_BROWSERS) {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(pkg)
            val resolved = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved != null) return pkg
        }

        // 2. Fallback: query all browsers capable of handling generic https://example.com
        val genericBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val defaultResolved = pm.resolveActivity(genericBrowserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultPkg = defaultResolved?.activityInfo?.packageName
        if (!defaultPkg.isNullOrBlank() && defaultPkg != "android" && defaultPkg !in EXCLUDED_APP_PACKAGES) {
            return defaultPkg
        }

        val candidates = pm.queryIntentActivities(genericBrowserIntent, PackageManager.MATCH_ALL)
        return candidates
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { pkg -> pkg != "android" && pkg !in EXCLUDED_APP_PACKAGES && pkg != context.packageName }
    }
}
