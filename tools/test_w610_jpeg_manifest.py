import unittest
import xml.etree.ElementTree as ET
from w610_jpeg_manifest import A, ACTIVITY, PACKAGE, check_minimal


def valid():
    return ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
    <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="36" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <application android:allowBackup="false" android:usesCleartextTraffic="false">
    <activity android:name="{ACTIVITY}" android:exported="true"><intent-filter>
    <action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter></activity></application></manifest>''')


class ManifestPolicyTest(unittest.TestCase):
    def test_minimal_manifest_passes(self):
        self.assertEqual('PASS', check_minimal(valid())['minimal_policy'])

    def test_sensitive_permissions_including_sdk23_cannot_hide(self):
        for tag in ('uses-permission', 'uses-permission-sdk-23'):
            for name in ('ANSWER_PHONE_CALLS', 'INTERNET', 'CAMERA', 'RECORD_AUDIO', 'READ_SMS', 'SYSTEM_ALERT_WINDOW', 'ACCESS_FINE_LOCATION'):
                with self.subTest(tag=tag, name=name):
                    root = valid()
                    ET.SubElement(root, tag, {A + 'name': 'android.permission.' + name})
                    with self.assertRaises(AssertionError): check_minimal(root)

    def test_notification_listener_and_any_other_service_are_rejected(self):
        root = valid()
        ET.SubElement(root.find('application'), 'service', {A + 'name': 'NotificationForwarderService',
            A + 'permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'})
        with self.assertRaises(AssertionError): check_minimal(root)

    def test_other_background_components_are_rejected(self):
        for tag in ('receiver', 'provider', 'activity-alias'):
            root = valid()
            ET.SubElement(root.find('application'), tag, {A + 'name': 'Unexpected'})
            with self.assertRaises(AssertionError): check_minimal(root)

    def test_main_package_cannot_be_overwritten(self):
        root = valid(); root.set('package', 'com.fersaiyan.cyanbridge')
        with self.assertRaises(AssertionError): check_minimal(root)

    def test_location_assertion_is_required(self):
        root = valid(); root.find('uses-permission').attrib.pop(A + 'usesPermissionFlags')
        with self.assertRaises(AssertionError): check_minimal(root)

    def test_signature_guard_is_not_a_dangerous_permission(self):
        root = valid()
        ET.SubElement(root, 'permission', {A + 'name': PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', A + 'protectionLevel': 'signature'})
        ET.SubElement(root, 'uses-permission', {A + 'name': PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'})
        self.assertEqual('PASS', check_minimal(root)['minimal_policy'])

    def test_custom_application_and_debug_runtime_are_rejected(self):
        for name, value in [('name', 'LegacyApplication'), ('debuggable', 'true'), ('allowBackup', 'true')]:
            root = valid(); root.find('application').set(A + name, value)
            with self.assertRaises(AssertionError): check_minimal(root)

    def test_deep_links_and_secondary_activities_are_rejected(self):
        root = valid()
        ET.SubElement(root.find('application/activity/intent-filter'), 'data', {A + 'scheme': 'cyanbridge'})
        with self.assertRaises(AssertionError): check_minimal(root)


if __name__ == '__main__': unittest.main()
