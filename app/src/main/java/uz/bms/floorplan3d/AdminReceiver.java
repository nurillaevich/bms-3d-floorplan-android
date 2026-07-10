package uz.bms.floorplan3d;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device-admin component. Provisioning this app as DEVICE OWNER (one-time, over
 * ADB — see the README) unlocks a truly tamper-proof kiosk: Lock Task Mode then
 * has no "screen pinned" toast and no Back+Overview escape. Without device owner
 * the app still pins itself, just with Android's normal (escapable) pinning.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
