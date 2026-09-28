# libssh2 1.11.1 security fix

`libssh2-1.11.1-server-sig-algs-size.patch` backports only the buffer allocation
fix from official commit [b671ac9bdd7fa4dc321c9c07d553bdea68bed727](https://github.com/libssh2/libssh2/commit/b671ac9bdd7fa4dc321c9c07d553bdea68bed727)
([PR 1885](https://github.com/libssh2/libssh2/pull/1885)). Original report/patch by Kit Knox.
libssh2 BSD license remains in `licenses/libssh2-1.11.1.txt`.

The filtered signature list is a byte-for-byte subset of the server list. Its
allocation must use the server list length. The 1.11.1 allocation uses the shorter
client supported list and can overflow when the server repeats a matching entry.
Our real AsyncSSH fixture reproduced the write with AddressSanitizer during RSA
in-memory authentication; intermittent host crashes also occurred without ASAN.

The release archive remains untouched and SHA256 locked. Build validates this
patch and the complete patched `src/userauth.c` before compiling. No other master
branch changes or crypto code are copied. Host native authentication and ASAN
regression tests cover the backport.

`libssh2-1.11.1-exit-status-presence.patch` is an **Amber project patch**, not an
upstream backport. The public get-exit-status API returns 0 even when no status
was received. A separate receipt flag is set only in the actual `exit-status`
packet branch and exposed through a read-only public query. No private channel
structure is read by Amber, no sentinel changes valid protocol values, and the
native packet preserves the complete unsigned 32-bit value. A separate real
AsyncSSH fixture covers both missing status and `0xffffffff`.
