"""CI check for the Linux companion: acts as a phone and verifies the uinput pad appears.

Usage: python3 smoke_test.py [host] [port]
"""
import socket
import struct
import sys
import time

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 47800


def frame(seq, kind, payload):
    return struct.pack("<HBBHB", 7 + len(payload), 1, 9, seq, kind) + payload


def read_message(sock):
    header = sock.recv(5, socket.MSG_WAITALL)
    length, _, _, kind = struct.unpack("<HBBB", header)
    return kind, sock.recv(length - 5, socket.MSG_WAITALL)


for _ in range(50):
    try:
        sock = socket.create_connection((host, port), timeout=5)
        break
    except OSError:
        time.sleep(0.2)
else:
    sys.exit("companion not listening")

sock.sendall(frame(0, 0, struct.pack(">Q", 0x0123456789ABCDEF) + b"OGP\x01"))
kind, reply = read_message(sock)
while kind != 0:  # skip early feedback
    kind, reply = read_message(sock)
os_code = reply[2]
assert os_code == 2, f"expected OS 2 (Linux), got {os_code}"
assert reply[3:6] == b"OGP", "missing OpenGamepad extension"

# A pressed, left stick right, right trigger half.
sock.sendall(frame(1, 1, struct.pack("<Hhhhhbb", 0x1000, 20000, 0, 0, 0, 0, 127)))
time.sleep(0.5)
devices = open("/proc/bus/input/devices").read()
assert "Microsoft X-Box 360 pad" in devices, devices
assert "OpenGamepad Keyboard" in devices, devices
sock.close()
print("ok: Linux companion created the virtual Xbox 360 pad and keyboard")
