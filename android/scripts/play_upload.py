#!/usr/bin/env python3
"""
Uploads a signed App Bundle to a Google Play track with the Android Publisher API, using Google's own
client library (no third-party GitHub action holds the service account key).

    PLAY_SERVICE_ACCOUNT_JSON='{...}' python3 play_upload.py app-release.aab \
        --package app.heartpact --track internal --status completed --notes "What changed"

The service account needs "Release apps to testing tracks" (and production, to use that track) in Play
Console > Users and permissions. The app's first bundle must be uploaded by hand in Play Console; a
Play app that has never been published only accepts --status draft.
"""
import argparse
import json
import os
import sys

TRACKS = ("internal", "alpha", "beta", "production")
STATUSES = ("completed", "draft", "inProgress", "halted")


def parse(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", help="path to the signed .aab")
    parser.add_argument("--package", default="app.heartpact")
    parser.add_argument("--track", default="internal", choices=TRACKS)
    parser.add_argument("--status", default="completed", choices=STATUSES)
    parser.add_argument("--notes", default="", help="release notes (en-US), at most 500 characters")
    parser.add_argument("--user-fraction", type=float, default=None, help="for --status inProgress: share of users, 0-1")
    parser.add_argument("--dry-run", action="store_true", help="check the inputs and stop before calling Google")
    args = parser.parse_args(argv)
    if not os.path.isfile(args.bundle) or not args.bundle.endswith(".aab"):
        parser.error(f"not an .aab file: {args.bundle}")
    if args.status == "inProgress" and not (args.user_fraction and 0 < args.user_fraction < 1):
        parser.error("--status inProgress needs --user-fraction between 0 and 1")
    args.notes = args.notes.strip()[:500]
    return args


def release(args, version_code):
    body = {"versionCodes": [str(version_code)], "status": args.status}
    if args.notes:
        body["releaseNotes"] = [{"language": "en-US", "text": args.notes}]
    if args.status == "inProgress":
        body["userFraction"] = args.user_fraction
    return body


def main(argv):
    args = parse(argv)
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    try:
        info = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit("PLAY_SERVICE_ACCOUNT_JSON is missing or isn't JSON.")
    if info.get("type") != "service_account":
        sys.exit("PLAY_SERVICE_ACCOUNT_JSON must be a service account key.")
    if args.dry_run:
        print(f"Would upload {args.bundle} to {args.package} on the {args.track} track as {args.status}.")
        return

    from google.auth.exceptions import RefreshError
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload

    credentials = service_account.Credentials.from_service_account_info(info, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    edits = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False).edits()
    try:
        edit = edits.insert(packageName=args.package, body={}).execute()["id"]
    except RefreshError as error:
        sys.exit(f"Google refused the service account key: {error.args[0] if error.args else error}")
    except HttpError as error:
        sys.exit(f"Google Play refused to start an edit for {args.package}: {error.status_code} {error.reason}")
    try:
        media = MediaFileUpload(args.bundle, mimetype="application/octet-stream", resumable=True)
        uploaded = edits.bundles().upload(packageName=args.package, editId=edit, media_body=media).execute()
        version_code = uploaded["versionCode"]
        edits.tracks().update(packageName=args.package, editId=edit, track=args.track,
                              body={"track": args.track, "releases": [release(args, version_code)]}).execute()
        edits.commit(packageName=args.package, editId=edit).execute()
    except HttpError as error:
        # An edit left open blocks nothing, but say plainly what Google refused.
        sys.exit(f"Google Play refused the upload: {error.status_code} {error.reason}")
    print(f"Version {version_code} is on the {args.track} track ({args.status}).")


if __name__ == "__main__":
    main(sys.argv[1:])
