#include "mosh_napi.h"
NAPI_MODULE_INIT() { return RegisterMosh(env, exports); }
