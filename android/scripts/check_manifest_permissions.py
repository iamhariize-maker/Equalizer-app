#!/usr/bin/env python3
"""Reject unwanted sensitive capabilities in aapt2's compiled APK manifest dump."""
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1]).read_text()
if "E: manifest" not in manifest or "android.permission.MODIFY_AUDIO_SETTINGS" not in manifest:
    sys.exit("FAIL APK permission policy: missing or invalid compiled manifest dump")

# These are forbidden even on services/receivers, not only uses-permission nodes.
forbidden = (
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.accessibilityservice.AccessibilityService",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.app.action.DEVICE_ADMIN_ENABLED",
    # Play Protect's enhanced fraud protection blocks sideloaded apps that can read
    # notifications (OTP theft), so Svan must never declare a notification listener.
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.service.notification.NotificationListenerService",
)
found = [capability for capability in forbidden if capability in manifest]
if found:
    sys.exit("FAIL APK permission policy: " + ", ".join(found))
print("PASS APK permission policy: no notification listener; forbidden capabilities absent")
