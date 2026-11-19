#!/usr/bin/env python3
"""Add a release to the Kable Killers SideStore/AltStore source file.

Usage:
  kable-killers-altstore-source.py SOURCE_JSON --version 0.6.0 --build 153 \
      --ipa path/to/app.ipa --download-url URL [--notes TEXT] [--date ISO8601]

Creates SOURCE_JSON if it does not exist. The newest version is listed first and only
the most recent MAX_VERSIONS are kept.
"""

import argparse
import datetime
import json
import os

MAX_VERSIONS = 10
REPO_RAW = "https://raw.githubusercontent.com/kablekillers/NuvioMobile/cmp-rewrite"
ICON_URL = f"{REPO_RAW}/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"
BUNDLE_ID = "com.kablekillers.mobile"
DESCRIPTION = (
    "Kable Killers Mobile for iPhone and iPad.\n\n"
    "Bring your own addons and sources, with artwork, ratings, subtitles, downloads, "
    "watch parties and your place saved across devices."
)


def base_source():
    return {
        "name": "Kable Killers",
        "identifier": "com.kablekillers.source",
        "subtitle": "Kable Killers Mobile for iPhone",
        "description": DESCRIPTION,
        "iconURL": ICON_URL,
        "website": "https://github.com/kablekillers/NuvioMobile",
        "tintColor": "#1E88E5",
        "featuredApps": [BUNDLE_ID],
        "apps": [],
        "news": [],
    }


def base_app():
    return {
        "name": "Kable Killers Mobile",
        "bundleIdentifier": BUNDLE_ID,
        "developerName": "Kable Killers",
        "subtitle": "Your media, your sources.",
        "localizedDescription": DESCRIPTION,
        "iconURL": ICON_URL,
        "tintColor": "#1E88E5",
        "category": "entertainment",
        "screenshots": [],
        "versions": [],
        "appPermissions": {
            "entitlements": [],
            "privacy": {
                "NSLocalNetworkUsageDescription": "Kable Killers connects to media servers on your local network.",
            },
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--ipa", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--date", default=None)
    parser.add_argument("--min-os", default="16.1")
    args = parser.parse_args()

    if os.path.exists(args.source):
        with open(args.source, encoding="utf-8") as f:
            source = json.load(f)
    else:
        source = base_source()

    app = next((a for a in source.get("apps", []) if a.get("bundleIdentifier") == BUNDLE_ID), None)
    if app is None:
        app = base_app()
        source.setdefault("apps", []).append(app)

    date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    entry = {
        "version": args.version,
        "buildVersion": str(args.build),
        "date": date,
        "localizedDescription": args.notes or f"Kable Killers Mobile v{args.version}",
        "downloadURL": args.download_url,
        "size": os.path.getsize(args.ipa),
        "minOSVersion": args.min_os,
    }
    versions = [v for v in app.get("versions", []) if v.get("version") != args.version]
    app["versions"] = ([entry] + versions)[:MAX_VERSIONS]

    with open(args.source, "w", encoding="utf-8") as f:
        json.dump(source, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"Added v{args.version} ({args.build}) to {args.source}")


if __name__ == "__main__":
    main()
