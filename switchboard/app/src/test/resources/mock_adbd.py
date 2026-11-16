#!/usr/bin/env python3
"""Mock adbd for AdbClient tests: real AUTH (verifies RSA signatures and parses Android's public key format), shell, sync SEND."""
import base64, os, socket, struct, subprocess, sys, threading
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa, utils

PORT, OUTDIR, MODE = int(sys.argv[1]), sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else "normal")
CNXN, AUTH, OPEN, OKAY, CLSE, WRTE, STLS = 0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257, 0x534c5453
KNOWN = []   # accepted public keys (persist across connections, like adb_keys)
TOKEN = os.urandom(20)

def send(c, cmd, a0, a1, data=b""):
    c.sendall(struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data)

def recv(c):
    h = b""
    while len(h) < 24:
        part = c.recv(24 - len(h))
        if not part: raise EOFError
        h += part
    cmd, a0, a1, n, chk, magic = struct.unpack("<6I", h)
    assert magic == cmd ^ 0xffffffff, "bad magic"
    d = b""
    while len(d) < n: d += c.recv(n - len(d))
    assert sum(d) & 0xffffffff == chk, "bad checksum"
    return cmd, a0, a1, d

def parse_pubkey(blob):
    raw = base64.b64decode(blob.split(b" ")[0])
    words, n0inv = struct.unpack("<II", raw[:8])
    n = int.from_bytes(raw[8:8 + words * 4], "little")
    rr = int.from_bytes(raw[8 + words * 4:8 + words * 8], "little")
    e = struct.unpack("<I", raw[8 + words * 8:12 + words * 8])[0]
    assert (n0inv * n) % 2**32 == 2**32 - 1, "bad n0inv"
    assert rr == pow(2, 4096, n), "bad rr"
    return rsa.RSAPublicNumbers(e, n).public_key()

def verify(sig):
    for k in KNOWN:
        try: k.verify(sig, TOKEN, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1())); return True
        except Exception: pass
    return False

def handle(c):
    try:
        cmd, *_ = recv(c); assert cmd == CNXN
        if MODE == "tls": return send(c, STLS, 0x01000000, 0)
        send(c, AUTH, 1, 0, TOKEN)
        while True:
            cmd, a0, a1, d = recv(c)
            if cmd == AUTH and a0 == 2:
                if verify(d): break
                send(c, AUTH, 1, 0, TOKEN)
            elif cmd == AUTH and a0 == 3:
                KNOWN.append(parse_pubkey(d)); break   # "user tapped Allow"
        send(c, CNXN, 0x01000000, 4096, b"device::ro.product.model=MockPhone;\x00")
        streams = {}
        while True:
            cmd, a0, a1, d = recv(c)
            if cmd == OPEN:
                svc = d.rstrip(b"\x00").decode(); rid = len(streams) + 100
                send(c, OKAY, rid, a0)
                if svc.startswith("shell:"):
                    out = subprocess.run(["sh", "-c", svc[6:]], capture_output=True, cwd=OUTDIR).stdout + b""
                    for i in range(0, len(out), 1000):
                        send(c, WRTE, rid, a0, out[i:i + 1000]); recv(c)   # wait for OKAY
                    send(c, CLSE, rid, a0); recv(c)
                else:
                    streams[a0] = (rid, b"")
            elif cmd == WRTE:
                rid, buf = streams[a0]; buf += d; streams[a0] = (rid, buf)
                send(c, OKAY, rid, a0)
                if b"DONE" in buf[-8:-4] or (len(buf) >= 8 and buf.rfind(b"DONE") == len(buf) - 8):
                    # parse SEND/DATA/DONE
                    n = struct.unpack("<I", buf[4:8])[0]; spec = buf[8:8 + n].decode(); path = spec.rsplit(",", 1)[0]
                    i, data = 8 + n, b""
                    while buf[i:i + 4] == b"DATA":
                        m = struct.unpack("<I", buf[i + 4:i + 8])[0]; data += buf[i + 8:i + 8 + m]; i += 8 + m
                    with open(os.path.join(OUTDIR, os.path.basename(path)), "wb") as f: f.write(data)
                    send(c, WRTE, rid, a0, b"OKAY" + struct.pack("<I", 0)); recv(c)
            elif cmd == CLSE:
                send(c, CLSE, 0, a0)
    except (EOFError, ConnectionError, OSError):
        pass
    finally:
        c.close()

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(("127.0.0.1", PORT)); srv.listen(5)
print("ready", flush=True)
while True:
    c, _ = srv.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
