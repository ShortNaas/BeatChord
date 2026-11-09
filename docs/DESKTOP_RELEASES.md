# Desktop releases

How a desktop build gets its version and how its files are named on the releases page. Windows
already follows all of this; every Linux variant (AppImage, deb, rpm, and anything added later) has
to follow the same protocol.

## 1. One version, stamped from one place

- The version is a plain string such as `1.8` or `1.8-beta1`. No leading `v`.
- `desktopApp/build.gradle.kts` reads it from `-Pbitchord.version=...` and falls back to a default
  (`appVersion`). CI passes the tag with the `v` removed (`v1.8` becomes `1.8`).
- The app reads the same value at runtime from the `bitchord.version` system property. It is baked
  into every package by `jvmArgs("-Dbitchord.version=$appVersion")` in the `compose.desktop`
  block of `desktopApp/build.gradle.kts`, which covers exe, msi, deb, rpm and the AppImage (it
  wraps the same app image, so `appimage.sh` needs nothing). Do not remove that line, and do not
  add a separate launcher that skips it: Settings would then report the hardcoded fallback instead
  of the version it was built as.
- A pre-release version carries a suffix after a dash: `1.8-beta1`.

## 2. The installer's own version is numeric

Installer formats reject `-beta1`, so `nativePackageVersion` in `desktopApp/build.gradle.kts`
builds a separate numeric one: `major.minor.desktopVersionCode`, for example `1.8.26`.

- Bump `desktopVersionCode` for **every** build you publish, betas included. Windows only
  upgrades in place when the numeric version goes up; the same number is treated as "already
  installed".
- deb and rpm take the same `nativePackageVersion`, so `apt`/`dnf` upgrade ordering works the same
  way. Do not hand-edit package versions anywhere else.
- Never change the Windows `upgradeUuid`. For Linux, never rename the package (`packageName =
  "BitChord"`) or the upgrade path breaks.

## 3. File names on the releases page

The release workflow (`.github/workflows/release.yml`) renames build output to these exact
patterns, where `<v>` is the version from section 1:

| Platform | File |
|---|---|
| Windows | `BitChord-<v>-windows-x64-setup.exe` |
| Windows | `BitChord-<v>-windows-x64.msi` |
| Windows | `BitChord-<v>-windows-x64-portable.zip` |
| Linux | `BitChord-<v>-linux-x86_64.AppImage` |
| Linux | `BitChord-<v>-linux-amd64.deb` |
| Linux | `BitChord-<v>-linux-x86_64.rpm` |
| macOS | `BitChord-<v>-macos-arm64.dmg` (Apple Silicon; also `.pkg` and `.zip`) |
| macOS | `BitChord-<v>-macos-x64.dmg` (Intel; also `.pkg` and `.zip`) |

Rules:

- Keep the platform tail exactly as above so users can tell the files apart and install the right
  one.
- Upload files to the release, do not rely on the generated source archives.

macOS builds are unsigned. Clear the quarantine flag with `xattr -cr /Applications/BitChord.app`.
If the app will not start, a missing library may need installing first with Homebrew
(`brew install <library>`; the exact library still has to be confirmed).

## 4. No automatic update check

The app does not ask GitHub (or anyone else) whether a newer release exists: it is local-first and
makes no network request it was not asked to make. Users install a new version by downloading it
from the releases page themselves.

What this means for releasing:

- The git tag must be `v<version>` (`v1.8`). The workflow also accepts only `v*.*.*`; for a
  two-part version like `v1.8` use the manual `workflow_dispatch` with `version` filled in, or
  tag `v1.8.0` and make sure the tag and the stamped version agree.
- Mark betas as pre-release so they are not mistaken for a finished release.
- Linux and Windows files should be on the same release so one download page holds both platforms.

## 5. Checklist for a Linux build

Before publishing, confirm each of these on the built files, not just in the source:

- [ ] Version stamped (`-Pbitchord.version=<v>`). Launch each built file and check Settings shows the
      same version.
- [ ] `desktopVersionCode` is higher than the last published build.
- [ ] The three files are named exactly as in section 3.
- [ ] They are attached to the same release as the Windows files.
- [ ] The release is not a draft or pre-release (for a real release).
- [ ] Install the previous release, then the new one over it: deb with `sudo apt install
      ./BitChord-*.deb`, rpm with `sudo dnf upgrade ./BitChord-*.rpm`. It must upgrade, not
      reinstall or conflict.
