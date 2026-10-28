#!/usr/bin/env python3
"""Inspect the packaged APK, or Android SDK apkanalyzer XML, and enforce JPEG-only scope."""
import argparse
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ANDROID = 'http://schemas.android.com/apk/res/android'
A = '{' + ANDROID + '}'
PACKAGE = 'com.fersaiyan.cyanbridge.w610jpegtest'
ACTIVITY = 'com.fersaiyan.cyanbridge.w610jpeg.JpegTestActivity'
ET.register_namespace('android', ANDROID)


def decode_axml(data):
    """Decode ResXMLTree/ResStringPool (ResourceTypes.h); audit cross-checks SDK output in CI."""
    def u16(pos): return struct.unpack_from('<H', data, pos)[0]
    def u32(pos): return struct.unpack_from('<I', data, pos)[0]
    def length8(pos):
        value = data[pos]
        return (((value & 0x7f) << 8) | data[pos + 1], pos + 2) if value & 0x80 else (value, pos + 1)
    def length16(pos):
        value = u16(pos)
        return (((value & 0x7fff) << 16) | u16(pos + 2), pos + 4) if value & 0x8000 else (value, pos + 2)
    assert u16(0) == 3, 'Not Android binary XML'
    strings, stack, root = [], [], None
    def string(index): return '' if index == 0xffffffff else strings[index]
    pos = u16(2)
    while pos < u32(4):
        kind, header, size = struct.unpack_from('<HHI', data, pos)
        assert size >= header >= 8 and pos + size <= len(data), 'Invalid XML chunk'
        if kind == 1:
            count, flags, start = u32(pos + 8), u32(pos + 16), pos + u32(pos + 20)
            for i in range(count):
                offset = start + u32(pos + header + 4 * i)
                if flags & 0x100:
                    _, offset = length8(offset)
                    length, offset = length8(offset)
                    strings.append(data[offset:offset + length].decode('utf-8'))
                else:
                    length, offset = length16(offset)
                    strings.append(data[offset:offset + 2 * length].decode('utf-16le'))
        elif kind == 0x102:
            node = ET.Element(string(u32(pos + 20)))
            attr_start, attr_size, attr_count = u16(pos + 24), u16(pos + 26), u16(pos + 28)
            for i in range(attr_count):
                attr = pos + 16 + attr_start + i * attr_size
                ns, name, raw = u32(attr), string(u32(attr + 4)), u32(attr + 8)
                kind_value, value = data[attr + 15], u32(attr + 16)
                if raw != 0xffffffff: text = string(raw)
                elif kind_value == 3: text = string(value)
                elif kind_value == 18: text = 'true' if value else 'false'
                elif kind_value == 1: text = f'@0x{value:08x}'
                elif kind_value == 17: text = hex(value)
                else: text = str(value)
                node.set(('{' + string(ns) + '}' if ns != 0xffffffff else '') + name, text)
            if stack: stack[-1].append(node)
            else: root = node
            stack.append(node)
        elif kind == 0x103:
            assert stack, 'Unexpected end element'
            stack.pop()
        pos += size
    assert root is not None and not stack, 'Incomplete manifest'
    return root


def load_manifest(path):
    path = Path(path)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive: return decode_axml(archive.read('AndroidManifest.xml'))
    data = path.read_bytes()
    return ET.fromstring(data) if data.lstrip().startswith(b'<') else decode_axml(data)


def inspect_manifest(root):
    app = root.find('application')
    sdk = root.find('uses-sdk')
    return {
        'package': root.get('package'),
        'sdk': {} if sdk is None else {k.removeprefix(A): v for k, v in sdk.attrib.items()},
        'permissions': [{k.removeprefix(A): v for k, v in node.attrib.items()}
                        for node in root if node.tag.startswith('uses-permission')],
        'application': {} if app is None else {k.removeprefix(A): v for k, v in app.attrib.items()},
        'components': [] if app is None else [dict(type=node.tag, **{k.removeprefix(A): v for k, v in node.attrib.items()})
                for node in app if node.tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider')],
    }


def check_minimal(root):
    report = inspect_manifest(root)
    assert report['package'] == PACKAGE, 'Unexpected application ID'
    assert report['sdk'].get('minSdkVersion') == '31', 'Expected Android 12 minimum'
    assert report['sdk'].get('targetSdkVersion') == '36', 'Unexpected target SDK'
    allowed = {'android.permission.BLUETOOTH_SCAN', 'android.permission.BLUETOOTH_CONNECT',
               PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
    requested = {p['name'] for p in report['permissions']}
    assert requested <= allowed, f'Unexpected permissions: {requested - allowed}'
    assert allowed - {PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'} <= requested, 'Missing BLE permission'
    scan = next(p for p in report['permissions'] if p['name'] == 'android.permission.BLUETOOTH_SCAN')
    flags = scan.get('usesPermissionFlags', '')
    assert flags == 'neverForLocation' or (flags and int(flags, 0) & 0x10000), 'SCAN must never derive location'
    for permission in root.findall('permission'):
        assert permission.get(A + 'name') == PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', 'Unexpected custom permission'
        assert permission.get(A + 'protectionLevel') in ('signature', '2', '0x2'), 'Receiver guard must be signature-only'
    app = root.find('application')
    assert app is not None
    assert app.get(A + 'name', 'android.app.Application') == 'android.app.Application', 'Unexpected Application startup'
    assert app.get(A + 'allowBackup') == 'false', 'Backup must be disabled'
    assert app.get(A + 'usesCleartextTraffic') == 'false', 'Cleartext must be disabled'
    assert app.get(A + 'debuggable', 'false') == 'false', 'Runtime must not be debuggable'
    assert app.get(A + 'requestLegacyExternalStorage', 'false') == 'false'
    assert not root.findall('instrumentation') and not root.findall('queries'), 'Unexpected instrumentation or package visibility'
    for tag in ('service', 'receiver', 'provider', 'activity-alias'):
        assert not app.findall(tag), f'Unneeded {tag}'
    activities = app.findall('activity')
    assert len(activities) == 1 and activities[0].get(A + 'name') == ACTIVITY
    assert activities[0].get(A + 'exported') == 'true'
    assert [n.get(A + 'name') for n in activities[0].findall('intent-filter/action')] == ['android.intent.action.MAIN']
    assert [n.get(A + 'name') for n in activities[0].findall('intent-filter/category')] == ['android.intent.category.LAUNCHER']
    assert not activities[0].findall('intent-filter/data'), 'Unexpected deep link'
    report['minimal_policy'] = 'PASS'
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--xml-output')
    args = parser.parse_args()
    root = load_manifest(args.manifest)
    if args.xml_output: ET.ElementTree(root).write(args.xml_output, encoding='utf-8', xml_declaration=True)
    print(json.dumps(check_minimal(root) if args.check else inspect_manifest(root), ensure_ascii=False, indent=2))
