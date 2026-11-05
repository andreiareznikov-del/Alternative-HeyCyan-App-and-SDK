import unittest
from w610_chatgpt_manifest import audit, ALLOWED

def fixture(extra='', service='connectedDevice', exported='false'):
    perms=''.join(f'<uses-permission android:name="{p}" '+('android:usesPermissionFlags="neverForLocation"' if p.endswith('BLUETOOTH_SCAN') else '')+'/>' for p in ALLOWED)
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.fersaiyan.cyanbridge.w610chatgpt">{perms}{extra}
      <application android:allowBackup="false">
      <activity android:name=".BridgeActivity"/>
      <service android:name=".W610BridgeService" android:exported="{exported}" android:foregroundServiceType="{service}"/>
      <provider android:name=".BridgeFileProvider" android:authorities="com.fersaiyan.cyanbridge.w610chatgpt.fileprovider" android:exported="false" android:grantUriPermissions="true"/>
      </application></manifest>'''

class ManifestAuditTest(unittest.TestCase):
    def test_minimal_permissions_pass(self): self.assertEqual('PASS', audit(fixture())['result'])
    def test_vendor_sensitive_permission_fails(self):
        for p in ['android.permission.RECORD_AUDIO', 'android.permission.INTERNET', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE']:
            with self.subTest(permission=p), self.assertRaises(AssertionError): audit(fixture(f'<uses-permission android:name="{p}"/>'))
    def test_exported_ble_service_fails(self):
        with self.assertRaises(AssertionError): audit(fixture(exported='true'))
    def test_wrong_foreground_type_fails(self):
        with self.assertRaises(AssertionError): audit(fixture(service='microphone'))
    def test_sdk_numeric_foreground_type(self):
        for value in ['0x10', '0x00000010', '16']:
            self.assertEqual('PASS', audit(fixture(service=value))['result'])

if __name__ == '__main__': unittest.main()
