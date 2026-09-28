# CPython target patch

`cpython-3.14.7-ohos12-gettext.patch` only disables gettext-specific methods in
`Modules/_localemodule.c` on API12. The API12 SDK header `libintl.h` exists but
contains no gettext/dgettext/dcgettext/textdomain/bindtextdomain declarations;
upstream's HAVE_LIBINTL_H guard incorrectly assumes those methods exist.

Observed failure: 10 C compilation errors for implicit declarations and
integer-to-pointer conversion. The guarded patch removes those exact missing
functions and leaves POSIX locale functionality intact. Host behavior is
unchanged. The archive, patch and patched file hashes are locked separately.
No SDK header, system configuration or positive configure cache is changed.
