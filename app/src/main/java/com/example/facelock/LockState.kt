package com.example.facelock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.os.Process
import androidx.core.content.ContextCompat

/** In-memory unlock state. Resets when the process dies (fail-closed). */
object LockState {
    const val EXTRA_PKG = "target_pkg"
    private const val SELF_GRACE_MS = 90_000L

    @Volatile var unlockedPkg: String? = null
    @Volatile private var selfUnlockUntil = 0L
    @Volatile private var lastLaunch = 0L

    fun selfUnlocked() = SystemClock.elapsedRealtime() < selfUnlockUntil
    fun grantSelf() { selfUnlockUntil = SystemClock.elapsedRealtime() + SELF_GRACE_MS }

    /** Password-unlocked dashboard session (5 minutes). */
    @Volatile private var adminUnlockUntil = 0L
    fun adminUnlocked() = SystemClock.elapsedRealtime() < adminUnlockUntil
    fun revokeAdmin() { adminUnlockUntil = 0L }
    fun grantAdmin() { adminUnlockUntil = SystemClock.elapsedRealtime() + 300_000L }

    /** Brings up the face lock over [pkg]. Passing our own package means "guard FaceLock itself". */
    @Synchronized
    fun showLock(ctx: Context, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 700) return
        lastLaunch = now
        ctx.startActivity(
            Intent(ctx, LockActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
        )
    }
}

/** Shared decision logic used by both the Accessibility service and the UsageStats watchdog. */
object LockPolicy {
    fun onForeground(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || isIme(ctx, pkg)) return
        // Leaving an unlocked app re-arms its lock.
        if (LockState.unlockedPkg != null && LockState.unlockedPkg != pkg) LockState.unlockedPkg = null
        if (SecureStore.hasFace(ctx) && pkg in SecureStore.getProtected(ctx) && LockState.unlockedPkg != pkg) {
            LockState.showLock(ctx, pkg)
        }
    }

    private fun isIme(ctx: Context, pkg: String): Boolean =
        ctx.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.packageName == pkg } == true
}

object AdminHelper {
    fun component(ctx: Context) = ComponentName(ctx, AppLockDeviceAdminReceiver::class.java)
    fun isActive(ctx: Context) =
        ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(ctx))
}

object Permissions {
    fun camera(c: Context) =
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    @Suppress("DEPRECATION")
    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun accessibility(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(c, AppLockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun admin(c: Context) = AdminHelper.isActive(c)
}
