"""Live performance numbers for the phone's Stats page, like Task Manager's Performance tab.

CPU, memory, network, disk and processes come from psutil on every system.
GPU load comes from Windows performance counters, which cover AMD, NVIDIA and Intel alike,
or from the amdgpu driver files on Linux.
"""

from __future__ import annotations

import ctypes
import glob
import platform
import sys
import threading
import time
from pathlib import Path

import psutil

PROCESS_EVERY = 3.0  # process list refresh, in seconds. Scanning processes is the costly part.


class _WindowsGpu:
    """3D engine load and dedicated memory from the GPU performance counters (PDH)."""

    PDH_FMT_DOUBLE = 0x00000200
    PDH_MORE_DATA = 0x800007D2

    class _Value(ctypes.Structure):
        _fields_ = [("status", ctypes.c_uint32), ("value", ctypes.c_double)]

    class _Item(ctypes.Structure):
        pass

    _Item._fields_ = [("name", ctypes.c_wchar_p), ("value", _Value)]

    def __init__(self):
        from ctypes import wintypes

        self.pdh = ctypes.WinDLL("pdh")
        self.pdh.PdhOpenQueryW.argtypes = (wintypes.LPCWSTR, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p))
        self.pdh.PdhAddEnglishCounterW.argtypes = (ctypes.c_void_p, wintypes.LPCWSTR, ctypes.c_size_t,
                                                   ctypes.POINTER(ctypes.c_void_p))
        self.pdh.PdhCollectQueryData.argtypes = (ctypes.c_void_p,)
        self.pdh.PdhGetFormattedCounterArrayW.argtypes = (
            ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
            ctypes.c_void_p)
        self.pdh.PdhGetFormattedCounterArrayW.restype = ctypes.c_uint32
        self.query = ctypes.c_void_p()
        if self.pdh.PdhOpenQueryW(None, 0, ctypes.byref(self.query)) != 0:
            raise OSError("PdhOpenQuery failed")
        self.engine = self._add(r"\GPU Engine(*engtype_3D)\Utilization Percentage")
        self.memory = self._add(r"\GPU Adapter Memory(*)\Dedicated Usage")
        self.pdh.PdhCollectQueryData(self.query)
        self.name, self.total = self._adapter_info()

    def _add(self, path: str):
        counter = ctypes.c_void_p()
        if self.pdh.PdhAddEnglishCounterW(self.query, path, 0, ctypes.byref(counter)) != 0:
            return None
        return counter

    def _values(self, counter) -> list[float]:
        from ctypes import wintypes

        if counter is None:
            return []
        size, count = wintypes.DWORD(0), wintypes.DWORD(0)
        rc = self.pdh.PdhGetFormattedCounterArrayW(counter, self.PDH_FMT_DOUBLE, ctypes.byref(size),
                                                   ctypes.byref(count), None)
        if rc != self.PDH_MORE_DATA or not size.value:
            return []
        buf = ctypes.create_string_buffer(size.value)
        rc = self.pdh.PdhGetFormattedCounterArrayW(counter, self.PDH_FMT_DOUBLE, ctypes.byref(size),
                                                   ctypes.byref(count), buf)
        if rc != 0:
            return []
        items = ctypes.cast(buf, ctypes.POINTER(self._Item))
        return [items[i].value.value for i in range(count.value) if items[i].value.status in (0, 1)]

    @staticmethod
    def _adapter_info() -> tuple[str, int]:
        """Name and VRAM size of the largest display adapter, from the driver's registry entry."""
        import winreg

        best = ("GPU", 0)
        key_path = r"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}"
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_path) as root:
                for i in range(32):
                    try:
                        sub = winreg.EnumKey(root, i)
                    except OSError:
                        break
                    try:
                        with winreg.OpenKey(root, sub) as k:
                            name = winreg.QueryValueEx(k, "DriverDesc")[0]
                            try:
                                size = int(winreg.QueryValueEx(k, "HardwareInformation.qwMemorySize")[0])
                            except OSError:
                                size = 0
                            if size >= best[1]:
                                best = (str(name), size)
                    except OSError:
                        continue
        except OSError:
            pass
        return best

    def sample(self) -> dict:
        self.pdh.PdhCollectQueryData(self.query)
        load = min(100.0, sum(self._values(self.engine)))
        used = int(sum(self._values(self.memory)))
        out = {"gpu": round(load, 1), "gpu_name": self.name}
        if used:
            out["vram_used"] = used
        if self.total:
            out["vram_total"] = self.total
        return out


class _LinuxGpu:
    """amdgpu exposes load, VRAM and temperature as plain files."""

    def __init__(self):
        cards = [Path(p).parent for p in glob.glob("/sys/class/drm/card*/device/gpu_busy_percent")]
        if not cards:
            raise OSError("no amdgpu card")
        self.dev = cards[0]
        name = self.dev / "product_name"
        self.name = name.read_text().strip() if name.exists() else "AMD GPU"

    def _read(self, name: str) -> int | None:
        try:
            return int((self.dev / name).read_text().strip())
        except (OSError, ValueError):
            return None

    def sample(self) -> dict:
        out = {"gpu_name": self.name}
        busy = self._read("gpu_busy_percent")
        if busy is not None:
            out["gpu"] = float(busy)
        used, total = self._read("mem_info_vram_used"), self._read("mem_info_vram_total")
        if used is not None and total:
            out["vram_used"], out["vram_total"] = used, total
        for temp in glob.glob(str(self.dev / "hwmon/hwmon*/temp1_input")):
            try:
                out["gpu_temp"] = int(Path(temp).read_text()) / 1000
            except (OSError, ValueError):
                pass
            break
        return out


def _cpu_name() -> str:
    if sys.platform == "win32":
        try:
            import winreg

            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                                r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as k:
                return str(winreg.QueryValueEx(k, "ProcessorNameString")[0]).strip()
        except OSError:
            pass
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "CPU"


class PerfMonitor:
    """Samples once a second while a phone shows the Stats page, and sleeps otherwise."""

    def __init__(self):
        self._lock = threading.Lock()
        self._last = {}
        self._wanted_until = 0.0
        self._thread: threading.Thread | None = None
        self._gpu = None
        self._gpu_tried = False
        self._procs: list[dict] = []
        self._procs_at = 0.0
        self._proc_cache: dict[int, psutil.Process] = {}
        self.cpu_name = _cpu_name()

    def snapshot(self) -> dict:
        """Latest numbers. Starts the sampler if it is not running."""
        with self._lock:
            self._wanted_until = time.monotonic() + 10
            if self._thread is None or not self._thread.is_alive():
                self._thread = threading.Thread(target=self._run, daemon=True, name="perf")
                self._thread.start()
            return dict(self._last)

    def _gpu_backend(self):
        if not self._gpu_tried:
            self._gpu_tried = True
            try:
                self._gpu = _WindowsGpu() if sys.platform == "win32" else _LinuxGpu()
            except Exception:  # noqa: BLE001 - no GPU counters is fine, the page hides the card
                self._gpu = None
        return self._gpu

    def _run(self) -> None:
        psutil.cpu_percent(percpu=True)
        net, disk, at = psutil.net_io_counters(), psutil.disk_io_counters(), time.monotonic()
        while time.monotonic() < self._wanted_until:
            time.sleep(1)
            now = time.monotonic()
            dt = max(0.001, now - at)
            sample = self.sample_once(net, disk, dt)
            net, disk, at = psutil.net_io_counters(), psutil.disk_io_counters(), now
            with self._lock:
                self._last = sample

    def sample_once(self, net0, disk0, dt: float) -> dict:
        cores = psutil.cpu_percent(percpu=True)
        mem = psutil.virtual_memory()
        net1, disk1 = psutil.net_io_counters(), psutil.disk_io_counters()
        out = {
            "cpu": round(sum(cores) / max(1, len(cores)), 1),
            "cores": [round(c) for c in cores],
            "cpu_name": self.cpu_name,
            "ram_used": mem.total - mem.available,
            "ram_total": mem.total,
            "net_down": int((net1.bytes_recv - net0.bytes_recv) / dt) if net0 else 0,
            "net_up": int((net1.bytes_sent - net0.bytes_sent) / dt) if net0 else 0,
        }
        if disk0 and disk1:
            out["disk_read"] = int((disk1.read_bytes - disk0.read_bytes) / dt)
            out["disk_write"] = int((disk1.write_bytes - disk0.write_bytes) / dt)
        try:
            freq = psutil.cpu_freq()
            if freq and freq.current:
                out["cpu_mhz"] = int(freq.current)
        except (OSError, NotImplementedError):
            pass
        gpu = self._gpu_backend()
        if gpu is not None:
            try:
                out.update(gpu.sample())
            except Exception:  # noqa: BLE001
                pass
        if time.monotonic() - self._procs_at > PROCESS_EVERY:
            self._procs = self._top_processes()
            self._procs_at = time.monotonic()
        out["procs"] = self._procs
        return out

    def _top_processes(self, limit: int = 7) -> list[dict]:
        """Busiest apps, with helper processes of the same name added together like Task Manager."""
        ncpu = psutil.cpu_count() or 1
        totals: dict[str, list[float]] = {}
        alive = set()
        for proc in psutil.process_iter(["name", "memory_info"]):
            pid = proc.pid
            alive.add(pid)
            cached = self._proc_cache.setdefault(pid, proc)
            try:
                cpu = cached.cpu_percent(None) / ncpu
                name = proc.info["name"] or "?"
                mem = proc.info["memory_info"].rss if proc.info["memory_info"] else 0
            except (psutil.Error, OSError):
                continue
            if pid == 0 or name.lower() in ("system idle process", "idle"):
                continue
            entry = totals.setdefault(name, [0.0, 0])
            entry[0] += cpu
            entry[1] += mem
        for pid in list(self._proc_cache):
            if pid not in alive:
                del self._proc_cache[pid]
        ranked = sorted(totals.items(), key=lambda kv: (kv[1][0], kv[1][1]), reverse=True)[:limit]
        return [{"name": n.removesuffix(".exe"), "cpu": round(v[0], 1), "mem": int(v[1])} for n, v in ranked]
