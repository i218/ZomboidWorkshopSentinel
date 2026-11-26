"""One-item Windows experiment using the installed Steamworks DLL directly."""
import argparse
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


APP = 108600
ITEM = 3822893607


class Callback(c.Structure):
    _fields_ = [("user", c.c_int32), ("kind", c.c_int32),
                ("data", c.c_void_p), ("size", c.c_int32)]


class DownloadResult(c.Structure):
    _fields_ = [("app", c.c_uint32), ("item", c.c_uint64), ("result", c.c_int32)]


def emit(**value):
    print(json.dumps(value), flush=True)


def download(root, depot):
    api = c.CDLL(str(root / "steam_api64.dll"))

    def bind(name, result, *args):
        function = getattr(api, name)
        function.restype = result
        function.argtypes = args
        return function

    ptr, text = c.c_void_p, c.c_char_p
    integer, uint = c.c_int32, c.c_uint32
    item_id = c.c_uint64
    call = bind("SteamInternal_GameServer_Init_V2", integer, uint, c.c_uint16,
                c.c_uint16, integer, text, text, ptr)
    error = c.create_string_buffer(1024)
    # Loopback, OS-assigned ports, no authentication or VAC for this test session.
    result = call(0x7F000001, 0, 0, 1, b"1.0.0.0", b"SteamGameServer015\0STEAMUGC_INTERFACE_VERSION021\0\0", error)
    if result:
        raise RuntimeError(f"Steamworks init={result}: {error.value.decode(errors='replace')}")
    shutdown = bind("SteamGameServer_Shutdown", None)
    server = None
    try:
        server = bind("SteamAPI_SteamGameServer_v015", ptr)()
        ugc = bind("SteamAPI_SteamGameServerUGC_v021", ptr)()
        if not server or not ugc:
            raise RuntimeError("Steamworks interface unavailable")
        cache = root / "download-cache"
        cache.mkdir()
        if not bind("SteamAPI_ISteamUGC_BInitWorkshopForGameServer", c.c_bool, ptr, uint, text)(
                ugc, depot, os.fsencode(cache)):
            raise RuntimeError(f"Steamworks refused isolated Workshop depot {depot}")
        emit(event="cache_selected", depot=depot, cache=str(cache))
        for key, value in [("Product", b"zomboid"), ("ModDir", b"zomboid"),
                           ("GameDescription", b"Project Zomboid Workshop probe")]:
            bind("SteamAPI_ISteamGameServer_Set" + key, None, ptr, text)(server, value)
        bind("SteamAPI_ISteamGameServer_SetDedicatedServer", None, ptr, c.c_bool)(server, True)
        bind("SteamAPI_ISteamGameServer_SetAdvertiseServerActive", None, ptr, c.c_bool)(server, False)
        bind("SteamAPI_ManualDispatch_Init", None)()
        pipe = bind("SteamGameServer_GetHSteamPipe", integer)()
        frame = bind("SteamAPI_ManualDispatch_RunFrame", None, integer)
        next_callback = bind("SteamAPI_ManualDispatch_GetNextCallback", c.c_bool, integer, c.POINTER(Callback))
        free_callback = bind("SteamAPI_ManualDispatch_FreeLastCallback", None, integer)
        logged_on = bind("SteamAPI_ISteamGameServer_BLoggedOn", c.c_bool, ptr)

        def pump():
            frame(pipe)
            message = Callback()
            while next_callback(pipe, c.byref(message)):
                try:
                    if message.kind in (101, 102, 103):
                        status = c.c_int32.from_address(message.data).value if message.size >= 4 else None
                        emit(event="connection_callback", kind=message.kind, status=status)
                    if message.kind == 3406 and message.size >= c.sizeof(DownloadResult):
                        outcome = DownloadResult.from_address(message.data)
                        if outcome.app == APP and outcome.item == ITEM:
                            yield outcome.result
                finally:
                    free_callback(pipe)

        bind("SteamAPI_ISteamGameServer_LogOnAnonymous", None, ptr)(server)
        until = time.monotonic() + 40
        while not logged_on(server):
            list(pump())
            if time.monotonic() >= until:
                raise RuntimeError("Anonymous Steam login timed out")
            time.sleep(.1)
        emit(event="anonymous_login")
        if not bind("SteamAPI_ISteamUGC_DownloadItem", c.c_bool, ptr, item_id, c.c_bool)(ugc, ITEM, False):
            raise RuntimeError("DownloadItem rejected the request")
        until = time.monotonic() + 90
        while True:
            results = list(pump())
            if results:
                emit(event="download_callback", result=results[0])
                if results[0] != 1:
                    raise RuntimeError(f"DownloadItemResult={results[0]}")
                break
            if time.monotonic() >= until:
                get_state = bind("SteamAPI_ISteamUGC_GetItemState", uint, ptr, item_id)
                raise RuntimeError(f"Download callback timed out, item state={get_state(ugc, ITEM)}")
            time.sleep(.1)
        location, size, timestamp = c.create_string_buffer(4096), item_id(), uint()
        install = bind("SteamAPI_ISteamUGC_GetItemInstallInfo", c.c_bool, ptr, item_id,
                       c.POINTER(item_id), ptr, uint, c.POINTER(uint))
        if not install(ugc, ITEM, c.byref(size), location, len(location), c.byref(timestamp)):
            raise RuntimeError("GetItemInstallInfo returned false")
        path = Path(os.fsdecode(location.value)).resolve(strict=True)
        if not path.is_relative_to(cache.resolve()):
            raise RuntimeError(f"Steam reported an install path outside the test cache: {path}")
        files = sorted(p for p in path.rglob("*") if p.is_file())
        if not files or not size.value or not timestamp.value:
            raise RuntimeError("Incomplete install information")
        payload = [{"path": p.relative_to(path).as_posix(), "sha256": sha256(p)} for p in files]
        file_bytes = sum(p.stat().st_size for p in files)
        if file_bytes != size.value:
            raise RuntimeError(f"Installed file size mismatch: {file_bytes} != {size.value}")
        (root / "payload-sha256.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
        emit(event="installed", path=str(path), steam_bytes=size.value,
             file_bytes=file_bytes, timestamp=timestamp.value, file_count=len(files))
    finally:
        if server:
            bind("SteamAPI_ISteamGameServer_LogOff", None, ptr)(server)
        shutdown()


def sha256(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def snapshot(roots):
    records = {}
    for root in roots:
        if not root.is_dir():
            raise ValueError(f"Expected working cache directory: {root}")
        pending = [root]
        while pending:
            with os.scandir(pending.pop()) as listing:
                for entry in listing:
                    stat = entry.stat(follow_symlinks=False)
                    path = Path(entry.path)
                    if entry.is_dir(follow_symlinks=False) and not path.is_junction():
                        pending.append(path)
                    digest = sha256(path) if entry.is_file(follow_symlinks=False) and (
                        entry.name.endswith(".acf") or str(ITEM) in path.parts) else None
                    # Directory size on Windows represents filesystem bookkeeping.
                    # Compare their presence and mtime, and compare sizes for files.
                    records[entry.path] = (None if entry.is_dir(follow_symlinks=False) else stat.st_size,
                                           stat.st_mtime_ns, digest)
    return records


def installed_manifest(cache):
    # Steam's installed section is distinct from the metadata for remote revisions.
    for acf in sorted(cache.rglob("appworkshop_*.acf")):
        source = acf.read_text(encoding="utf-8-sig")
        rows = r'(?:\s*"\d+"\s*\{[^{}]*\})*'
        match = re.search(r'"WorkshopItemsInstalled"\s*\{(?P<body>' + rows + r')\s*\}', source)
        row = re.search(rf'"{ITEM}"\s*\{{([^{{}}]*)\}}', match["body"]) if match else None
        manifest = re.search(r'"manifest"\s*"([1-9][0-9]{0,19})"', row[1]) if row else None
        if manifest:
            size = re.search(r'"size"\s*"(\d+)"', row[1])
            timestamp = re.search(r'"timeupdated"\s*"(\d+)"', row[1])
            return {"source": str(acf), "manifest_id": manifest[1],
                    "acf_size": int(size[1]) if size else None,
                    "acf_timestamp": int(timestamp[1]) if timestamp else None}
    return None


def run(args):
    root = args.run_dir.resolve()
    watched = [path.resolve(strict=True) for path in args.watch]
    if any(root.is_relative_to(path) or path.is_relative_to(root) for path in watched):
        raise ValueError("Test directory overlaps a working cache")
    root.mkdir(parents=True)
    shutil.copyfile(args.dll, root / "steam_api64.dll")
    (root / "steam_appid.txt").write_text(str(APP), encoding="ascii")
    env = dict(os.environ, SteamAppId=str(APP), SteamGameId=str(APP))
    before = snapshot(watched)
    output = root / "steamworks.jsonl"
    try:
        with output.open("w", encoding="utf-8") as log:
            outcome = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--worker",
                                      "--run-dir", str(root), "--depot", str(args.depot)],
                                     cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=150)
    finally:
        after = snapshot(watched)
        changes = sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key))
        report = {"roots": [str(path) for path in watched], "entries_before": len(before),
                  "entries_after": len(after), "hashed_entries": sum(row[2] is not None for row in after.values()),
                  "changed": changes}
        (root / "cache-comparison.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        emit(event="working_cache_comparison", changed_count=len(changes),
             **{key: value for key, value in report.items() if key != "changed"})
    raw = output.read_text(encoding="utf-8")
    print(raw, end="")
    if outcome.returncode or changes:
        return 1
    manifest = installed_manifest(root / "download-cache")
    emit(event="manifest", **(manifest or {"manifest_id": None}))
    if not manifest:
        return 2
    events = [json.loads(line) for line in raw.splitlines() if line.startswith('{"event":')]
    installed = next(event for event in events if event["event"] == "installed")
    if (manifest["acf_size"], manifest["acf_timestamp"]) != (installed["steam_bytes"], installed["timestamp"]):
        raise RuntimeError("Installed ACF does not match GetItemInstallInfo")
    (root / "result.json").write_text(json.dumps({"installed": installed, "acf": manifest,
                                               "working_cache": report}, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--dll", type=Path)
    parser.add_argument("--watch", action="append", type=Path, default=[])
    parser.add_argument("--depot", type=int, default=APP)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    options = parser.parse_args()
    if options.worker:
        download(options.run_dir, options.depot)
    else:
        if not options.dll or not options.watch:
            parser.error("--dll and at least one --watch cache are required")
        sys.exit(run(options))
