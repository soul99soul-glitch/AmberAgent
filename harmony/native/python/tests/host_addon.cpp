#include "python_napi.h"
#include <node_api.h>
napi_value Initialize(napi_env env, napi_value exports) { return RegisterPython(env, exports); }
NAPI_MODULE(NODE_GYP_MODULE_NAME, Initialize)
