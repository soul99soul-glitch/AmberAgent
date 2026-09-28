#ifndef AMBER_NATIVE_H
#define AMBER_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum AmberTokenizerId {
    AMBER_O200K_BASE = 1,
    AMBER_CL100K_BASE = 2,
    AMBER_CLAUDE = 3,
    AMBER_GEMINI = 4
};

// Input is borrowed UTF-8 with an explicit byte length (embedded NUL is supported).
// Errors: -1 unknown ID, -2 invalid UTF-8, -3 nonempty null pointer, -4 Rust panic.
int64_t amber_count_tokens(uint32_t tokenizer_id, const uint8_t *text, size_t len);

#ifdef __cplusplus
}
#endif

#endif
