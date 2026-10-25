package com.novaghost.family.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.novaghost.family.R

class NovaAdminReceiver : DeviceAdminReceiver()

/**
 * Only uses APIs Android officially grants to an ACTIVE device admin (lockNow) or a
 * DEVICE OWNER (suspending packages). Nothing here works on an ordinary install until the
 * user activates the admin or the device is provisioned as managed.
 */
class DeviceControl(private val ctx: Context) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(ctx, NovaAdminReceiver::class.java)

    fun isAdmin(): Boolean = dpm.isAdminActive(admin)
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(ctx.packageName)

    fun activationIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.admin_description))

    fun deactivate() { if (isAdmin()) dpm.removeActiveAdmin(admin) }

    fun lockNow(): Boolean {
        if (!isAdmin()) return false
        return try { dpm.lockNow(); true } catch (e: SecurityException) { false }
    }

    /** Device-owner only. Returns how many packages were changed. */
    fun setSuspended(pkgs: List<String>, suspended: Boolean): Int {
        if (!isDeviceOwner() || pkgs.isEmpty()) return 0
        return try {
            val failed = dpm.setPackagesSuspended(admin, pkgs.toTypedArray(), suspended)
            pkgs.size - failed.size
        } catch (e: Exception) { 0 }
    }
}
