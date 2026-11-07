#!/usr/bin/env python3
"""Package the already built Hendrix CLAP/VST3 for the current target."""
from pathlib import Path
import argparse
import json
import os
import plistlib
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
FORMATS = {
    "linux": ("Linux", "libhendrix_audio_to_midi.so", "x86_64-linux", ".so"),
    "windows": ("Windows", "hendrix_audio_to_midi.dll", "x86_64-win", ".vst3"),
    "macos": ("macOS", "libhendrix_audio_to_midi.dylib", None, None),
}

def notices(folder, target):
    metadata = json.loads(subprocess.check_output([
        "cargo", "metadata", "--manifest-path", str(ROOT / "Cargo.toml"),
        "--locked", "--format-version", "1", "--features", "portable",
        "--filter-platform", target,
    ]))
    # Traverse only dependencies active for this build. Cargo.lock also holds
    # dependencies for the optional standalone preview and other platforms.
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    pending = [metadata["resolve"]["root"]]
    included = set()
    while pending:
        item = pending.pop()
        if item in included:
            continue
        included.add(item)
        for dep in nodes[item]["deps"]:
            if any(kind["kind"] != "dev" for kind in dep["dep_kinds"]):
                pending.append(dep["pkg"])
    license_root = folder / "Licenses"
    license_root.mkdir()
    lines = ["Hendrix 1.0 portable plugin dependency notices", ""]
    for pkg in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
        if pkg["id"] not in included or not pkg.get("source"):
            continue
        name = f'{pkg["name"]}-{pkg["version"]}'
        lines.append(f'{name}: {pkg["license"] or "See license file"}')
        if pkg["repository"]:
            lines.append(f'  Source: {pkg["repository"]}')
        root = Path(pkg["manifest_path"]).parent
        files = {p for p in root.iterdir() if p.is_file() and p.name.upper().startswith(
            ("LICENSE", "LICENCE", "COPYING", "NOTICE")
        )}
        if pkg.get("license_file"):
            files.add(root / pkg["license_file"])
        if not files and pkg.get("license") == "ISC" and pkg["name"].startswith("nice-"):
            files.add(ROOT / "nice-plug-LICENSE.txt")
        if files:
            dest = license_root / name
            dest.mkdir()
            for path in sorted(files):
                shutil.copyfile(path, dest / path.name)
    (folder / "DEPENDENCY-LICENSES.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=FORMATS)
    parser.add_argument("target")
    parser.add_argument("library", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    platform, binary, vst_dir, vst_ext = FORMATS[args.platform]
    if args.library.name != binary:
        parser.error(f"Expected {binary}")
    name = f"Hendrix-1.0-{platform}-x86_64-" + ("CLAP" if args.platform == "macos" else "CLAP-VST3")
    root = args.output / name
    root.mkdir(parents=True)
    clap = root / "Hendrix.clap"
    if args.platform == "macos":
        contents = clap / "Contents"
        (contents / "MacOS").mkdir(parents=True)
        shutil.copyfile(args.library, contents / "MacOS/Hendrix")
        (contents / "MacOS/Hendrix").chmod(0o755)
        (contents / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleName": "Hendrix", "CFBundleExecutable": "Hendrix",
            "CFBundleIdentifier": "studio.moonshroom.clap.Hendrix",
            "CFBundlePackageType": "BNDL", "CFBundleVersion": "1",
            "CFBundleShortVersionString": "1.0", "LSMinimumSystemVersion": "12.0",
        }))
        subprocess.run(["codesign", "--force", "--sign", "-", "--timestamp=none", str(clap)], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(clap)], check=True)
    else:
        shutil.copyfile(args.library, clap)
        clap.chmod(0o755)
        vst = root / "Hendrix.vst3" / "Contents" / vst_dir / ("Hendrix" + vst_ext)
        vst.parent.mkdir(parents=True)
        shutil.copyfile(args.library, vst)
        vst.chmod(0o755)
    shutil.copyfile(ROOT / "LICENSE", root / "LICENSE")
    shutil.copyfile(ROOT / "THIRD_PARTY_NOTICES.md", root / "THIRD_PARTY_NOTICES.md")
    notices(root, args.target)
    install = {
        "windows": r"Copy Hendrix.clap to %COMMONPROGRAMFILES%\CLAP and Hendrix.vst3 to %COMMONPROGRAMFILES%\VST3.",
        "linux": "Copy Hendrix.clap to ~/.clap and Hendrix.vst3 to ~/.vst3. Built on Ubuntu 22.04 (glibc 2.35). The editor uses X11/XWayland and OpenGL.",
        "macos": "Copy Hendrix.clap to ~/Library/Audio/Plug-Ins/CLAP. Requires Intel macOS 12 or later. Ad-hoc signed.",
    }[args.platform]
    (root / "INSTALL.txt").write_text(
        f"HENDRIX 1.0 — Moonshroom Studio\n{platform} x86_64\n\n{install}\n\n"
        "Rescan plugins in your host. Insert Hendrix on an audio track and route its MIDI output to a synth.\n"
        "The DAW must support MIDI output from audio effects in the selected format.\n"
        "This edition uses the host MIDI output. The Mac AU/VST3 edition also offers CoreMIDI virtual output.\n"
        "The portable VST3 has a separate plugin ID and preset state from the original Mac VST3.\n",
        encoding="utf-8",
    )
    archive = args.output / f"{name}.zip"
    if args.platform == "macos":
        subprocess.run(["ditto", "-c", "-k", "--norsrc", "--noextattr", "--noqtn",
                        "--keepParent", str(root), str(archive)], check=True)
    else:
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            for file in sorted(root.rglob("*")):
                if file.is_file():
                    z.write(file, file.relative_to(args.output))
    with zipfile.ZipFile(archive) as z:
        bad = z.testzip()
        if bad:
            raise RuntimeError(f"Corrupt ZIP member: {bad}")
    print(archive)

if __name__ == "__main__":
    main()
