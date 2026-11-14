import zipfile

with zipfile.ZipFile('Bajaj+Ride+Connect_1.11.80_APKPure.xapk', 'r') as z:
    with zipfile.ZipFile(z.open('com.bajajconnect.rideapp.apk'), 'r') as apk:
        raw = apk.read('AndroidManifest.xml')
        # AXML string pool starts around byte 8
        str_count = int.from_bytes(raw[16:20], 'little')
        style_count = int.from_bytes(raw[20:24], 'little')
        flags = int.from_bytes(raw[24:28], 'little')
        str_off = int.from_bytes(raw[28:32], 'little') + 8
        offsets = [int.from_bytes(raw[36+i*4:40+i*4], 'little') for i in range(str_count)]
        is_utf8 = (flags & (1 << 8)) != 0
        strings = []
        for off in offsets:
            pos = str_off + off
            if is_utf8:
                # read 2 varints
                pos += 1 # len
                length = raw[pos]
                pos += 1
                strings.append(raw[pos:pos+length].decode('utf-8', errors='ignore'))
            else:
                length = int.from_bytes(raw[pos:pos+2], 'little')
                pos += 2
                strings.append(raw[pos:pos+length*2].decode('utf-16le', errors='ignore'))
        for s in strings:
            if any(k in s.lower() for k in ['navigation', 'map', 'presentation', 'virtualdisplay', 'com.google']):
                print(s)
