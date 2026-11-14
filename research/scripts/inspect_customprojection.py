import zipfile

with zipfile.ZipFile('Bajaj+Ride+Connect_1.11.80_APKPure.xapk', 'r') as z:
    with zipfile.ZipFile(z.open('com.bajajconnect.rideapp.apk'), 'r') as apk:
        data = apk.read('classes3.dex')

string_ids_off = int.from_bytes(data[0x3c:0x40], 'little')
string_count = int.from_bytes(data[0x38:0x3c], 'little')
type_ids_off = int.from_bytes(data[0x44:0x48], 'little')
method_ids_off = int.from_bytes(data[0x5c:0x60], 'little')
class_defs_size = int.from_bytes(data[0x60:0x64], 'little')
class_defs_off = int.from_bytes(data[0x64:0x68], 'little')

def get_string(idx):
    if idx >= string_count: return f'<str_{idx}>'
    off = int.from_bytes(data[string_ids_off + idx*4 : string_ids_off + (idx+1)*4], 'little')
    pos = off
    while (data[pos] & 0x80) != 0: pos += 1
    pos += 1
    end = pos
    while data[end] != 0: end += 1
    return data[pos:end].decode('latin1', errors='ignore')

def get_type(idx):
    str_idx = int.from_bytes(data[type_ids_off + idx*4 : type_ids_off + (idx+1)*4], 'little')
    return get_string(str_idx)

def get_method(idx):
    class_idx = int.from_bytes(data[method_ids_off + idx*8 : method_ids_off + idx*8 + 2], 'little')
    name_idx = int.from_bytes(data[method_ids_off + idx*8 + 4 : method_ids_off + idx*8 + 8], 'little')
    return f'{get_type(class_idx)}->{get_string(name_idx)}'

class Reader:
    def __init__(self, pos): self.pos = pos
    def read_uleb(self):
        res, shift = 0, 0
        while True:
            b = data[self.pos]
            self.pos += 1
            res |= (b & 0x7f) << shift
            if (b & 0x80) == 0: break
            shift += 7
        return res

for i in range(class_defs_size):
    c_off = class_defs_off + i * 32
    c_idx = int.from_bytes(data[c_off:c_off+4], 'little')
    c_name = get_type(c_idx)
    if 'CustomProjection' in c_name and not '$' in c_name:
        print(f'Class: {c_name}')
        c_data_off = int.from_bytes(data[c_off+24:c_off+28], 'little')
        r = Reader(c_data_off)
        sf, inf, dm, vm = r.read_uleb(), r.read_uleb(), r.read_uleb(), r.read_uleb()
        for _ in range(sf + inf): r.read_uleb(); r.read_uleb()
        m_idx = 0
        for _ in range(dm):
            m_idx += r.read_uleb()
            r.read_uleb()
            code_off = r.read_uleb()
            print(f'  Direct: {get_method(m_idx)}')
        m_idx = 0
        for _ in range(vm):
            m_idx += r.read_uleb()
            r.read_uleb()
            code_off = r.read_uleb()
            print(f'  Virtual: {get_method(m_idx)}')
