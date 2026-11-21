import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import MagicMock

from app.windows import WindowsSpooler, queue_report


def queue(name, port, **values):
    return dict(pPrinterName=name, pPortName=port, **values)


class WindowsQueueTest(unittest.TestCase):
    def test_only_exact_system_default_is_marked_among_two_ready_duplicates(self):
        first = queue_report(queue("Office PCL", "USB001"), "office ps", (True, True))
        second = queue_report(queue("Office PS", "usb001"), "office ps", (True, True))
        self.assertEqual(first["status"], "FREE")
        self.assertFalse(first["agentDefault"])
        self.assertTrue(second["agentDefault"])
        self.assertEqual(first["deviceGroup"], second["deviceGroup"])
        self.assertNotEqual(first["code"], second["code"])

    def test_missing_default_never_promotes_a_ready_queue(self):
        self.assertFalse(queue_report(queue("Desk", "USB001"), None, (False, True))["agentDefault"])

    def test_virtual_and_offline_defaults_are_reported_separately(self):
        virtual = queue_report(queue("PDF", "PORTPROMPT:"), "PDF", (True, False))
        offline = queue_report(queue("Desk", "IP_Office", Attributes=0x400), "Desk", (True, True))
        self.assertTrue(virtual["agentDefault"])
        self.assertTrue(virtual["virtualPrinter"])
        self.assertEqual(offline["status"], "OFFLINE")

    def test_os_operational_flags_and_spool_jobs_control_availability(self):
        active = queue_report(queue("Desk", "USB001"), None, (True, True),
                              [{"Status": 0x10}, {"Status": 0x08}, {"Status": 0x80}])
        self.assertEqual(active["status"], "BUSY")
        self.assertEqual(active["activeJobs"], 1)
        self.assertEqual(active["pendingJobs"], 1)
        for flag, status in [(0x1, "PAUSED"), (0x10, "ERROR"), (0x400, "BUSY"), (0x80, "OFFLINE")]:
            self.assertEqual(queue_report(queue("Desk", "USB001", Status=flag), None, (True, True))["status"],
                             status)

    def test_enum_level_four_keeps_every_queue_when_one_driver_cannot_open(self):
        class SpoolerError(Exception):
            winerror = 5

        spooler = WindowsSpooler.__new__(WindowsSpooler)
        spooler.api = MagicMock()
        spooler.api.PRINTER_ENUM_LOCAL = 2
        spooler.api.PRINTER_ENUM_CONNECTIONS = 4
        spooler.api.EnumPrinters.return_value = [
            {"pPrinterName": "Office_Printer_1", "Attributes": 0},
            {"pPrinterName": "Office Printer #2", "Attributes": 0},
        ]
        spooler.api.GetDefaultPrinter.return_value = "Office_Printer_1"
        spooler.api.OpenPrinter.side_effect = lambda name: (
            name if name == "Office_Printer_1" else self.raise_spooler_error(SpoolerError))
        spooler.api.GetPrinter.return_value = {
            "pPrinterName": "Office_Printer_1", "pDriverName": "Office Model", "pPortName": "USB001",
        }
        spooler.api.EnumJobs.return_value = []
        spooler.api.DeviceCapabilities.return_value = 1
        spooler.error = SpoolerError
        spooler.lookups = {}
        with ThreadPoolExecutor(max_workers=2) as workers:
            spooler.details = workers
            first, second = spooler.discover()
        spooler.api.EnumPrinters.assert_called_once_with(6, None, 4)
        self.assertEqual(first["name"], "Office_Printer_1")
        self.assertEqual(first["model"], "Office Model")
        self.assertEqual(first["portName"], "USB001")
        self.assertTrue(first["agentDefault"])
        self.assertEqual(second["name"], "Office Printer #2")
        self.assertEqual(second["status"], "ERROR")
        self.assertEqual(spooler.destinations[second["uri"]], "Office Printer #2")

    def test_spooler_status_requires_matching_job_identity(self):
        spooler = WindowsSpooler.__new__(WindowsSpooler)
        spooler.api = MagicMock()
        spooler.api.JOB_STATUS_PRINTED = 0x80
        spooler.error = OSError
        for flags, status in [(0x08, "QUEUED"), (0x10, "PRINTING"), (1, "PAUSED"),
                              (0x80, "COMPLETED"), (0x100, "CANCELLED")]:
            spooler.api.GetJob.return_value = {"pDocument": "SmartPrint-test", "Status": flags}
            self.assertEqual(spooler.job_status("Desk", 1, "SmartPrint-test"), status)
        self.assertEqual(spooler.job_status("Desk", 1, "SmartPrint-other"), "UNKNOWN")

    @staticmethod
    def raise_spooler_error(error):
        raise error()
