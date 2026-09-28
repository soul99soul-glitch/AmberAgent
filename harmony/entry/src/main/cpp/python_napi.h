#ifndef AMBER_PYTHON_NAPI_H
#define AMBER_PYTHON_NAPI_H
#include <napi/native_api.h>
napi_value RegisterPython(napi_env env, napi_value exports);
#endif
