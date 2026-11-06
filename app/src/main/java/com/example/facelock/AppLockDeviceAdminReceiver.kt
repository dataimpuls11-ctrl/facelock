package com.example.facelock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * While this admin is active, Android refuses to uninstall the app ("Deactivate this device
 * admin app first"). Deactivation lives in Settings > Security > Device admin apps; the
 * Accessibility service puts a face check in front of those screens, and we also raise the
 * face lock the moment a deactivation is requested.
 */
class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (SecureStore.hasFace(context) && !LockState.selfUnlocked()) {
            LockState.showLock(context, context.packageName)
        }
        return "Deactivating admin removes uninstall protection for FaceLock and the apps it guards."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Admin was removed (user passed the face check, or the OS allowed it): nothing more we can enforce here.
    }
}
