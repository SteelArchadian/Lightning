"""Writes data/lightning/structure/empty.nbt: an empty 9x6x9 structure used as the GameTest arena."""
import gzip, struct, sys

def name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def tag(tid, key, payload):
    return bytes([tid]) + name(key) + payload

def int_list(key, values):
    return tag(9, key, bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values))

def empty_list(key):
    return tag(9, key, bytes([0]) + struct.pack(">i", 0))

air = tag(8, "Name", name("minecraft:air")) + b"\x00"
palette = tag(9, "palette", bytes([10]) + struct.pack(">i", 1) + air)
root = int_list("size", [9, 6, 9]) + palette + empty_list("blocks") + empty_list("entities") + tag(3, "DataVersion", struct.pack(">i", 3955))
data = tag(10, "", root + b"\x00")
with gzip.open(sys.argv[1], "wb") as f:
    f.write(data)
