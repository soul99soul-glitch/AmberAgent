# iOS Terminal Runtime Strategy

Amber Agent iOS separates terminal execution into stable and experimental runtimes so the App Store build remains reviewable while heavier terminal environments can still be tested deliberately.

## Stable Runtimes

- `remote_ssh`: recommended full CLI runtime. It is the default target for Model Council and external CLI tools because it can support PTY, package managers, long jobs, interactive login, and file sync on the remote host.
- `local_ios_tools`: lightweight local helper runtime. It is intended for sandbox file operations and small built-in tools such as `curl`, `scp`, `sftp`, `tar`, Python, or Lua once the `ios_system` dependency is linked. It is not a Termux replacement and must not advertise npm, ELF execution, apk/pkg, or long-running background jobs.

## Experimental Runtimes

- `remote_mosh`: experimental remote runtime for resilient mobile terminal sessions. It must stay behind license review until the final Mosh implementation and dependency chain are approved.
- `ish_experimental`: experimental embedded Linux runtime. It must stay out of the stable App Store target unless GPL source, license text, modifications, rootfs provenance, and checksums are packaged for release.

The default `iosApp` target does not compile experimental runtime code. The `iosAppExperimentalGPL` target defines `ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES` and is the only target that should link Mosh or iSH implementation code.

## Runtime Capability Rules

- Only `remote_ssh` is supported for external CLI execution by default on iOS.
- `local_ios_tools` may run lightweight local commands but package installation must return unsupported.
- `remote_mosh` and `ish_experimental` require both an experimental build and a user-enabled Labs setting.
- Stable builds must not download a rootfs, expose `apk add`, execute user-downloaded binaries, or silently route Model Council seats to experimental runtimes.
