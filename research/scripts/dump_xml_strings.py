import zipfile

with zipfile.ZipFile('Bajaj+Ride+Connect_1.11.80_APKPure.xapk', 'r') as z:
    with zipfile.ZipFile(z.open('com.bajajconnect.rideapp.apk'), 'r') as apk:
        for fname in ['res/layout/gmap_route_pp.xml', 'res/layout/layout_navigation_view.xml', 'res/layout/tft_map_view.xml']:
            try:
                raw = apk.read(fname)
                # Extract ASCII/UTF-8 strings from binary XML
                import re
                strings = re.findall(b'[\x20-\x7e]{3,}', raw)
                print(f'=== {fname} ===')
                for s in strings:
                    print('  ', s.decode('ascii'))
            except Exception as e:
                print(f'{fname}: {e}')
