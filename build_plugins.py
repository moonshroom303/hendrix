#!/usr/bin/env python3
"""Build Hendrix AU, VST3 and standalone on macOS. Python 3.9+, Rust and CMake."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

IPLUG_COMMIT = "d82b2e99b17c8f723d3f6e2a88ffd8b656f28e5d"
SDK_COMMIT = "3cdf9ca5d1f5b1b21e0a86832aa4abe55607bd96"

def run(*args, cwd=None):
    subprocess.run([str(x) for x in args], cwd=cwd, check=True)

def checkout(path, url, commit, submodules=()):
    if not (path / ".git").exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--no-checkout", "--filter=blob:none", url, path)
        run("git", "checkout", "--detach", commit, cwd=path)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=path, text=True).strip()
    if actual != commit:
        raise RuntimeError(f"{path}: expected {commit}, found {actual}; use a clean pinned checkout")
    if submodules:
        run("git", "submodule", "update", "--init", "--depth", "1", "--", *submodules, cwd=path)

def patch(path, before, after):
    source = path.read_text()
    if after in source:
        return
    if source.count(before) != 1:
        raise RuntimeError(f"Unexpected dependency source: {path}")
    path.write_text(source.replace(before, after))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, default=Path("build"))
    parser.add_argument("--framework", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--jobs", type=int, default=min(4, os.cpu_count() or 2))
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("The current format wrappers target macOS; the Rust library is portable.")
    source = Path(__file__).resolve().parent
    build = args.build.resolve()
    framework = (args.framework or build / "deps/iPlug2").resolve()
    sdk = (args.sdk or build / "deps/vst3sdk").resolve()
    checkout(framework, "https://github.com/iPlug2/iPlug2.git", IPLUG_COMMIT)
    checkout(sdk, "https://github.com/steinbergmedia/vst3sdk.git", SDK_COMMIT,
             ("base", "cmake", "pluginterfaces", "public.sdk"))
    sdk_link = framework / "Dependencies/IPlug/VST3_SDK"
    if not sdk_link.is_symlink():
        if sdk_link.exists():
            # The upstream checkout contains only a README at this location.
            if any(p.name != "README.md" for p in sdk_link.iterdir()):
                raise RuntimeError(f"Refusing to replace a populated SDK: {sdk_link}")
            sdk_link.rename(sdk_link.with_name("VST3_SDK-readme"))
        sdk_link.symlink_to(sdk, target_is_directory=True)
    if sdk_link.resolve() != sdk:
        raise RuntimeError(f"SDK link points elsewhere: {sdk_link}")
    patch(framework / "IPlug/VST3/IPlugVST3_ProcessorBase.cpp",
          "int16 tmp = static_cast<int16> (msg.PitchWheel() * 0x3FFF);",
          "int16 tmp = static_cast<int16>(std::clamp((msg.PitchWheel() + 1.0) * 8192.0, 0.0, 16383.0)); // Hendrix patch: bipolar MIDI bend to unsigned 14-bit")
    patch(framework / "IPlug/APP/IPlugAPP.cpp",
          """    std::vector<uint8_t> message;
    message.push_back(msg.mStatus);
    message.push_back(msg.mData1);
    message.push_back(msg.mData2);

    mAppHost->mMidiOut->sendMessage(&message);""",
          """    // Hendrix patch: fixed-size short MIDI messages need no temporary heap vector.
    const unsigned char message[3] = {msg.mStatus, msg.mData1, msg.mData2};
    mAppHost->mMidiOut->sendMessage(message, 3);""")
    run("cmake", "-S", source, "-B", build / "formats", "-G", "Unix Makefiles",
        "-DCMAKE_BUILD_TYPE=Release", f"-DIPLUG2_DIR={framework}",
        "-DIGRAPHICS_BACKEND=NANOVG", "-DIGRAPHICS_RENDERER=GL2",
        "-DIPLUG_DEPLOY_PLUGINS=OFF")
    run("cmake", "--build", build / "formats", "--parallel", str(args.jobs))
    for suffix in ("component", "vst3", "app"):
        bundle = build / "formats/out" / f"Hendrix.{suffix}"
        run("codesign", "--force", "--sign", "-", "--timestamp=none", bundle)
        run("codesign", "--verify", "--deep", "--strict", bundle)
    print(f"Built three bundles in {build / 'formats/out'}")

if __name__ == "__main__":
    main()
