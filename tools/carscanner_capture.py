#!/usr/bin/env python3
"""
Car Scanner capture honeypot.

A minimal ELM327-over-TCP emulator whose job is NOT to be a faithful adapter, but to make an OBD app
(Car Scanner) connect, select a manufacturer profile, and *enumerate every sensor* — while we log the
exact request each sensor uses: the ECU header (AT SH) plus the command (22 <DID> / 01 <PID>).

It answers AT setup with OK/identifiers and answers every OBD request with a positive stub, so the app
believes every sensor works and keeps polling. We never read Car Scanner's database — we only observe
the requests it emits, which is the part you can't reverse-engineer blind.

Run:  python tools/carscanner_capture.py --port 35000 --log tools/carscanner-capture.csv
Then: adb -s <device> reverse tcp:35000 tcp:35000
      In Car Scanner: adapter = Wi-Fi, host 127.0.0.1, port 35000, select the Li Auto profile, connect.
"""
import argparse
import csv
import re
import socket
import sys
import threading
import time

PROMPT = b"\r\r>"


class Elm:
    """Per-connection ELM327 state + responder."""

    def __init__(self, logwriter, logfile, pid01_data="00", replay=None):
        self.headers_on = False          # AT H1
        self.spaces_on = False           # AT S1
        self.tx_header = None             # last AT SH <hdr>
        self.proto = None                 # active protocol number (from AT SP)
        self.auto = False                 # AT SP0 (auto) was used
        # Known data bytes returned for every (non-bitmask) mode-01 PID. A distinctive ramp lets us
        # read each sensor's byte offset + scale off the screen (value-injection formula cracking).
        self.pid01_data = [pid01_data[i:i + 2] for i in range(0, len(pid01_data), 2)] or ["00"]
        # Replay map: normalized command -> recorded real frames (or None for a recorded NO DATA).
        # When set, the init/identification commands answer with the real car's bytes so Car Scanner
        # believes it's a genuine Li Auto and engages the experimental profile; sensor PIDs not in the
        # map fall through to the synthetic ramp (so we can still inject values to decode formulas).
        self.replay = replay
        self.logwriter = logwriter
        self.logfile = logfile

    # --- response formatting -------------------------------------------------

    def _resp_header(self):
        """Responder arbitration id for an 11-bit physical request (request + 8), default 7E8."""
        if self.tx_header and len(self.tx_header) == 3:
            try:
                return "%03X" % (int(self.tx_header, 16) + 8)
            except ValueError:
                pass
        return "7E8"

    def _format(self, data_bytes):
        """Frame a service response as an ISO 15765 single frame: [header] PCI data."""
        data = list(data_bytes)
        toks = []
        if self.headers_on:
            toks.append(self._resp_header())
        toks.append("%02X" % len(data))  # SF PCI = data length (high nibble 0 = single frame)
        toks += data
        sep = " " if self.spaces_on else ""
        return sep.join(toks)

    def _replay_response(self, cmd):
        """Re-emit the recorded real frames for [cmd], reformatted to the current ATH/ATS state.
        Returns the response string, or None if [cmd] isn't in the replay map."""
        if self.replay is None or cmd not in self.replay:
            return None
        frames = self.replay[cmd]
        if not frames:
            return "NO DATA"
        sep = " " if self.spaces_on else ""
        lines = []
        for responder, data in frames:
            toks = ([responder] if self.headers_on else []) + data
            lines.append(sep.join(toks))
        return "\r".join(lines)

    # --- command handling ----------------------------------------------------

    def _log(self, raw):
        kind = "at" if raw.upper().startswith("AT") else "obd"
        self.logwriter.writerow([f"{time.time():.3f}", self.tx_header or "", raw, kind])
        self.logfile.flush()
        tag = "  <SH %s>" % self.tx_header if kind == "obd" and self.tx_header else ""
        print(f"  recv: {raw}{tag}", flush=True)

    def handle(self, raw):
        """Return the response string (without prompt) for one command line."""
        self._log(raw)
        cmd = raw.replace(" ", "").upper()

        if cmd.startswith("AT"):
            return self._handle_at(cmd)

        # Pretend to be a CAN 11-bit / ISO 15765-4 car only (protocol 6). Fail other protocols so
        # Car Scanner locks onto 6 — which is what a modern Li Auto actually uses (header 7E0).
        if self.proto not in (6, None):
            return "NO DATA"

        # Replay the real car's recorded identification responses (init map) when available.
        replayed = self._replay_response(cmd)
        if replayed is not None:
            return replayed

        # OBD service requests
        if cmd.startswith("22") and len(cmd) >= 6:          # ReadDataByIdentifier
            did = cmd[2:6]
            return self._format(["62", did[:2], did[2:], "00", "00"])
        if cmd.startswith("01") and len(cmd) >= 4:          # standard mode 01
            pid = cmd[2:4]
            if pid in ("00", "20", "40", "60", "80", "A0", "C0", "E0"):
                return self._format(["41", pid, "FF", "FF", "FF", "FF"])  # claim everything supported
            return self._format(["41", pid] + self.pid01_data)
        if cmd.startswith("09"):                            # vehicle info (VIN etc.) — skip
            return "NO DATA"
        if cmd in ("03", "07", "0A"):                       # DTC services — none stored
            return self._format(["43", "00"])
        return "NO DATA"

    def _handle_at(self, cmd):
        if cmd in ("ATZ", "ATWS"):
            return "ELM327 v1.5"
        if cmd == "ATI":
            return "ELM327 v1.5"
        if cmd == "AT@1":
            return "OBDII to RS232 Interpreter"
        if cmd == "ATRV":
            return "12.4V"
        if cmd.startswith("ATSP"):
            arg = cmd[4:]
            if arg in ("", "0", "00", "A0"):
                self.auto, self.proto = True, 6   # auto -> we "detect" CAN 11-bit/500k
            else:
                self.auto = False
                try:
                    self.proto = int(arg[-1], 16)
                except ValueError:
                    self.proto = None
            return "OK"
        if cmd == "ATDP":
            return "ISO 15765-4 (CAN 11/500)" if self.proto == 6 else "AUTO"
        if cmd == "ATDPN":
            # Real ELM prefixes auto-detected protocols with 'A'. Report 6 only when it's our car.
            if self.proto is None:
                return "A0" if self.auto else "0"
            return ("A" if self.auto else "") + ("%X" % self.proto)
        if cmd.startswith("ATH"):
            self.headers_on = cmd.endswith("1")
            return "OK"
        if cmd.startswith("ATS") and cmd[:4] in ("ATS0", "ATS1"):
            self.spaces_on = cmd.endswith("1")
            return "OK"
        if cmd.startswith("ATSH"):
            self.tx_header = cmd[4:] or None
            return "OK"
        # Everything else (ATE, ATL, ATSP, ATAT, ATCAF, ATCRA, ATFC, ATST, ATAL, ATBI, ...) -> OK
        return "OK"


def parse_frames(raw):
    """Split a recorded ELM reply into [(responder, [byte tokens]), …] — one entry per CAN frame."""
    frames = []
    cur = None
    for t in raw.split():
        if re.fullmatch(r"7E[0-9A-F]", t) or re.fullmatch(r"[0-9A-F]{8}", t):  # 11-bit or 29-bit id
            cur = (t, [])
            frames.append(cur)
        elif cur is not None:
            cur[1].append(t)
    return frames


def load_replay(path):
    """Load an init-map CSV (header,command,label,raw) into {normalized command -> frames|None}."""
    m = {}
    with open(path, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            cmd = (row.get("command") or "").replace(" ", "").upper()
            raw = (row.get("raw") or "").strip()
            if not cmd:
                continue
            collapsed = raw.replace(" ", "").upper()
            if not collapsed or "NODATA" in collapsed or "STOPPED" in collapsed:
                m[cmd] = None  # recorded as no data
            else:
                m[cmd] = parse_frames(raw)
    print(f"[*] Loaded {len(m)} replay commands from {path}", flush=True)
    return m


def serve(conn, addr, logwriter, logfile, pid01_data, replay):
    print(f"[+] Car Scanner connected from {addr}", flush=True)
    elm = Elm(logwriter, logfile, pid01_data, replay)
    buf = b""
    try:
        while True:
            chunk = conn.recv(1024)
            if not chunk:
                break
            buf += chunk
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                raw = line.decode("ascii", "ignore").strip()
                if not raw:
                    continue
                resp = elm.handle(raw)
                conn.sendall(resp.encode("ascii") + PROMPT)
    except OSError:
        pass
    finally:
        conn.close()
        print(f"[-] {addr} disconnected", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=35000)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--log", default="tools/carscanner-capture.csv")
    ap.add_argument("--pid01-data", default="00",
                    help="hex data bytes returned for every mode-01 PID, e.g. 11223344556677")
    ap.add_argument("--replay", default=None,
                    help="init-map CSV from the app's init recorder; replays the real car's "
                         "identification so Car Scanner engages the experimental Li profile")
    args = ap.parse_args()

    replay = load_replay(args.replay) if args.replay else None

    logfile = open(args.log, "a", newline="", encoding="utf-8")
    logwriter = csv.writer(logfile)
    if logfile.tell() == 0:
        logwriter.writerow(["epoch", "txHeader", "rawCommand", "kind"])
        logfile.flush()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)
    print(f"[*] ELM327 capture honeypot on {args.host}:{args.port}  ->  logging to {args.log}", flush=True)
    print("[*] Waiting for Car Scanner… (Ctrl-C to stop)", flush=True)
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve, args=(conn, addr, logwriter, logfile, args.pid01_data, replay), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[*] Stopping.", flush=True)
    finally:
        srv.close()
        logfile.close()


if __name__ == "__main__":
    sys.exit(main())
