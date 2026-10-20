#!/usr/bin/env python3
"""
One-Command GitHub Uploader for Plex Channel Connect.
Uploads ALL project files—including the hidden .github/workflows/build-apk.yml file—
directly to your GitHub repository using the GitHub REST API, completely bypassing
the browser drag-and-drop issue where .github folders are ignored.
"""

import base64
import json
import os
import urllib.request
import urllib.error
from pathlib import Path

def upload_file(repo: str, token: str, branch: str, local_path: Path, remote_path: str):
    url = f"https://api.github.com/repos/{repo}/contents/{remote_path}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "PlexChannelConnect-Uploader"
    }

    sha = None
    try:
        req = urllib.request.Request(f"{url}?ref={branch}", headers=headers, method="GET")
        with urllib.request.urlopen(req) as resp:
            existing = json.loads(resp.read().decode("utf-8"))
            sha = existing.get("sha")
    except urllib.error.HTTPError:
        pass

    content_b64 = base64.b64encode(local_path.read_bytes()).decode("ascii")
    payload = {
        "message": f"Upload {remote_path}",
        "content": content_b64,
        "branch": branch
    }
    if sha:
        payload["sha"] = sha

    data = json.dumps(payload).encode("utf-8")
    put_req = urllib.request.Request(url, data=data, headers=headers, method="PUT")
    with urllib.request.urlopen(put_req) as resp:
        return resp.status

def main():
    print("=== Plex Channel Connect — Automatic GitHub Uploader ===")
    repo = input("Enter your GitHub repository (e.g. username/plex-channel-connect): ").strip()
    token = input("Enter your GitHub Personal Access Token (with 'repo' & 'workflow' scopes): ").strip()
    branch = input("Enter branch name [default: main]: ").strip() or "main"

    root = Path(__file__).resolve().parent
    for dirpath, _, filenames in os.walk(root):
        for fname in filenames:
            full_path = Path(dirpath) / fname
            rel_path = full_path.relative_to(root).as_posix()
            if rel_path.startswith(".git/"):
                continue
            print(f"Uploading {rel_path} ...")
            try:
                status = upload_file(repo, token, branch, full_path, rel_path)
                print(f"  -> OK ({status})")
            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8", errors="ignore")
                print(f"  -> ERROR {e.code}: {err_body}")

    print("\nDone! Open your GitHub repository's 'Actions' tab to download the compiled APK.")

if __name__ == "__main__":
    main()
