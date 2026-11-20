import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import requests

from app.main import Agent, PrintFailure


@unittest.skipIf(os.name == "nt", "CUPS tests require the Linux adapter")
class AgentTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.agent = Agent("http://localhost:8080/api/v1", "test", "token",
                           Path(folder.name), False)
        self.addCleanup(self.agent.lock.close)
        self.addCleanup(self.agent.journal.close)
        self.agent.cups = MagicMock()
        self.agent.printers = {"ipp://localhost/printers/Desk": "Desk"}
        self.file = {
            "fileId": 23, "submissionKey": "file-attempt-1",
            "printerUri": "ipp://localhost/printers/Desk",
            "downloadPath": "/api/v1/agent/jobs/12/files/23.pdf",
            "copies": 2, "orientation": "LANDSCAPE", "sideMode": "DUPLEX", "colorMode": "BW",
        }
        self.job = {"queueEntryId": 12, "files": [self.file]}
        self.agent.cups.printFile.return_value = 1001
        self.agent.cups.getJobAttributes.return_value = {
            "job-name": "SmartPrint-file-attempt-1", "job-state": 5,
        }

    def test_lan_http_requires_explicit_opt_in_and_rejects_public_ips(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            with self.assertRaises(ValueError):
                Agent("http://192.168.1.20:8081/api/v1", "agent", "token", path, False)
            with self.assertRaises(ValueError):
                Agent("http://8.8.8.8/api/v1", "agent", "token", path, False, allow_lan_http=True)
            agent = Agent("http://192.168.1.20:8081/api/v1", "agent", "token", path, False,
                          allow_lan_http=True)
            try:
                self.assertEqual(agent.origin, "http://192.168.1.20:8081")
            finally:
                agent.journal.close()
                agent.lock.close()
                agent.http.close()

    def acknowledgement(self, count):
        response = MagicMock()
        response.json.return_value = {"agentId": 1, "shopId": 2, "printerCount": count}
        return response

    def test_heartbeat_sends_all_discovered_queues_and_checks_acknowledgement(self):
        reports = [
            {"name": "Office_1", "agentDefault": False},
            {"name": "Office 2", "agentDefault": True},
        ]
        with patch.object(self.agent, "discover", return_value=reports), patch.object(
                self.agent, "post", return_value=self.acknowledgement(2)) as post:
            local, ack, error = self.agent.synchronize()
        self.assertEqual(local, reports)
        self.assertEqual(ack["printerCount"], 2)
        self.assertIsNone(error)
        self.assertEqual(post.call_args.kwargs["json"]["printers"], reports)

    def test_discovery_reports_cups_default_and_removes_missing_destinations(self):
        connection = self.agent.cups
        connection.getDefault.return_value = "Preferred"
        connection.getPrinterAttributes.return_value = {"printer-is-accepting-jobs": True}
        connection.getPrinters.return_value = {
            "First": {"printer-state": 3},
            "Preferred": {"printer-state": 4},
        }
        with patch("app.main.cups.Connection", return_value=connection):
            first = self.agent.discover()
            self.assertEqual([report["code"] for report in first if report["agentDefault"]],
                             ["Preferred"])
            connection.getPrinters.return_value = {"Preferred": {"printer-state": 3}}
            second = self.agent.discover()
            self.assertEqual([report["code"] for report in second], ["Preferred"])
            self.assertNotIn("ipp://localhost/printers/First", self.agent.printers)

    def test_cups_error_still_reports_agent_heartbeat(self):
        with patch.object(self.agent, "discover", side_effect=RuntimeError), patch.object(
                self.agent, "post", return_value=self.acknowledgement(0)) as post:
            self.agent.tick()
            post.assert_called_once()
            self.assertEqual(post.call_args.args, ("/heartbeat",))
            self.assertEqual(post.call_args.kwargs["json"]["printers"], [])
            self.assertIn("CUPS", post.call_args.kwargs["json"]["discoveryError"])

    def test_a_running_assignment_does_not_block_other_printers(self):
        first = {"queueEntryId": 12, "files": [self.file]}
        second_file = {**self.file, "fileId": 24, "submissionKey": "second-attempt",
                       "downloadPath": "/api/v1/agent/jobs/13/files/24.pdf",
                       "printerUri": "ipp://localhost/printers/Other"}
        second = {"queueEntryId": 13, "files": [second_file]}
        self.agent.printers["ipp://localhost/printers/Other"] = "Other"
        self.agent.cups.printFile.side_effect = [1001, 1002]
        self.agent.cups.getJobAttributes.side_effect = [
            {"job-name": "SmartPrint-file-attempt-1", "job-state": 5},
            {"job-name": "SmartPrint-second-attempt", "job-state": 5},
        ]
        response = MagicMock()
        response.__enter__.return_value = response
        response.iter_content.side_effect = lambda **_: iter([b"%PDF-test"])
        batch = MagicMock()
        batch.json.return_value = [first, second]
        with patch.object(self.agent, "discover", return_value=[]), patch.object(
                self.agent, "post", side_effect=[self.acknowledgement(0), batch]), patch.object(
                self.agent, "request", return_value=response):
            self.agent.tick()
        self.assertEqual([call.args[0] for call in self.agent.cups.printFile.call_args_list],
                         ["Desk", "Other"])

    def test_pending_spool_and_lost_http_response_do_not_resubmit(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.iter_content.return_value = iter([b"%PDF-test"])
        with patch.object(self.agent, "request", return_value=response), patch.object(self.agent, "post") as post:
            self.agent.handle(self.job)
            self.agent.cups.printFile.assert_called_once()
            args = self.agent.cups.printFile.call_args.args
            self.assertEqual(args[0], "Desk")
            self.assertEqual(args[3]["copies"], "2")
            self.assertEqual(args[3]["sides"], "two-sided-short-edge")
            self.assertEqual(args[3]["print-color-mode"], "monochrome")
            post.assert_not_called()

            self.agent.cups.getJobAttributes.return_value["job-state"] = 9
            post.side_effect = requests.ConnectionError
            with self.assertRaises(requests.ConnectionError):
                self.agent.handle(self.job)

            post.side_effect = None
            self.agent.handle(self.job)
            self.agent.cups.printFile.assert_called_once()
            post.assert_any_call("/jobs/12/files/23/complete", headers={"X-Submission-Key": "file-attempt-1"})
            post.assert_called_with("/jobs/12/complete")

    def test_ambiguous_submission_stops_for_review(self):
        with self.agent.journal:
            self.agent.journal.execute("INSERT INTO submissions(submission_key) VALUES (?)",
                                       ("file-attempt-1",))
        self.agent.cups.getJobs.return_value = {}
        with self.assertRaisesRegex(PrintFailure, "outcome unknown"):
            self.agent.handle(self.job)
        self.agent.cups.printFile.assert_not_called()


if __name__ == "__main__":
    unittest.main()
