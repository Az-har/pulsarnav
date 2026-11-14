import zipfile

with zipfile.ZipFile('Bajaj+Ride+Connect_1.11.80_APKPure.xapk', 'r') as z:
    with zipfile.ZipFile(z.open('com.bajajconnect.rideapp.apk'), 'r') as apk:
        for name in apk.namelist():
            if 'layout' in name and any(k in name for k in ['presentation', 'map', 'tft', 'nav', 'route']):
                print(name)
