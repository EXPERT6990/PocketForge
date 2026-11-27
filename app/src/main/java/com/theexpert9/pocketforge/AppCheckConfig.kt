package com.theexpert9.pocketforge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

// App Check contract keys defined by the Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

private val firebaseInitLock = Any()

/**
 * Ensures the default [FirebaseApp] is initialized in the current process.
 * Falls back to reading the google-services generated string resources explicitly when
 * [FirebaseApp.initializeApp] returns null under non-transitive R / package-name differences.
 */
fun ensureFirebaseInitialized(context: Context): FirebaseApp? = synchronized(firebaseInitLock) {
    val appContext = context.applicationContext ?: context
    val existing = FirebaseApp.getApps(appContext).firstOrNull()
    if (existing != null) return existing

    // Attempt standard resource-based initialization first.
    val standardInit = runCatching { FirebaseApp.initializeApp(appContext) }
        .onFailure { Log.w(TAG, "Standard FirebaseApp.initializeApp threw", it) }
        .getOrNull()
    if (standardInit != null) return standardInit

    // Explicit fallback: read generated google-services string resources directly.
    val appId = resolveStringResource(appContext, "google_app_id")
    val apiKey = resolveStringResource(appContext, "google_api_key")
    val projectId = resolveStringResource(appContext, "project_id")
    val gcmSenderId = resolveStringResource(appContext, "gcm_defaultSenderId")
    val storageBucket = resolveStringResource(appContext, "google_storage_bucket")

    if (appId.isNullOrBlank() || apiKey.isNullOrBlank()) {
        Log.e(
            TAG,
            "Firebase resources missing: google_app_id=${!appId.isNullOrBlank()}, google_api_key=${!apiKey.isNullOrBlank()}"
        )
        return null
    }

    val optionsBuilder = FirebaseOptions.Builder()
        .setApplicationId(appId)
        .setApiKey(apiKey)
    if (!projectId.isNullOrBlank()) optionsBuilder.setProjectId(projectId)
    if (!gcmSenderId.isNullOrBlank()) optionsBuilder.setGcmSenderId(gcmSenderId)
    if (!storageBucket.isNullOrBlank()) optionsBuilder.setStorageBucket(storageBucket)

    runCatching {
        FirebaseApp.initializeApp(appContext, optionsBuilder.build())
    }.onSuccess {
        Log.d(TAG, "FirebaseApp initialized via explicit FirebaseOptions fallback (project=$projectId).")
    }.onFailure {
        Log.e(TAG, "Explicit FirebaseOptions initialization failed", it)
    }.getOrNull()
}

fun getFirebaseDiagnosticStatus(context: Context): String {
    val app = ensureFirebaseInitialized(context)
    return if (app != null) {
        val project = app.options.projectId ?: "default"
        "Connected · $project"
    } else {
        "Not initialized (missing google-services resources)"
    }
}

private fun resolveStringResource(context: Context, name: String): String? {
    val packagesToCheck = listOf(context.packageName, BuildConfig.APPLICATION_ID, "com.theexpert9.pocketforge").distinct()
    for (pkg in packagesToCheck) {
        val resId = context.resources.getIdentifier(name, "string", pkg)
        if (resId != 0) {
            val value = runCatching { context.getString(resId) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
    }
    return null
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        // 1. Ensure default FirebaseApp is initialized (with explicit resource fallback)
        val firebaseApp = ensureFirebaseInitialized(context)
        if (firebaseApp == null) {
            Log.e(TAG, "Cannot install App Check: default FirebaseApp failed to initialize.")
            return
        }

        if (BuildConfig.DEBUG) {
            // 2. Debug: Read debug token from Intent extra
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit() ensures the secret is written before DebugAppCheckProviderFactory reads it.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            // 3. Release: Look up recaptcha_site_key safely without throwing Resources.NotFoundException
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
