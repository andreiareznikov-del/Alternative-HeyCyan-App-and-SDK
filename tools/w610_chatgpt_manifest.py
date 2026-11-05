"""Audit the actual packaged manifest. No source-only permission claims."""
import json
import sys
import xml.etree.ElementTree as ET

NS = '{http://schemas.android.com/apk/res/android}'
ALLOWED = {
    'android.permission.BLUETOOTH_SCAN', 'android.permission.BLUETOOTH_CONNECT',
    'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE',
    'android.permission.POST_NOTIFICATIONS', 'android.permission.WAKE_LOCK',
}

def audit(xml):
    root = ET.fromstring(xml)
    package = root.get('package')
    assert package == 'com.fersaiyan.cyanbridge.w610chatgpt', package
    permissions = {p.get(NS+'name') for p in root.findall('uses-permission')}
    guard = package+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    assert permissions - {guard} == ALLOWED, sorted((permissions - {guard}) ^ ALLOWED)
    for permission in root.findall('permission'):
        assert permission.get(NS+'name') == guard
        assert permission.get(NS+'protectionLevel') in ('signature', '2', '0x2')
    scan = next(p for p in root.findall('uses-permission') if p.get(NS+'name') == 'android.permission.BLUETOOTH_SCAN')
    flags = scan.get(NS+'usesPermissionFlags', '')
    assert flags == 'neverForLocation' or (flags and int(flags, 0) & 0x10000)
    app = root.find('application')
    assert app.get(NS+'allowBackup') == 'false'
    assert app.get(NS+'debuggable', 'false') == 'false'
    assert app.get(NS+'usesCleartextTraffic', 'false') == 'false'
    assert app.get(NS+'name', 'android.app.Application') == 'android.app.Application'
    assert not app.findall('receiver')
    services = app.findall('service')
    assert len(services) == 1
    assert services[0].get(NS+'name').endswith('.W610BridgeService')
    service_type = services[0].get(NS+'foregroundServiceType', '')
    try:
        numeric_type = int(service_type, 0)
    except ValueError:
        numeric_type = None
    assert service_type == 'connectedDevice' or numeric_type == 0x10, service_type
    assert services[0].get(NS+'exported') == 'false'
    providers = app.findall('provider')
    assert len(providers) == 1
    assert providers[0].get(NS+'authorities') == package+'.fileprovider'
    assert providers[0].get(NS+'exported') == 'false'
    assert providers[0].get(NS+'grantUriPermissions') == 'true'
    assert len(app.findall('activity')) == 1
    assert not app.findall('activity-alias')
    return dict(package=package, permissions=sorted(permissions), services=1, receivers=0, providers=1, result='PASS')

if __name__ == '__main__':
    print(json.dumps(audit(open(sys.argv[1], encoding='utf-8').read()), indent=2))
