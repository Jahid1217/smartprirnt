import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import requests

from app.main import connection_error
from app.pairing import load_credentials, pair_device


class PairingTest(unittest.TestCase):
    def test_credential_survives_restart_with_mocked_os_encryption(self):
        def protect(data, decrypt=False):
            return bytes(value ^ 255 for value in data)

        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True), patch(
            "app.pairing.protect", side_effect=protect
        ), patch("app.pairing.check_server", side_effect=lambda url: url), patch("app.pairing.requests.post") as post:
            response = post.return_value.__enter__.return_value
            response.status_code = 200
            response.json.return_value = {"agentCode": "station", "agentToken": "secret"}
            state = Path(directory)
            pair_device("https://example.test/api/v1", "0123456789ABCDEFabcd", state)
            self.assertNotIn(b"secret", (state / "credentials.enc").read_bytes())
            self.assertEqual(load_credentials(state)["token"], "secret")
            self.assertFalse(post.call_args.kwargs["allow_redirects"])

    def test_http_is_rejected_before_network_access(self):
        with patch("app.pairing.requests.post") as post:
            with self.assertRaisesRegex(ValueError, "HTTPS"):
                pair_device("http://example.test/api/v1", "0123456789ABCDEFabcd", Path("."))
        post.assert_not_called()

    def test_wrong_https_endpoint_does_not_redeem_code(self):
        with patch("app.pairing.requests.get") as get, patch("app.pairing.requests.post") as post:
            reply = get.return_value.__enter__.return_value
            reply.status_code = 200
            reply.json.return_value = {"html": True}
            with self.assertRaisesRegex(ValueError, "health response"):
                pair_device("https://example.test/api/v1", "0123456789ABCDEFabcd", Path("."))
        post.assert_not_called()


    def test_tls_errors_distinguish_an_http_port_from_an_untrusted_certificate(self):
        self.assertIn("plain HTTP", connection_error(requests.exceptions.SSLError("WRONG_VERSION_NUMBER")))
        self.assertIn("trusted CA", connection_error(requests.exceptions.SSLError("CERTIFICATE_VERIFY_FAILED")))
