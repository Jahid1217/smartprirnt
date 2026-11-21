"""Pair a station and persist its credentials with operating-system encryption."""
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit

import requests


def protect(data, decrypt=False):
    if os.name == "nt":
        import win32crypt

        if decrypt:
            return win32crypt.CryptUnprotectData(data, None, None, None, 1)[1]
        return win32crypt.CryptProtectData(data, "SmartPrint", None, None, None, 1)
    command = ["systemd-creds", "decrypt" if decrypt else "encrypt", "--name=smartprint-agent"]
    if not decrypt:
        command.append("--with-key=host")
    return subprocess.run(
        [*command, "-", "-"], input=data, capture_output=True, check=True, timeout=15,
    ).stdout


def credential_path(state_dir):
    return Path(os.getenv("SMARTPRINT_CREDENTIAL_FILE", state_dir / "credentials.enc"))


def load_credentials(state_dir):
    runtime_dir = os.getenv("CREDENTIALS_DIRECTORY")
    if runtime_dir:
        runtime_file = Path(runtime_dir) / "smartprint-agent"
        if runtime_file.is_file():
            return json.loads(runtime_file.read_bytes())
    path = credential_path(state_dir)
    if not path.exists():
        return None
    return json.loads(protect(path.read_bytes(), decrypt=True))


LOG = logging.getLogger("smartprint")


def check_server(base_url):
    url = urlsplit(base_url)
    if (url.scheme != "https" or not url.hostname or url.username or url.password
            or url.query or url.fragment or not url.path.rstrip("/").endswith("/api/v1")):
        raise ValueError("Use the SmartPrint HTTPS API URL ending in /api/v1")
    base_url = base_url.rstrip("/")
    with requests.get(f"{base_url}/health", timeout=(8, 20), allow_redirects=False) as response:
        if response.status_code != 200:
            raise ValueError(f"SmartPrint health check returned HTTP {response.status_code}. Check the API URL and proxy.")
        try:
            health = response.json()
        except ValueError:
            health = None
        if health != {"application": "SmartPrint", "status": "UP"}:
            raise ValueError("The API URL did not return the SmartPrint health response. Check the URL and proxy.")
    LOG.info("Verified HTTPS SmartPrint API at %s", base_url)
    return base_url


def pair_device(base_url, code, state_dir):
    if not re.fullmatch(r"[a-fA-F0-9]{20}", code):
        raise ValueError("Enter the 20-character pairing code shown in SmartPrint")
    base_url = check_server(base_url)
    target = credential_path(state_dir)
    # Check storage access before redeeming a one-time code.
    protect(b"credential store check")
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        path = Path(output.name)
    try:
        _redeem(base_url, code, path)
        path.replace(target)
        LOG.info("Pairing credential saved for the current installation")
    finally:
        path.unlink(missing_ok=True)


def _redeem(base_url, code, path):
    with requests.post(f"{base_url.rstrip('/')}/agent/pairing", json={"code": code},
                       timeout=(8, 20), allow_redirects=False) as response:
        if response.status_code == 401:
            raise ValueError("Pairing code is invalid, expired, or already used. Generate a new code in this shop.")
        if response.status_code != 200:
            raise ValueError(f"Pairing endpoint returned HTTP {response.status_code}. Check the API URL and backend version.")
        try:
            reply = response.json()
            code, token = reply["agentCode"], reply["agentToken"]
            if not all(isinstance(value, str) and value for value in (code, token)):
                raise ValueError
        except (ValueError, TypeError, KeyError):
            raise ValueError("The pairing endpoint did not return SmartPrint device credentials.") from None
        credentials = dict(base_url=base_url, code=code, token=token)
    encrypted = protect(json.dumps(credentials).encode())
    with path.open("wb") as output:
        output.write(encrypted)
        output.flush()
        os.fsync(output.fileno())
