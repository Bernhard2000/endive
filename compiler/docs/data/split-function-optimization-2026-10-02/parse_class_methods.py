from pathlib import Path
import struct, json, sys

def methods(path):
    data = path.read_bytes()
    pos = 8
    def read(fmt, size):
        nonlocal pos
        value = struct.unpack_from(fmt, data, pos)[0]
        pos += size
        return value
    def u1(): return read('>B', 1)
    def u2(): return read('>H', 2)
    def u4(): return read('>I', 4)
    pool = {}
    count = u2()
    index = 1
    while index < count:
        tag = u1()
        if tag == 1:
            size = u2()
            pool[index] = data[pos:pos + size].decode('utf-8', errors='replace')
            pos += size
        elif tag in (3, 4): pos += 4
        elif tag in (5, 6): pos += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): pos += 2
        elif tag in (9, 10, 11, 12, 17, 18): pos += 4
        elif tag == 15: pos += 3
        else: raise ValueError(tag)
        index += 1
    pos += 6
    interfaces = u2()
    pos += 2 * interfaces
    for _ in range(u2()):
        pos += 6
        for _ in range(u2()):
            u2()
            size = u4()
            pos += size
    result = []
    for _ in range(u2()):
        u2()
        name = pool[u2()]
        descriptor = pool[u2()]
        for _ in range(u2()):
            attr = pool[u2()]
            size = u4()
            start = pos
            if attr == 'Code':
                stack, locals_, length = u2(), u2(), u4()
                result.append(dict(name=name, descriptor=descriptor, code_length=length,
                                   max_stack=stack, max_locals=locals_))
            pos = start + size
    return result

if __name__ == '__main__':
    rows = []
    for path in Path(sys.argv[1]).glob('*.class'):
        rows.extend(methods(path))
    Path(sys.argv[2]).write_text(json.dumps(rows, indent=2) + '\n')
    print('methods', len(rows))
    for row in rows:
        if '_PyUnicode_ToNumeric' in row['name']: print(row)
