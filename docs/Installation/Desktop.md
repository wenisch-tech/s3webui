---
sidebar_label: Desktop (experimental)
---

# Run as a desktop application (experimental)

This page installs S3 Web UI as a native desktop application with its own Java runtime, so you need neither Docker nor a Java installation. The desktop edition is experimental.

## What is different from the container

- The app opens in its own window and listens only on `127.0.0.1`, on a random free port.
- **Authentication is disabled.** Anyone with access to your user session can use the app, so there is no login. The first time you start the app, enter your S3 endpoint and keys in the key picker. They are saved automatically (`AUTOSTOREKEYS`) and appear in the picker on the next start, so you don't need to manage them under **Settings → S3 keys**.
- Data (database and encryption key) is stored per user:
  - Linux: `~/.local/share/s3webui`
  - Windows: `%APPDATA%\s3webui`
- Use the container if you need several users, OIDC or access from other machines.

## Install

Download the package for your system from the [GitHub releases](https://github.com/wenisch-tech/s3webui/releases) and verify it against `SHA256SUMS`.

| System | Package | Install |
|---|---|---|
| Arch Linux, CachyOS | `s3webui-<version>-1-x86_64.pkg.tar.zst` | `sudo pacman -U s3webui-*.pkg.tar.zst` |
| Debian, Ubuntu | `s3webui_<version>-1_amd64.deb` | `sudo apt install ./s3webui_*.deb` |
| Fedora, openSUSE | `s3webui-<version>-1.x86_64.rpm` | `sudo dnf install ./s3webui-*.rpm` |
| Windows | `S3WEBUI-<version>.msi` | double-click the installer |
| macOS, Apple Silicon | `S3WEBUI-<version>-arm64.dmg` | drag the app to Applications |
| macOS, Intel | `S3WEBUI-<version>-x64.dmg` | drag the app to Applications |

The packages are not signed yet:

- **Windows:** SmartScreen shows "Windows protected your PC". Choose **More info → Run anyway**.
- **macOS:** Gatekeeper blocks the first start. Right-click the app and choose **Open**, or allow it under **System Settings → Privacy & Security**.

## Build it yourself

You need JDK 25 and Maven 3.9+. Run the commands from the repository root.

```bash
# run without packaging
mvn -Pdesktop -DskipTests javafx:run

# Linux app-image, plus an Arch package when makepkg is available
packaging/build-linux.sh
```

The installers are built by the `Desktop packages` GitHub workflow, because `jpackage` can only build for the operating system it runs on.
