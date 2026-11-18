#!/usr/bin/env bash
# Builds the jpackage app-image and the Arch package locally.
# Usage: packaging/build-linux.sh [--deb-rpm]   (deb/rpm need dpkg/fakeroot and rpmbuild installed)
set -euo pipefail
cd "$(dirname "$0")/.."

profiles="desktop,desktop-package,desktop-linux-image"
[[ "${1:-}" == "--deb-rpm" ]] && profiles+=",desktop-linux-packages"

# jpackage needs numeric X.Y.Z, so drop any -SNAPSHOT/-pr suffix
version="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-.*//')"

rm -rf target/dist target/desktop
mvn -B -P"$profiles" -DskipTests -Djacoco.skip=true -Ddesktop.app.version="$version" pre-integration-test

if command -v makepkg >/dev/null; then
  (cd packaging/arch && S3WEBUI_VERSION="$version" makepkg -f --nodeps)
  mv packaging/arch/*.pkg.tar.zst target/dist/
  echo "Arch package: $(ls target/dist/*.pkg.tar.zst)"
fi
