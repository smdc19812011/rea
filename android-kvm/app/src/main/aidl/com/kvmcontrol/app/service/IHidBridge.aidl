// IHidBridge.aidl
// Interface exposed by HidUserService running in the Shizuku elevated process.
// The main app calls these methods; the service writes to /dev/hidg0 and /dev/hidg1.
package com.kvmcontrol.app.service;

interface IHidBridge {
    /** Returns true if /dev/hidg0 and /dev/hidg1 are accessible in this process. */
    boolean isAvailable();

    /** Send an 8-byte keyboard HID report to /dev/hidg0. */
    boolean sendKeyboardReport(in byte[] report);

    /** Send a 4-byte mouse HID report to /dev/hidg1. */
    boolean sendMouseReport(in byte[] report);

    /** Release all keys (send all-zero keyboard report). */
    boolean releaseKeys();

    /** Destroy the service and close file descriptors. */
    void destroy();
}
