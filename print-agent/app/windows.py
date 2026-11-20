"""Windows spooler discovery and native GDI printing."""
from concurrent.futures import ThreadPoolExecutor, wait
from contextlib import contextmanager
import hashlib
import logging
from urllib.parse import quote

LOG = logging.getLogger("smartprint.windows")

# PRINTER_INFO_2 status and attribute flags.
OFFLINE = 0x4 | 0x80 | 0x1000
FAULTS = 0x2 | 0x8 | 0x10 | 0x20 | 0x40 | 0x800 | 0x40000 | 0x100000 | 0x200000
PROCESSING = 0x400 | 0x4000
WORK_OFFLINE = 0x400


def queue_report(queue, default, capabilities, jobs=()):
    name = queue["pPrinterName"]
    port = (queue.get("pPortName") or "").strip().casefold()
    driver = (queue.get("pDriverName") or "").casefold()
    virtual = (port in {"file:", "portprompt:", "nul:", "fax:"}
               or port.startswith(("shrfax:", "onenote", "xpsport"))
               or any(marker in driver for marker in
                      ("print to pdf", "pdf printer", "xps document writer", "onenote", "fax")))
    flags = queue.get("Status", 0)
    pending = [job for job in jobs if not job["Status"] & (0x4 | 0x80 | 0x100)]
    active = sum(bool(job["Status"] & (0x10 | 0x1000)) for job in pending)
    if flags & OFFLINE or queue.get("Attributes", 0) & WORK_OFFLINE:
        status = "OFFLINE"
    elif flags & 0x1:
        status = "PAUSED"
    elif flags & FAULTS:
        status = "ERROR"
    elif active or flags & PROCESSING:
        status = "BUSY"
    else:
        status = "FREE"
    # A shared spooler endpoint is evidence of a duplicate. A match is not
    # inferred from the display name or from two queues using the same driver.
    endpoint = f"{(queue.get('pServerName') or '').casefold()}|{port}"
    group = None if not port or virtual or "," in port else hashlib.sha256(endpoint.encode()).hexdigest()[:32]
    return dict(
        code=hashlib.sha256(name.casefold().encode()).hexdigest()[:48],
        name=name, uri="winspool://local/" + quote(name, safe=""),
        connectionType="WINDOWS", status=status,
        model=(queue.get("pDriverName") or "")[:200] or None,
        portName=(queue.get("pPortName") or "")[:512] or None,
        pendingJobs=len(pending) - active, activeJobs=active,
        colorSupported=capabilities[0], duplexSupported=capabilities[1],
        agentDefault=bool(default and name.casefold() == default.casefold()),
        virtualPrinter=virtual, deviceGroup=group,
    )


class WindowsSpooler:
    def __init__(self):
        import pywintypes
        import win32print
        self.api = win32print
        self.error = pywintypes.error
        self.destinations = {}
        self.details = ThreadPoolExecutor(max_workers=4, thread_name_prefix="printer-details")
        self.lookups = {}

    @contextmanager
    def printer(self, name):
        handle = self.api.OpenPrinter(name)
        try:
            yield handle
        finally:
            self.api.ClosePrinter(handle)

    def discover(self):
        # Level 4 gets the installed and per-user connected queue names from the
        # local spooler cache, independently of remote driver or queue access.
        flags = self.api.PRINTER_ENUM_LOCAL | self.api.PRINTER_ENUM_CONNECTIONS
        installed = self.api.EnumPrinters(flags, None, 4)
        try:
            default = self.api.GetDefaultPrinter()
        except self.error as exc:
            if exc.winerror not in (2, 1801):
                LOG.debug("Windows default lookup failed with Win32 error %s", exc.winerror)
            default = None

        reports = []
        destinations = {}
        queues = {}
        for entry in installed:
            name = entry["pPrinterName"]
            if name:
                queues.setdefault(name.casefold(), entry)
        for key in self.lookups.keys() - queues.keys():
            self.lookups.pop(key).cancel()

        for key, entry in queues.items():
            if key not in self.lookups:
                self.lookups[key] = self.details.submit(self.inspect_queue, entry, entry["pPrinterName"])
        # Remote spooler calls can block. Publish the installed queue even if its
        # metadata or workload cannot be established within this heartbeat.
        wait(self.lookups.values(), timeout=2)
        for key, entry in queues.items():
            name = entry["pPrinterName"]
            lookup = self.lookups[key]
            report = None
            if lookup.done() and not lookup.cancelled():
                self.lookups.pop(key)
                try:
                    report = lookup.result()
                except (ValueError, TypeError, KeyError) as exc:
                    LOG.debug("Windows queue %r returned invalid detail data: %s", name, type(exc).__name__)
            if report is None:
                report = queue_report(entry, default, (False, False))
                if report["status"] in ("FREE", "BUSY"):
                    report["status"] = "ERROR"
            report["agentDefault"] = bool(default and name.casefold() == default.casefold())
            destinations[report["uri"]] = name
            reports.append(report)
        self.destinations = destinations
        LOG.debug("Windows enumerated %s installed queues; system default=%r", len(reports), default)
        return reports

    def inspect_queue(self, entry, name):
        queue = dict(entry)
        capabilities = (False, False)
        jobs = None
        try:
            with self.printer(name) as handle:
                queue.update(self.api.GetPrinter(handle, 2))
                queue["pPrinterName"] = name  # Preserve the name used to open the installed queue.
                port = queue.get("pPortName") or ""
                capabilities = tuple(self.capability(name, port, field) for field in (32, 7))
                jobs = self.api.EnumJobs(handle, 0, max(queue.get("cJobs", 0) + 32, 256), 1)
        except self.error as exc:
            LOG.debug("Windows queue %r detail lookup failed with Win32 error %s",
                      name, exc.winerror)
        report = queue_report(queue, None, capabilities, jobs or ())
        if jobs is None and report["status"] in ("FREE", "BUSY"):
            # The installed queue exists, but its runtime state is unknown.
            report["status"] = "ERROR"
        return report

    def capability(self, name, port, field):
        try:
            return self.api.DeviceCapabilities(name, port, field) == 1
        except self.error as exc:
            LOG.debug("Windows queue %r capability %s failed with Win32 error %s",
                      name, field, exc.winerror)
            return False

    def recover(self, destination, title):
        with self.printer(destination) as handle:
            count = self.api.GetPrinter(handle, 2)["cJobs"]
            return [job["JobId"] for job in self.api.EnumJobs(handle, 0, count, 1)
                    if job["pDocument"] == title]

    def submit(self, destination, path, title, item):
        import pypdfium2
        from PIL import ImageWin
        import win32con
        import win32gui

        with self.printer(destination) as handle, pypdfium2.PdfDocument(path) as pdf:
            mode = self.api.GetPrinter(handle, 2)["pDevMode"]
            if mode is None:
                raise RuntimeError("The Windows driver did not provide print settings")
            mode.Fields |= (win32con.DM_ORIENTATION | win32con.DM_COLOR |
                            win32con.DM_DUPLEX | win32con.DM_COPIES)
            landscape = item["orientation"] == "LANDSCAPE"
            mode.Orientation = 2 if landscape else 1
            mode.Color = 2 if item["colorMode"] == "COLOR" else 1
            mode.Duplex = (3 if landscape else 2) if item["sideMode"] == "DUPLEX" else 1
            mode.Copies = 1
            if self.api.DocumentProperties(0, handle, destination, mode, mode,
                                           win32con.DM_IN_BUFFER | win32con.DM_OUT_BUFFER) != 1:
                raise RuntimeError("Windows rejected the printer settings")
            context = win32gui.CreateDC("WINSPOOL", destination, mode)
            try:
                width = self.api.GetDeviceCaps(context, win32con.HORZRES)
                height = self.api.GetDeviceCaps(context, win32con.VERTRES)
                job_id = self.api.StartDoc(context, (title, None, None, 0))
                try:
                    try:
                        self.api.SetJob(handle, job_id, 0, None, 8)  # JOB_CONTROL_RETAIN
                    except self.error:
                        pass  # Some drivers or remote queues reject retention.
                    for copy_index in range(item["copies"]):
                        for page in pdf:
                            try:
                                bitmap = page.render(scale=min(200 / 72, 4096 / max(page.get_size())))
                                try:
                                    image = bitmap.to_pil().convert("RGB")
                                finally:
                                    bitmap.close()
                            finally:
                                page.close()
                            scale = min(width / image.width, height / image.height)
                            w, h = int(image.width * scale), int(image.height * scale)
                            x, y = (width - w) // 2, (height - h) // 2
                            self.api.StartPage(context)
                            ImageWin.Dib(image).draw(int(context), (x, y, x + w, y + h))
                            self.api.EndPage(context)
                        if item["sideMode"] == "DUPLEX" and len(pdf) % 2 and copy_index + 1 < item["copies"]:
                            self.api.StartPage(context)
                            self.api.EndPage(context)
                    self.api.EndDoc(context)
                except Exception:
                    try:
                        self.api.SetJob(handle, job_id, 0, None, 3)  # JOB_CONTROL_CANCEL
                    except self.error:
                        pass
                    raise
                return job_id
            finally:
                win32gui.DeleteDC(context)

    def completed(self, destination, job_id, title):
        with self.printer(destination) as handle:
            try:
                job = self.api.GetJob(handle, job_id, 1)
            except self.error as exc:
                if exc.winerror in (2, 87):
                    raise RuntimeError("Windows job history is missing. Check printer output before retrying.") from exc
                raise
        if job["pDocument"] != title or job["Status"] & (0x4 | 0x100):
            raise RuntimeError("Windows cancelled or lost the print job. Check printer output.")
        return bool(job["Status"] & self.api.JOB_STATUS_PRINTED)

    def release(self, destination, job_id):
        try:
            with self.printer(destination) as handle:
                self.api.SetJob(handle, job_id, 0, None, 9)  # JOB_CONTROL_RELEASE
        except self.error:
            pass
