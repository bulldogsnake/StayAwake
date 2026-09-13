#!/usr/bin/env python3
"""
Tigil blocklist compiler.

Merges curated Philippine seeds with global gambling feeds, applies the
allowlist, and emits every artifact the project consumes:

  dist/tigil-blocklist.bin   compact hash index shipped inside the Android APK
  dist/rules.json            keyword/regex rules + allowlist for the app
  dist/domains.txt           plain sorted domain list (human review, diffing)
  dist/hosts.txt             /etc/hosts format
  dist/pihole-adlist.txt     Pi-hole / any "one domain per line" adlist
  dist/adguardhome.txt       AdGuard Home filter syntax
  dist/rpz.zone              BIND Response Policy Zone, for ISP/office resolvers
  dist/mikrotik.rsc          MikroTik RouterOS static DNS script
  dist/manifest.json         version, counts, sha256 of each artifact

Usage:
  python3 build_blocklist.py              # fetch upstream, build everything
  python3 build_blocklist.py --offline    # build from cached/seed data only
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEED = ROOT / "seed"
CACHE = ROOT / ".cache"
DIST = ROOT / "dist"

MAGIC = b"TIGIL\x00\x00\x01"
FORMAT_VERSION = 1

# Upstream feeds. Both are community-maintained and permissively licensed;
# see SOURCES.md for attribution and licence terms.
UPSTREAM = {
    "stevenblack-gambling":
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/"
        "alternates/gambling-only/hosts",
    "blocklistproject-gambling":
        "https://raw.githubusercontent.com/blocklistproject/Lists/master/"
        "gambling.txt",
}

# Public suffixes we must not treat as a registrable domain on their own.
# Trimmed list: enough for the TLDs these feeds actually contain.
MULTI_LABEL_SUFFIXES = {
    "com.ph", "net.ph", "org.ph", "gov.ph", "edu.ph", "co.ph",
    "com.au", "co.uk", "org.uk", "gov.uk", "co.jp", "com.br", "com.mx",
    "com.cn", "com.hk", "com.tw", "com.sg", "com.my", "co.id", "co.th",
    "com.vn", "co.kr", "co.nz", "com.tr", "co.za", "com.ar", "com.co",
}

HOST_RE = re.compile(r"^(?:0\.0\.0\.0|127\.0\.0\.1|::1?)\s+(\S+)")
DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                       r"(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def log(msg: str) -> None:
    print(f"[tigil] {msg}", file=sys.stderr)


def fnv1a64(text: str) -> int:
    """64-bit FNV-1a. Must stay byte-identical to Hashing.fnv1a64() in Kotlin."""
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def to_signed64(value: int) -> int:
    return value - (1 << 64) if value >= (1 << 63) else value


def registrable(domain: str) -> str:
    """Reduce a hostname to its registrable domain (example.com.ph)."""
    parts = domain.split(".")
    if len(parts) <= 2:
        return domain
    if ".".join(parts[-2:]) in MULTI_LABEL_SUFFIXES:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def strip_comment(line: str) -> str:
    return line.split("#", 1)[0].strip()


def read_seed_domains(path: Path) -> set[str]:
    out: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        value = strip_comment(raw).lower()
        if value and DOMAIN_RE.match(value):
            out.add(value)
    return out


def parse_feed(text: str) -> set[str]:
    out: set[str] = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = HOST_RE.match(line)
        candidate = (match.group(1) if match else line).lower().rstrip(".")
        if candidate in ("localhost", "localhost.localdomain", "0.0.0.0"):
            continue
        if DOMAIN_RE.match(candidate):
            out.add(candidate)
    return out


def fetch(name: str, url: str, offline: bool) -> set[str]:
    CACHE.mkdir(exist_ok=True)
    cached = CACHE / f"{name}.txt"
    if not offline:
        try:
            log(f"fetching {name} …")
            request = urllib.request.Request(
                url, headers={"User-Agent": "tigil-blocklist-builder/1.0"})
            with urllib.request.urlopen(request, timeout=120) as response:
                cached.write_bytes(response.read())
        except Exception as exc:                      # noqa: BLE001
            log(f"  fetch failed ({exc}); falling back to cache")
    if not cached.exists():
        log(f"  no cache for {name}; skipping")
        return set()
    domains = parse_feed(cached.read_text(encoding="utf-8", errors="replace"))
    log(f"  {name}: {len(domains):,} domains")
    return domains


# --------------------------------------------------------------------------
# rules
# --------------------------------------------------------------------------

def read_rules() -> dict:
    substrings, regexes, tlds = [], [], []
    for raw in (SEED / "keywords.txt").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("sub:"):
            substrings.append(line[4:].strip())
        elif line.startswith("re:"):
            pattern = line[3:].strip()
            re.compile(pattern)          # fail the build on a bad regex
            regexes.append(pattern)
        elif line.startswith("tld:"):
            tlds.append(line[4:].strip().lower())
        else:
            raise SystemExit(f"keywords.txt: unrecognised rule {line!r}")
    return {"substrings": substrings, "regexes": regexes, "tlds": tlds}


def apply_allowlist(domains: set[str], allow: set[str]) -> set[str]:
    """Drop any domain that is, or sits under, an allowlisted domain."""
    kept = set()
    for domain in domains:
        parts = domain.split(".")
        if any(".".join(parts[i:]) in allow for i in range(len(parts))):
            continue
        kept.add(domain)
    return kept


# --------------------------------------------------------------------------
# emitters
# --------------------------------------------------------------------------

def write_binary(path: Path, domains: list[str], built_at: int) -> None:
    hashes = sorted({fnv1a64(d) for d in domains})
    with path.open("wb") as handle:
        handle.write(MAGIC)
        handle.write(struct.pack(">IIII", FORMAT_VERSION, built_at,
                                 len(hashes), 0))
        handle.write(struct.pack(f">{len(hashes)}q",
                                 *(to_signed64(h) for h in hashes)))
    log(f"  tigil-blocklist.bin: {len(hashes):,} hashes, "
        f"{path.stat().st_size / 1_048_576:.2f} MiB")


def write_text_artifacts(domains: list[str], built_at: int) -> None:
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(built_at))
    banner = (f"# Tigil gambling blocklist\n"
              f"# Built: {stamp}\n"
              f"# Domains: {len(domains):,}\n"
              f"# Source: https://github.com/bulldogsnake/StayAwake (tigil/)\n")

    (DIST / "domains.txt").write_text(
        banner + "\n".join(domains) + "\n", encoding="utf-8")

    (DIST / "hosts.txt").write_text(
        banner + "\n".join(f"0.0.0.0 {d}" for d in domains) + "\n",
        encoding="utf-8")

    (DIST / "pihole-adlist.txt").write_text(
        banner + "\n".join(domains) + "\n", encoding="utf-8")

    (DIST / "adguardhome.txt").write_text(
        banner + "\n".join(f"||{d}^" for d in domains) + "\n",
        encoding="utf-8")

    rpz_head = (
        f"$TTL 60\n"
        f"@ IN SOA localhost. root.localhost. ({built_at} 3600 900 604800 60)\n"
        f"  IN NS localhost.\n"
        f"; Tigil gambling RPZ — {len(domains):,} domains, built {stamp}\n")
    rpz_body = "\n".join(f"{d} CNAME .\n*.{d} CNAME ." for d in domains)
    (DIST / "rpz.zone").write_text(rpz_head + rpz_body + "\n", encoding="utf-8")

    mikrotik = ["# Tigil — MikroTik RouterOS static DNS block script",
                f"# {len(domains):,} domains, built {stamp}",
                "/ip dns static"]
    mikrotik += [f'add type=NXDOMAIN name="{d}" match-subdomain=yes comment=tigil'
                 for d in domains]
    (DIST / "mikrotik.rsc").write_text("\n".join(mikrotik) + "\n",
                                       encoding="utf-8")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# --------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true",
                        help="build from cached/seed data only")
    args = parser.parse_args()

    DIST.mkdir(exist_ok=True)
    built_at = int(time.time())

    allow = read_seed_domains(SEED / "allowlist.txt")
    curated = read_seed_domains(SEED / "ph-gambling.txt")
    log(f"seeds: {len(curated):,} curated PH domains, {len(allow):,} allowlisted")

    domains = set(curated)
    provenance = {"curated-ph": len(curated)}
    for name, url in UPSTREAM.items():
        feed = fetch(name, url, args.offline)
        provenance[name] = len(feed)
        domains |= feed

    before = len(domains)
    domains = apply_allowlist(domains, allow)
    log(f"merged {before:,} domains, {before - len(domains):,} removed "
        f"by allowlist -> {len(domains):,}")

    ordered = sorted(domains)
    write_binary(DIST / "tigil-blocklist.bin", ordered, built_at)
    write_text_artifacts(ordered, built_at)

    rules = read_rules()
    (DIST / "rules.json").write_text(json.dumps({
        "version": 1,
        "builtAt": built_at,
        "substrings": rules["substrings"],
        "regexes": rules["regexes"],
        "tlds": rules["tlds"],
        "allowlist": sorted(allow),
    }, indent=2), encoding="utf-8")
    log(f"  rules.json: {len(rules['substrings'])} substrings, "
        f"{len(rules['regexes'])} regexes, {len(rules['tlds'])} TLDs, "
        f"{len(allow)} allowlisted")

    artifacts = sorted(p for p in DIST.iterdir() if p.name != "manifest.json")
    (DIST / "manifest.json").write_text(json.dumps({
        "listVersion": built_at,
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(built_at)),
        "domainCount": len(ordered),
        "provenance": provenance,
        "artifacts": {p.name: {"bytes": p.stat().st_size,
                               "sha256": sha256_of(p)} for p in artifacts},
    }, indent=2), encoding="utf-8")

    log(f"done — {len(ordered):,} domains across {len(artifacts)} artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
