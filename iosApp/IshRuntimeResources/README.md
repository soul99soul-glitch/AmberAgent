# Embedded iSH Rootfs Resource

This directory vendors the minimal rootfs used only by the `iosAppExperimentalGPL` target.

- Source package: `Lolendor/ish-arm64-pkg`
- Version: `0.3.3`
- Release asset: `https://github.com/Lolendor/ish-arm64-pkg/releases/download/v0.3.3/fs.tar.gz`
- Verified SHA-256: `be0f3c133f78f28b023288459b33dc28fa253a6ef29f7123bc5f3892edf90ad4`

The `fs/` directory is marked as vendored binary data in the repository attributes so Git does not normalize or diff
rootfs files. Do not edit files under `fs/` by hand; replace the directory from a verified upstream release if the
runtime package changes.

This resource must stay out of the stable `iosApp` target. It is bundled only by `iosAppExperimentalGPL` because the
iSH-derived runtime requires explicit GPL/license review before distribution.
