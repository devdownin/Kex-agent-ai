#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only OpenClaw migration planner. Nothing is enabled or executed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

MAX_FILE = 256 * 1024
SENSITIVE = re.compile(r"(?i)token|secret|password|api.?key|credential|authorization|private.?key")


def read_regular(path, root):
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Symbolic links or paths outside the selected source are not supported")
    if path.stat().st_size > MAX_FILE:
        raise ValueError("Source file exceeds the 256 KiB migration limit")
    with path.open(encoding="utf-8") as stream:
        content = stream.read(MAX_FILE + 1)
    if len(content.encode("utf-8")) > MAX_FILE:
        raise ValueError("Source file exceeds the 256 KiB migration limit")
    return content


def secret_values(value, sensitive=False):
    if isinstance(value, dict):
        return [s for k, v in value.items() for s in secret_values(v, sensitive or bool(SENSITIVE.search(k))) ]
    if isinstance(value, list):
        return [s for v in value for s in secret_values(v, sensitive)]
    return [value] if sensitive and isinstance(value, str) and value else []


def sanitize_markdown(content, secrets):
    # OpenClaw frontmatter can contain environment credentials and command dispatch metadata.
    content = re.sub(r"\A---\s*\n.*?\n---\s*(?:\n|$)", "", content, flags=re.S)
    for secret in sorted(set(secrets), key=len, reverse=True):
        content = content.replace(secret, "[REDACTED]")
    content = re.sub(r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
                     "[REDACTED PRIVATE KEY]", content, flags=re.S)
    content = re.sub(r"(?i)\b(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{8,}", "[REDACTED]", content)
    content = re.sub(r"(?im)^.*(?:api[_ -]?key|password|secret|authorization|access[_ -]?token)\s*[:=].*$",
                     "[REDACTED CREDENTIAL LINE]", content)
    content = re.sub(r"(?i)(https?://)[^\s/@]+:[^\s/@]+@", r"\1[REDACTED]@", content)
    return content.strip()


def plan(source, workspace=None):
    source = source.expanduser().absolute()
    if source.is_symlink() or not source.is_dir():
        raise ValueError("Source must be an existing directory, not a symbolic link")
    config = {}
    warnings = []
    config_path = source / "openclaw.json"
    if config_path.exists():
        try:
            config = json.loads(read_regular(config_path, source))
            if not isinstance(config, dict):
                raise ValueError("OpenClaw configuration must be an object")
        except json.JSONDecodeError:
            # Fail closed: without parsing config we cannot redact its credentials reliably.
            raise ValueError("openclaw.json uses unsupported JSON5 syntax; export strict JSON to a separate source copy first") from None
    agents = config.get("agents", {})
    defaults = agents.get("defaults", {}) if isinstance(agents, dict) else {}
    configured_workspace = defaults.get("workspace") if isinstance(defaults, dict) else None
    workspace = (workspace or Path(configured_workspace or source / "workspace")).expanduser().absolute()
    roots = [source / "skills", workspace / "skills", workspace / ".agents" / "skills"]
    skills = []
    secrets = secret_values(config)
    seen = set()
    for root in roots:
        if not root.exists():
            continue
        if root.is_symlink() or any(parent.is_symlink() for parent in root.parents):
            raise ValueError("Skill roots must not contain symbolic links")
        for directory in sorted(root.iterdir()):
            if directory.is_symlink():
                warnings.append("Skipped symbolic link in skills root")
                continue
            if not directory.is_dir() or not (directory / "SKILL.md").exists():
                continue
            content = sanitize_markdown(read_regular(directory / "SKILL.md", root), secrets)
            if not content:
                warnings.append("Skipped empty skill after credential filtering")
                continue
            digest = hashlib.sha256(content.encode()).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            title = re.sub(r"[^A-Za-z0-9 ._-]", "_", directory.name)[:100]
            skills.append({"title": "OpenClaw: " + title, "procedure": content,
                           "source": "openclaw-migration", "status": "PENDING_REVIEW", "sha256": digest})
            if len(skills) > 1000:
                raise ValueError("Migration is limited to 1000 skills")
    supported_paths = {"agents.defaults.workspace"}
    unsupported = []

    def fields(value, prefix=""):
        if isinstance(value, dict):
            for key, item in value.items():
                path = prefix + "." + key if prefix else key
                if SENSITIVE.search(key):
                    unsupported.append(path + " (credential excluded)")
                elif isinstance(item, dict) and item:
                    fields(item, path)
                elif path not in supported_paths:
                    unsupported.append(path)
        elif prefix:
            unsupported.append(prefix)
    fields(config)
    warnings.append("Only SKILL.md procedures are imported; scripts, attachments, plugins, sessions, memory and permissions are not copied")
    return {"formatVersion": 1, "source": "OpenClaw", "skills": skills,
            "unsupportedFields": sorted(unsupported), "warnings": warnings}


def write_bundle(bundle, output):
    output = output.expanduser().absolute()
    # Exclusive directory creation prevents overwriting or following an existing destination symlink.
    if any(parent.is_symlink() for parent in output.parents):
        raise ValueError("Destination parents must not contain symbolic links")
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    target = output / "pending-skills.json"
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(bundle, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return target


def import_pending_skills(bundle, api_url):
    parsed = urllib.parse.urlsplit(api_url)
    if (parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment
            or (parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"))):
        raise ValueError("Import requires HTTPS, or HTTP on loopback, without URL credentials")
    token = os.environ.get("KEX_API_TOKEN", "")
    if not token or "\n" in token or "\r" in token:
        raise ValueError("Set KEX_API_TOKEN for authenticated pending-skill import")
    if any(len(skill["procedure"]) > 12000 for skill in bundle["skills"]):
        raise ValueError("A skill exceeds the API limit of 12000 characters; review and split the bundle first")
    # Reject redirects so credentials cannot leave the selected Kex endpoint.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    ids = []
    for skill in bundle["skills"]:
        payload = {"title": skill["title"], "markdown": skill["procedure"],
                   "evidence": "Imported OpenClaw SKILL.md; sha256=" + skill["sha256"]
                               + "; not executed or validated. Human review required."}
        request = urllib.request.Request(api_url.rstrip("/") + "/api/agent/skills",
                data=json.dumps(payload).encode(), method="POST",
                headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with opener.open(request, timeout=30) as response:
                result = json.loads(response.read(1024 * 1024))
            if result.get("status") != "PENDING":
                raise ValueError("Kex returned a skill outside the mandatory PENDING state")
            ids.append(result.get("id"))
        except (urllib.error.URLError, ValueError):
            raise ValueError("Pending import stopped after " + str(len(ids))
                             + " skill(s); inspect the review queue before retrying") from None
    return ids


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("~/.openclaw"))
    parser.add_argument("--workspace", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apply", action="store_true", help="Write a new pending-review bundle; never enables skills")
    parser.add_argument("--dry-run", action="store_true", help="Plan only (default)")
    parser.add_argument("--api-url", help="Also submit pending skills to this Kex base URL; token via KEX_API_TOKEN")
    args = parser.parse_args(argv)
    if args.apply and args.dry_run:
        parser.error("--apply and --dry-run are mutually exclusive")
    if args.apply and args.output is None:
        parser.error("--apply requires --output to a new directory")
    try:
        bundle = plan(args.source, args.workspace)
        report = {"dryRun": not args.apply, "pendingSkills": len(bundle["skills"]),
                  "unsupportedFields": bundle["unsupportedFields"], "warnings": bundle["warnings"]}
        if args.apply:
            write_bundle(bundle, args.output)
            report["written"] = "pending-skills.json"
            if args.api_url:
                report["pendingReviewIds"] = import_pending_skills(bundle, args.api_url)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError) as error:
        # Do not print file content or parser snippets containing source credentials.
        print("Migration refused: " + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
