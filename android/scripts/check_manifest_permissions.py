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
)
found = [capability for capability in forbidden if capability in manifest]
if found:
    sys.exit("FAIL APK permission policy: " + ", ".join(found))
# The sole notification-access capability is the user-selected music-state fallback.
# Keep the service protected by Android's system-only bind permission.
import re
services = re.split(r"(?m)^\s*E: service\b", manifest)[1:]
recognition = [s for s in services if "PlayerRecognitionService" in s]
if len(recognition) != 1 or "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" not in recognition[0] or "android.service.notification.NotificationListenerService" not in recognition[0]:
    sys.exit("FAIL APK permission policy: missing protected player-recognition service")
for capability in ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "android.service.notification.NotificationListenerService"):
    if manifest.count(capability) != 2:  # aapt2 prints value plus (Raw: value)
        sys.exit("FAIL APK permission policy: unexpected notification-access declaration count")
print("PASS APK permission policy: one protected optional music listener; forbidden capabilities absent")
