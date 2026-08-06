#!/usr/bin/env python3
"""Seeds the isolated "quickdrop-e2e" instance (port 8081, .claude/launch.json,
application-e2e.properties) with a small set of real data via the public HTTP API --
never by reaching into internals -- so Layer 2/3 agents (see docs/AGENTIC_TEST_PLAN.md)
have something to probe/click through.

Step 0 is the important one: it re-points fileStoragePath to e2e-data/files via the
real admin settings form, exactly as an admin would through the UI. Without it, uploads
would land in the DB-seeded default ("files", relative to CWD) -- i.e. the real repo
files/ directory -- which is exactly what this whole e2e setup exists to avoid.

Usage:
    python scripts/seed_e2e.py [--base-url http://localhost:8081] [--storage-path e2e-data/files]
                               [--admin-password admin-e2e-pw]
    (start the target server first: it does not launch quickdrop-e2e itself)

Each parallel test track (see docs/AGENTIC_TEST_PLAN.md §6) should run its own instance
on its own port with its own storage path -- pass --base-url/--storage-path matching that
track's launch.json entry and application-e2eN.properties so tracks never share state.

Requires: requests (pip install requests if missing).
"""
import argparse
import re
import sys
import time
from html.parser import HTMLParser

import requests

BASE_URL = "http://localhost:8081"
ADMIN_PASSWORD = "admin-e2e-pw"
E2E_STORAGE_PATH = "e2e-data/files"


class FormFieldScraper(HTMLParser):
    """Extracts current name/value pairs from every <input>/<textarea>/<select> in a
    single <form>, so we can resubmit it unchanged except for the one field we want to
    override -- mirrors exactly what a browser does on submit, avoiding the risk of a
    hand-built payload silently zeroing out unlisted boolean settings.
    """

    def __init__(self):
        super().__init__()
        self.fields = {}
        self._current_select = None
        self._current_textarea = None
        self._textarea_buf = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "input":
            name = attrs.get("name")
            if not name:
                return
            input_type = (attrs.get("type") or "text").lower()
            if input_type in ("checkbox", "radio"):
                # Only emit a value when checked. An unchecked box must be OMITTED
                # entirely (not sent as ""), so Spring's binder falls through to the
                # Thymeleaf-generated hidden "_name" marker field and binds false --
                # sending "" instead makes it try to parse "" as a boolean and 400s.
                if "checked" in attrs:
                    self.fields[name] = attrs.get("value", "on")
            else:
                self.fields[name] = attrs.get("value", "")
        elif tag == "textarea":
            name = attrs.get("name")
            if name:
                self._current_textarea = name
                self._textarea_buf = []
        elif tag == "select":
            self._current_select = attrs.get("name")
        elif tag == "option" and self._current_select:
            if "selected" in attrs:
                self.fields[self._current_select] = attrs.get("value", "")

    def handle_data(self, data):
        if self._current_textarea:
            self._textarea_buf.append(data)

    def handle_endtag(self, tag):
        if tag == "textarea" and self._current_textarea:
            self.fields[self._current_textarea] = "".join(self._textarea_buf)
            self._current_textarea = None
        elif tag == "select":
            self._current_select = None


def wait_for_server(session: requests.Session, timeout_s=60):
    print(f"Waiting for {BASE_URL} ...")
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            r = session.get(BASE_URL, timeout=3)
            if r.status_code < 500:
                print("Server is up.")
                return
        except requests.exceptions.RequestException:
            pass
        time.sleep(2)
    raise SystemExit(f"Server did not become ready within {timeout_s}s")


def csrf_headers(session: requests.Session) -> dict:
    token = session.cookies.get("XSRF-TOKEN")
    if not token:
        session.get(BASE_URL + "/")
        token = session.cookies.get("XSRF-TOKEN")
    if not token:
        raise RuntimeError("Did not receive an XSRF-TOKEN cookie")
    return {"X-XSRF-TOKEN": token}


def setup_admin(session: requests.Session):
    print("Setting admin password...")
    r = session.post(
        f"{BASE_URL}/admin/setup",
        data={"adminPassword": ADMIN_PASSWORD},
        headers=csrf_headers(session),
        allow_redirects=True,
    )
    r.raise_for_status()


def login_admin(session: requests.Session):
    print("Logging in as admin...")
    r = session.post(
        f"{BASE_URL}/admin/password",
        data={"password": ADMIN_PASSWORD},
        headers=csrf_headers(session),
        allow_redirects=True,
    )
    r.raise_for_status()
    if "/admin/dashboard" not in r.url and "dashboard" not in r.url:
        raise RuntimeError(f"Admin login did not land on dashboard (ended at {r.url})")


def redirect_storage_path(session: requests.Session):
    print(f"Redirecting file storage to {E2E_STORAGE_PATH} ...")
    settings_page = session.get(f"{BASE_URL}/admin/settings")
    settings_page.raise_for_status()

    scraper = FormFieldScraper()
    scraper.feed(settings_page.text)
    fields = scraper.fields
    if "fileStoragePath" not in fields:
        raise RuntimeError("Could not find fileStoragePath field on /admin/settings -- "
                            "template may have changed, update FormFieldScraper usage")

    fields["fileStoragePath"] = E2E_STORAGE_PATH
    # appPassword is a write-only field on the form (blank = "don't change"); never
    # resubmit a stale/blank value that could accidentally disable the app password.
    fields.pop("appPassword", None)
    fields.pop("appLogo", None)

    # The controller's appLogo param is typed MultipartFile, which Spring can only
    # resolve against an actual multipart/form-data request -- even with required=false,
    # a plain urlencoded POST 400s. Send an empty file part, exactly like a browser does
    # for an untouched <input type="file">.
    r = session.post(
        f"{BASE_URL}/admin/save",
        data=fields,
        files={"appLogo": ("", b"", "application/octet-stream")},
        headers=csrf_headers(session),
        allow_redirects=True,
    )
    r.raise_for_status()

    confirm = session.get(f"{BASE_URL}/admin/settings")
    if f'value="{E2E_STORAGE_PATH}"' not in confirm.text:
        raise RuntimeError("fileStoragePath did not stick after /admin/save -- "
                            "refusing to continue seeding (would write into real files/)")
    print("Storage path isolated.")


def upload_single_chunk_file(session: requests.Session, filename: str, content: bytes,
                             password: str = None, description: str = None) -> str:
    files = {"file": (filename, content, "application/octet-stream")}
    data = {
        "fileName": filename,
        "chunkNumber": "0",
        "totalChunks": "1",
        "fileSize": str(len(content)),
    }
    if password:
        data["password"] = password
    if description:
        data["description"] = description

    r = session.post(f"{BASE_URL}/api/file/upload-chunk", files=files, data=data,
                      headers=csrf_headers(session))
    r.raise_for_status()
    upload_id = r.json()["uploadId"]

    deadline = time.time() + 30
    while time.time() < deadline:
        status = session.get(f"{BASE_URL}/api/file/upload-status/{upload_id}").json()
        if status["status"] == "complete":
            return status["uuid"]
        if status["status"] == "error":
            raise RuntimeError(f"Upload failed: {status.get('error')}")
        time.sleep(0.5)
    raise RuntimeError(f"Upload {upload_id} did not complete within 30s")


def create_paste(session: requests.Session, title: str, content: str, password: str = None) -> str:
    data = {"title": title, "content": content, "syntax": "markdown"}
    if password:
        data["password"] = password
    r = session.post(f"{BASE_URL}/file/paste", data=data, headers=csrf_headers(session),
                      allow_redirects=False)
    location = r.headers.get("Location", "")
    match = re.search(r"/file/([0-9a-fA-F-]{36})", location)
    if not match:
        raise RuntimeError(f"Paste creation did not redirect to a file view (got {r.status_code} -> {location!r})")
    return match.group(1)


def main():
    session = requests.Session()
    wait_for_server(session)
    setup_admin(session)
    login_admin(session)
    redirect_storage_path(session)

    plain_uuid = upload_single_chunk_file(
        session, "plain-1kb.txt",
        open("src/test/resources/fixtures/plain-1kb.txt", "rb").read(),
        description="Seed: plain file, no password",
    )
    print(f"  uploaded plain file -> /file/{plain_uuid}")

    protected_uuid = upload_single_chunk_file(
        session, "protected.txt", b"seed content behind a password\n",
        password="seed-password", description="Seed: password-protected file",
    )
    print(f"  uploaded password-protected file -> /file/{protected_uuid}")

    paste_uuid = create_paste(session, "Seed Paste", "# Seed paste\n\nSome **markdown** content.")
    print(f"  created paste -> /file/{paste_uuid}")

    print("\nSeed complete. Isolated instance at", BASE_URL)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--storage-path", default=E2E_STORAGE_PATH)
    parser.add_argument("--admin-password", default=ADMIN_PASSWORD)
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    BASE_URL = args.base_url
    E2E_STORAGE_PATH = args.storage_path
    ADMIN_PASSWORD = args.admin_password
    try:
        main()
    except Exception as e:
        print(f"\nSeeding failed: {e}", file=sys.stderr)
        sys.exit(1)
