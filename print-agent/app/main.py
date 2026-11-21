"""Run the SmartPrint agent against the local operating-system spooler."""
import argparse
import hashlib
from ipaddress import ip_address
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import signal
import sqlite3
import sys
from subprocess import SubprocessError
import tempfile
import time
from threading import Event, Thread

from .pairing import check_server, load_credentials, pair_device
from urllib.parse import quote, urlsplit, unquote

import requests

if os.name == "nt":
    import msvcrt
    from .windows import WindowsSpooler
else:
    import cups
    import fcntl

LOG = logging.getLogger("smartprint")
MAX_PDF_BYTES = 100 * 1024 * 1024
IPP_JOB_STATUS = {3: "QUEUED", 4: "PAUSED", 5: "PRINTING", 6: "PAUSED",
                  7: "CANCELLED", 8: "FAILED", 9: "COMPLETED"}


class PrintFailure(Exception):
    """A confirmed failure or a submission whose outcome requires review."""


class Agent:
    def __init__(self, base_url, code, token, state_dir, dry_run, allow_lan_http=False,
                 report_spooler=False, spool_timeout=3600):
        url = urlsplit(base_url)
        local_http = url.hostname in {"localhost", "127.0.0.1", "::1"}
        if url.scheme == "http" and allow_lan_http and not local_http:
            try:
                address = ip_address(url.hostname or "")
                local_http = address.is_private and not (address.is_unspecified or address.is_link_local)
            except ValueError:
                pass
            if local_http:
                LOG.warning("Using unencrypted HTTP to a private LAN server")
        if not url.hostname or (url.scheme != "https" and (url.scheme != "http" or not local_http)):
            raise ValueError("Use HTTPS, loopback HTTP, or explicitly enable SMARTPRINT_ALLOW_LAN_HTTP for a private IP")
        if url.username or url.password or url.query or url.fragment:
            raise ValueError("Enter a plain server API URL")
        self.base_url = base_url.rstrip("/")
        self.origin = f"{url.scheme}://{url.netloc}"
        self.stop = Event()
        self.dry_run = dry_run
        self.report_spooler = report_spooler
        self.spool_timeout = spool_timeout
        self.reported = {}
        self.http = requests.Session()
        self.http.headers.update({"X-Agent-Code": code, "X-Agent-Token": token})
        self.directory = state_dir
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = (self.directory / "agent.lock").open("a")
        self.windows = WindowsSpooler() if os.name == "nt" else None
        self.platform = "WINDOWS" if self.windows else "CUPS"
        try:
            if self.windows:
                self.lock.seek(0)
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.lock.close()
            self.http.close()
            raise RuntimeError("Could not lock agent state. Stop the existing agent before running diagnostics.") from None
        for leftover in self.directory.glob("document-*.pdf"):
            leftover.unlink()
        self.journal = sqlite3.connect(self.directory / "spool.sqlite3")
        self.journal.execute("""
            CREATE TABLE IF NOT EXISTS submissions (
                submission_key TEXT PRIMARY KEY, cups_id INTEGER,
                changed_at INTEGER NOT NULL DEFAULT (unixepoch())
            )
        """)
        self.journal.commit()
        self.cups = None
        self.printers = {}
        self.availability = {}
        self.last_discovery = None

    def request(self, method, url, **kwargs):
        LOG.debug("%s %s", method, urlsplit(url).path)
        response = self.http.request(method, url, timeout=(8, 150), allow_redirects=False, **kwargs)
        if 300 <= response.status_code < 400:
            response.close()
            raise RuntimeError("The API redirected the agent. Configure the final API URL directly.")
        try:
            response.raise_for_status()
        except requests.RequestException:
            response.close()
            raise
        return response

    def post(self, suffix, **kwargs):
        return self.request("POST", f"{self.base_url}/agent{suffix}", **kwargs)

    def discover(self):
        if self.dry_run:
            uri = "ipp://localhost/printers/SmartPrint-DryRun"
            self.printers = {uri: "SmartPrint-DryRun"}
            return [dict(code="SmartPrint-DryRun", name="SmartPrint dry run", uri=uri,
                         connectionType="CUPS", status="FREE", colorSupported=True,
                         duplexSupported=True, agentDefault=True)]
        if self.windows:
            reports = self.windows.discover()
            self.printers = self.windows.destinations
            return reports

        # Always use the local scheduler. A reported device URI is never opened.
        self.cups = cups.Connection(host=os.getenv("CUPS_SOCKET", "/run/cups/cups.sock"))
        default = self.cups.getDefault()
        jobs = self.cups.getJobs(which_jobs="not-completed", my_jobs=False,
                                requested_attributes=["job-printer-uri", "job-state"])
        jobs_by_queue = {}
        for job in jobs.values():
            name = unquote(urlsplit(job.get("job-printer-uri", "")).path.rsplit("/", 1)[-1])
            jobs_by_queue.setdefault(name, []).append(job.get("job-state"))
        reports = []
        self.printers = {}
        for name, attributes in self.cups.getPrinters().items():
            uri = f"ipp://localhost/printers/{quote(name, safe='')}"
            self.printers[uri] = name
            printer_type = attributes.get("printer-type", 0)
            code = name if len(name) <= 58 else hashlib.sha256(name.encode()).hexdigest()[:48]
            queue_jobs = jobs_by_queue.get(name, [])
            active = queue_jobs.count(5)
            reasons = attributes.get("printer-state-reasons") or []
            if isinstance(reasons, str):
                reasons = reasons.split(",")
            state = attributes.get("printer-state")
            accepting = attributes.get("printer-is-accepting-jobs")
            if accepting is None and state in (3, 4):
                try:
                    details = self.cups.getPrinterAttributes(
                        name=name, requested_attributes=["printer-is-accepting-jobs"])
                    accepting = details.get("printer-is-accepting-jobs")
                except cups.IPPError:
                    accepting = None
            if any(reason.startswith(("offline", "connecting-to-device", "printer-offline")) for reason in reasons):
                status = "OFFLINE"
            elif any(reason.startswith(("paused", "moving-to-paused")) for reason in reasons):
                status = "PAUSED"
            elif state not in (3, 4) or any(reason.endswith("-error") or reason.startswith(
                    ("media-empty", "media-jam", "door-open", "cover-open", "toner-empty"))
                    for reason in reasons):
                status = "ERROR"
            elif accepting is None:
                status = "ERROR"
            elif not accepting:
                status = "PAUSED"
            else:
                status = "BUSY" if active or state == 4 else "FREE"
            device_uri = attributes.get("device-uri") or ""
            device_group = (hashlib.sha256(device_uri.encode()).hexdigest()[:32]
                            if device_uri and not device_uri.startswith(("file:", "implicitclass:")) else None)
            reports.append(dict(
                code=code, name=(attributes.get("printer-info") or name)[:150], uri=uri,
                connectionType="CUPS",
                portName=urlsplit(device_uri).scheme[:20].upper() or None,
                status=status, model=(attributes.get("printer-make-and-model") or "")[:200] or None,
                pendingJobs=len(queue_jobs) - active, activeJobs=active, deviceGroup=device_group,
                virtualPrinter=device_uri.startswith(("file:", "cups-pdf:")),
                colorSupported=bool(printer_type & cups.CUPS_PRINTER_COLOR),
                duplexSupported=bool(printer_type & cups.CUPS_PRINTER_DUPLEX),
                agentDefault=name == default,
            ))
        return reports

    def submission(self, item):
        key = item["submissionKey"]
        title = f"SmartPrint-{key}"
        row = self.journal.execute(
            "SELECT cups_id FROM submissions WHERE submission_key = ?", (key,)
        ).fetchone()
        destination = self.printers.get(item["printerUri"])
        # The existing spool ID remains valid when a printer is temporarily offline.
        if row:
            if row[0] is not None:
                return row[0]
            if self.windows:
                if not destination:
                    raise PrintFailure("Windows queue missing. Submission outcome unknown. Verify printer output.")
                matches = self.windows.recover(destination, title)
            else:
                jobs = self.cups.getJobs(which_jobs="all", my_jobs=True,
                                         requested_attributes=["job-name"])
                matches = [job_id for job_id, job in jobs.items() if job.get("job-name") == title]
            if len(matches) != 1:
                raise PrintFailure("Print submission outcome unknown. Check printer output before retrying.")
            job_id = matches[0]
        else:
            if not destination:
                raise PrintFailure("The assigned printer is no longer installed")
            if self.availability.get(item["printerUri"]) in {"OFFLINE", "PAUSED", "ERROR"}:
                raise PrintFailure("The assigned printer is unavailable")
            if type(item["copies"]) is not int or not 1 <= item["copies"] <= 100:
                raise PrintFailure("Invalid copy count")
            download_suffix = f"/agent/jobs/{self.job_id}/files/{item['fileId']}.pdf"
            if item["downloadPath"] != "/api/v1" + download_suffix:
                raise PrintFailure("The server returned an invalid document path")
            document = tempfile.NamedTemporaryFile(prefix="document-", suffix=".pdf",
                                                    dir=self.directory, delete=False)
            try:
                with document, self.request("GET", self.base_url + download_suffix, stream=True) as response:
                    size = 0
                    for chunk in response.iter_content(chunk_size=65536):
                        if not chunk:
                            continue
                        if size == 0 and not chunk.startswith(b"%PDF-"):
                            raise PrintFailure("The approved document is not a PDF")
                        size += len(chunk)
                        if size > MAX_PDF_BYTES:
                            raise PrintFailure("The approved PDF exceeds the 100 MiB agent limit")
                        document.write(chunk)
                    if size == 0:
                        raise PrintFailure("The approved PDF is empty")
                if self.stop.is_set():
                    return None
                # Record intent before crossing the process boundary to the spooler.
                with self.journal:
                    self.journal.execute("INSERT INTO submissions(submission_key) VALUES (?)", (key,))
                if self.windows:
                    job_id = self.windows.submit(destination, document.name, title, item)
                else:
                    job_id = self.cups.printFile(destination, document.name, title, {
                        "copies": str(item["copies"]),
                        "sides": ("two-sided-short-edge" if item["orientation"] == "LANDSCAPE"
                                  else "two-sided-long-edge") if item["sideMode"] == "DUPLEX"
                        else "one-sided",
                        "print-color-mode": "color" if item["colorMode"] == "COLOR" else "monochrome",
                        "multiple-document-handling": "separate-documents-collated-copies",
                    })
            except requests.HTTPError as exc:
                if exc.response.status_code == 404:
                    raise PrintFailure("The approved PDF is no longer available") from exc
                raise
            finally:
                Path(document.name).unlink(missing_ok=True)
        with self.journal:
            self.journal.execute(
                "UPDATE submissions SET cups_id = ?, changed_at = unixepoch() WHERE submission_key = ?",
                (job_id, key),
            )
        LOG.info("Local spool job recorded: queue entry=%s file=%s reference=%s", self.job_id, item["fileId"], job_id)
        return job_id

    def job_status(self, item, spool_id):
        title = f"SmartPrint-{item['submissionKey']}"
        if self.windows:
            destination = self.printers.get(item["printerUri"])
            return self.windows.job_status(destination, spool_id, title) if destination else "UNKNOWN"
        try:
            attributes = self.cups.getJobAttributes(
                spool_id, requested_attributes=["job-state", "job-name", "job-state-reasons"]
            )
        except cups.IPPError as exc:
            if exc.args[0] == cups.IPP_NOT_FOUND:
                return "UNKNOWN"
            raise
        if attributes.get("job-name") != title:
            return "UNKNOWN"
        if "job-completed-with-errors" in attributes.get("job-state-reasons", []):
            return "FAILED"
        return IPP_JOB_STATUS.get(attributes.get("job-state"), "UNKNOWN")

    def handle(self, job):
        self.job_id = job["queueEntryId"]
        for item in job["files"]:
            key = item["submissionKey"]
            if self.stop.is_set():
                return
            if not self.dry_run:
                spool_id = self.submission(item)
                if spool_id is None:
                    return
                status = self.job_status(item, spool_id)
                if status in {"PRINTING", "QUEUED", "PAUSED"}:
                    submitted_at, = self.journal.execute(
                        "SELECT changed_at FROM submissions WHERE submission_key = ?", (key,)
                    ).fetchone()
                    if time.time() - submitted_at >= self.spool_timeout:
                        status = "UNKNOWN"
                if self.report_spooler and self.reported.get(key) != status:
                    self.post(f"/jobs/{self.job_id}/files/{item['fileId']}/spooler",
                              headers={"X-Submission-Key": key},
                              json={"reference": str(spool_id), "status": status})
                    self.reported[key] = status
                if status in {"UNKNOWN", "FAILED", "CANCELLED"}:
                    self.reported.pop(key, None)
                    raise PrintFailure(f"Spooler status {status.lower()}. Verify printer output before retrying.")
                if status != "COMPLETED":
                    return
            self.post(f"/jobs/{self.job_id}/files/{item['fileId']}/complete",
                      headers={"X-Submission-Key": item["submissionKey"]})
            if self.windows and not self.dry_run:
                self.windows.release(self.printers[item["printerUri"]], spool_id)
            self.reported.pop(key, None)
            with self.journal:
                self.journal.execute(
                    "DELETE FROM submissions WHERE submission_key = ?", (item["submissionKey"],)
                )
        self.post(f"/jobs/{self.job_id}/complete", **(
            {"headers": self.assignment_headers(job)} if job.get("assignmentKey") else {}))
        LOG.info("Completed queue entry %s%s", self.job_id, " (dry run)" if self.dry_run else "")

    @staticmethod
    def assignment_headers(job):
        return {"X-Assignment-Key": job["assignmentKey"]} if job.get("assignmentKey") else {}

    def spool_errors(self):
        return (self.windows.error if self.windows else cups.IPPError, OSError, RuntimeError)

    def synchronize(self):
        error = None
        try:
            reports = self.discover()
            self.availability = {row["uri"]: row["status"] for row in reports if "uri" in row}
        except self.spool_errors() as exc:
            service = "Windows Print Spooler" if self.windows else "CUPS"
            error = f"Local {service} is unavailable. Check that the print service is running."
            LOG.warning("%s Discovery failed with %s (%s)", error, type(exc).__name__,
                        getattr(exc, "winerror", "no OS error code"))
            reports = []

        try:
            response = self.post("/heartbeat", json={
                "printers": reports, "platform": self.platform, "discoveryError": error,
            })
        except requests.HTTPError as exc:
            try:
                body = exc.response.json()
                fields = [item["field"] for item in body.get("fieldErrors", [])]
            except (ValueError, TypeError, KeyError, AttributeError):
                fields = []
            LOG.warning("Printer heartbeat rejected: HTTP %s; %s local queues; validation fields=%s",
                        exc.response.status_code, len(reports), fields)
            raise
        # A successful HTTP response alone is insufficient if the URL points to an HTML frontend.
        try:
            ack = response.json()
            ack_count = ack["printerCount"]
            agent_id = ack["agentId"]
        except (ValueError, TypeError, KeyError, AttributeError) as exc:
            raise RuntimeError("Heartbeat response was not a SmartPrint agent acknowledgement. Check the API URL.") from exc

        inventory = tuple(sorted((row["name"], row["agentDefault"]) for row in reports))
        discovery = (inventory, agent_id, ack_count, error)
        if discovery != self.last_discovery:
            LOG.info("Printer heartbeat accepted: agent=%s shop=%s local=%s synchronized=%s queues=%r",
                     agent_id, ack.get("shopId"), len(reports), ack_count, inventory)
            if ack_count != len(reports):
                LOG.warning("The server synchronized %s of %s queues", ack_count, len(reports))
            self.last_discovery = discovery
        return reports, ack, error

    def tick(self):
        _, _, error = self.synchronize()
        if error or self.stop.is_set():
            return
        # Keep unfinished journal records across network outages and process restarts.
        with self.post("/jobs/assignments") as response:
            assignments = response.json()
        if not isinstance(assignments, list):
            raise RuntimeError("Unexpected assignment response. Check the API URL and backend version.")
        LOG.debug("Job poll returned %s assignments", len(assignments))
        for assignment in assignments:
            try:
                self.handle(assignment)
            except PrintFailure as exc:
                self.post(f"/jobs/{self.job_id}/failed", json={"reason": str(exc)[:500]},
                          headers=self.assignment_headers(assignment))
                LOG.warning("Queue entry %s failed: %s", self.job_id, exc)
            except self.spool_errors() as exc:
                LOG.warning("Queue entry %s will reconnect after %s",
                            assignment["queueEntryId"], type(exc).__name__)


def start_local_identity(origin, code):
    """Expose only the public agent identifier on loopback for the shop UI."""
    class IdentityHandler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, include_body):
            request_origin = self.headers.get("Origin")
            if (self.path != "/identity" or self.headers.get("Host") != "127.0.0.1:18765"
                    or request_origin != origin):
                self.send_error(404)
                return
            self.send_response(200 if include_body else 204)
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Access-Control-Allow-Private-Network", "true")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            if include_body:
                self.wfile.write(json.dumps({"agentCode": code}).encode())

        def do_GET(self):
            self.reply(True)

        def do_OPTIONS(self):
            self.reply(False)

    listener = ThreadingHTTPServer(("127.0.0.1", 18765), IdentityHandler)
    Thread(target=listener.serve_forever, daemon=True).start()
    return listener


def authentication_error(response):
    try:
        reason = response.json().get("message")
    except (ValueError, AttributeError):
        reason = None
    if reason == "Print agent revoked":
        return "This device was revoked. Generate a fresh code, pair this installation, and restart the agent."
    return "Device credentials were rejected. Check the stored API URL and pair again if needed."


def connection_error(exc):
    if isinstance(exc, requests.exceptions.SSLError):
        details = str(exc).upper()
        if "WRONG_VERSION_NUMBER" in details:
            return "TLS handshake failed. This port may serve plain HTTP. Use the HTTPS reverse proxy address."
        if "CERTIFICATE_VERIFY_FAILED" in details:
            return "TLS certificate verification failed. Check the hostname and trusted CA bundle (REQUESTS_CA_BUNDLE)."
        return "TLS handshake failed. Check the HTTPS hostname, port and proxy configuration."
    if isinstance(exc, (requests.ConnectionError, requests.Timeout)):
        return f"API connection failed ({type(exc).__name__}). Check host, port and network."
    if type(exc) is RuntimeError and str(exc).startswith(("The API redirected", "Heartbeat response", "Unexpected assignment")):
        return str(exc)
    return type(exc).__name__


def main():
    from dotenv import load_dotenv

    parser = argparse.ArgumentParser(description="SmartPrint local printing agent")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check-server", metavar="HTTPS_API_URL", help="Verify HTTPS and the API path without pairing")
    modes.add_argument("--pair", metavar="HTTPS_API_URL", help="Pair with a code entered interactively")
    modes.add_argument("--discover", action="store_true", help="List local queues without contacting SmartPrint")
    modes.add_argument("--check-discovery", action="store_true", help="Send one heartbeat without requesting jobs")
    parser.add_argument("--debug", action="store_true", help="Log per-queue metadata lookup failures")
    args = parser.parse_args()

    os.umask(0o077)
    state_home = "LOCALAPPDATA" if os.name == "nt" else "XDG_STATE_HOME"
    home = Path(os.environ.get(state_home, Path.home() / ".local" / "state"))
    config_path = home / "smartprint-agent" / "agent.conf" if getattr(sys, "frozen", False) else Path(
        __file__).resolve().parents[1] / ".env"
    load_dotenv(os.getenv("SMARTPRINT_AGENT_CONFIG", config_path))
    state_dir = Path(os.getenv("AGENT_STATE_DIR", home / "smartprint-agent"))
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    log_file = state_dir / "agent.log"
    handlers = [RotatingFileHandler(log_file, maxBytes=1_000_000, backupCount=2, encoding="utf-8")]
    if sys.stderr is not None:
        handlers.insert(0, logging.StreamHandler())
    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s",
                        handlers=handlers)
    if args.pair or args.check_server:
        from getpass import getpass

        try:
            if args.check_server:
                check_server(args.check_server)
                print("SmartPrint HTTPS API verified.")
            else:
                pair_device(args.pair, getpass("Pairing code: ").strip(), state_dir)
                print("Credentials saved. Start or restart the background agent and wait for an accepted heartbeat.")
        except requests.exceptions.SSLError as exc:
            parser.error(connection_error(exc))
        except requests.RequestException as exc:
            parser.error(f"Could not reach the HTTPS API ({type(exc).__name__}). Check DNS, port and firewall.")
        except ValueError as exc:
            parser.error(str(exc))
        except (OSError, SubprocessError) as exc:
            parser.error(f"Could not write OS-protected credentials ({type(exc).__name__}). Check the installation and account.")
        return
    try:
        saved = None if args.discover else load_credentials(state_dir)
    except Exception as exc:
        parser.error(f"Could not open OS credential storage ({type(exc).__name__}). Pair again under the service account")
    code = saved["code"] if saved else os.getenv("AGENT_CODE")
    token = saved["token"] if saved else os.getenv("AGENT_TOKEN")
    if not args.discover and (not code or not token or token == "replace_with_agent_secret"):
        LOG.error("No SmartPrint device credentials for this installation. Run --pair HTTPS_API_URL and restart.")
        raise SystemExit(78)
    poll = max(2, int(os.getenv("POLL_SECONDS", "5")))
    dry_run = not (saved or args.check_discovery or args.discover) and os.getenv("DRY_RUN", "false").lower() == "true"
    base_url = saved["base_url"] if saved else os.getenv("SMARTPRINT_API_BASE", "http://localhost:8080/api/v1")
    try:
        agent = Agent(base_url, code or "", token or "", state_dir, dry_run,
                      allow_lan_http=not saved and os.getenv("SMARTPRINT_ALLOW_LAN_HTTP", "false").lower() == "true",
                      report_spooler=True, spool_timeout=max(60, int(os.getenv("SPOOL_TIMEOUT_SECONDS", "3600"))))
    except (ValueError, RuntimeError) as exc:
        LOG.error("Agent could not start: %s", exc)
        raise SystemExit(78) from None
    if args.discover:
        print(json.dumps(agent.discover(), indent=2), flush=True)
        return
    LOG.info("SmartPrint agent started on %s; API base=%s; credential source=%s; log=%s", agent.platform, agent.base_url,
             "OS storage" if saved else "legacy environment", log_file)
    if args.check_discovery:
        try:
            reports, ack, error = agent.synchronize()
        except requests.HTTPError as exc:
            LOG.error("Diagnostic heartbeat rejected (HTTP %s): %s", exc.response.status_code,
                      authentication_error(exc.response) if exc.response.status_code in (401, 403)
                      else "check the API route and server logs")
            raise SystemExit(1) from None
        except (requests.RequestException, RuntimeError) as exc:
            LOG.error("Diagnostic heartbeat failed: %s", connection_error(exc))
            raise SystemExit(1) from None
        print(json.dumps({"localQueues": reports, "heartbeat": ack, "discoveryError": error}, indent=2))
        if error or len(reports) != ack["printerCount"]:
            raise SystemExit(1)
        return
    try:
        start_local_identity(os.getenv("SMARTPRINT_BROWSER_ORIGIN", agent.origin).rstrip("/"), code)
    except OSError:
        LOG.warning("Local browser identification unavailable on port 18765; cloud discovery is still active")
    if agent.dry_run:
        LOG.info("Dry run enabled: approved jobs will be marked completed without printing")
    stop = agent.stop
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    delay = poll
    while not stop.is_set():
        try:
            agent.tick()
            delay = poll
        except requests.HTTPError as exc:
            if exc.response.status_code in (401, 403):
                LOG.error("%s", authentication_error(exc.response))
                raise SystemExit(78) from None
            LOG.warning("Server request failed with status %s", exc.response.status_code)
            delay = min(30, delay * 2)
        except (requests.RequestException, *agent.spool_errors()) as exc:
            LOG.warning("Agent will reconnect: %s", connection_error(exc))
            delay = min(30, delay * 2)
        stop.wait(delay)
    agent.http.close()
    agent.journal.close()
    agent.lock.close()


if __name__ == "__main__":
    main()
