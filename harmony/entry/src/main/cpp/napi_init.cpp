#include <napi/native_api.h>
#include "amber_native.h"
#include "ssh_napi.h"
#include "python_napi.h"
#include "plugin_js_napi.h"
#include "mosh_napi.h"
#include "google_loopback_napi.h"
#include "stream_frame_rate.h"

#include <memory>
#include <string>
#include <vector>

namespace {
struct CountJob {
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    std::vector<uint32_t> ids;
    std::vector<std::string> texts;
    std::vector<int64_t> counts;
    int64_t error = 0;
};

void Reject(napi_env env, napi_deferred deferred, const char *message)
{
    napi_value text = nullptr;
    napi_value error = nullptr;
    if (napi_create_string_utf8(env, message, NAPI_AUTO_LENGTH, &text) == napi_ok &&
        napi_create_error(env, nullptr, text, &error) == napi_ok) {
        napi_reject_deferred(env, deferred, error);
    }
}

bool ReadString(napi_env env, napi_value value, std::string &result)
{
    napi_valuetype type = napi_undefined;
    size_t size = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok) {
        return false;
    }
    result.resize(size + 1);
    size_t copied = 0;
    if (napi_get_value_string_utf8(env, value, result.data(), result.size(), &copied) != napi_ok) {
        return false;
    }
    result.resize(copied);
    return true;
}

uint32_t TokenizerId(const std::string &id)
{
    if (id == "o200k_base") return AMBER_O200K_BASE;
    if (id == "cl100k_base") return AMBER_CL100K_BASE;
    if (id == "claude") return AMBER_CLAUDE;
    if (id == "gemini") return AMBER_GEMINI;
    return 0;
}

const char *ReadInputs(napi_env env, napi_callback_info info, CountJob &job)
{
    size_t argc = 3;
    napi_value args[3] = {nullptr, nullptr, nullptr};
    if (napi_get_cb_info(env, info, &argc, args, nullptr, nullptr) != napi_ok || argc != 2) {
        return "countBatch requires tokenizerIds and texts arrays";
    }
    bool idsArray = false;
    bool textsArray = false;
    uint32_t idsLength = 0;
    uint32_t textsLength = 0;
    if (napi_is_array(env, args[0], &idsArray) != napi_ok || !idsArray ||
        napi_is_array(env, args[1], &textsArray) != napi_ok || !textsArray ||
        napi_get_array_length(env, args[0], &idsLength) != napi_ok ||
        napi_get_array_length(env, args[1], &textsLength) != napi_ok) {
        return "tokenizerIds and texts must be arrays";
    }
    if (idsLength != textsLength) {
        return "tokenizerIds and texts must have the same length";
    }
    job.ids.reserve(idsLength);
    job.texts.reserve(idsLength);
    job.counts.resize(idsLength);
    for (uint32_t i = 0; i < idsLength; ++i) {
        napi_value idValue = nullptr;
        napi_value textValue = nullptr;
        std::string id;
        std::string text;
        if (napi_get_element(env, args[0], i, &idValue) != napi_ok ||
            napi_get_element(env, args[1], i, &textValue) != napi_ok ||
            !ReadString(env, idValue, id) || !ReadString(env, textValue, text)) {
            return "tokenizerIds and texts must contain only strings";
        }
        const uint32_t numericId = TokenizerId(id);
        if (numericId == 0) return "Unknown tokenizer ID";
        job.ids.push_back(numericId);
        job.texts.push_back(std::move(text));
    }
    return nullptr;
}

void Execute(napi_env, void *data)
{
    auto &job = *static_cast<CountJob *>(data);
    for (size_t i = 0; i < job.ids.size(); ++i) {
        const auto &text = job.texts[i];
        const int64_t count = amber_count_tokens(job.ids[i],
            reinterpret_cast<const uint8_t *>(text.data()), text.size());
        if (count < 0) {
            job.error = count;
            return;
        }
        job.counts[i] = count;
    }
}

void Complete(napi_env env, napi_status status, void *data)
{
    std::unique_ptr<CountJob> job(static_cast<CountJob *>(data));
    if (status != napi_ok) {
        Reject(env, job->deferred, "Tokenizer async work did not complete");
    } else if (job->error != 0) {
        const char *message = job->error == -2 ? "Tokenizer input is not valid UTF-8" :
            job->error == -4 ? "Tokenizer panicked" : "Tokenizer failed";
        Reject(env, job->deferred, message);
    } else {
        napi_value results = nullptr;
        bool ready = napi_create_array_with_length(env, job->counts.size(), &results) == napi_ok;
        for (size_t i = 0; ready && i < job->counts.size(); ++i) {
            napi_value count = nullptr;
            ready = napi_create_int64(env, job->counts[i], &count) == napi_ok &&
                napi_set_element(env, results, static_cast<uint32_t>(i), count) == napi_ok;
        }
        if (ready) {
            napi_resolve_deferred(env, job->deferred, results);
        } else {
            Reject(env, job->deferred, "Cannot create tokenizer result");
        }
    }
    napi_delete_async_work(env, job->work);
}

napi_value CountBatch(napi_env env, napi_callback_info info)
{
    napi_value promise = nullptr;
    napi_deferred deferred = nullptr;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) {
        napi_throw_error(env, nullptr, "Cannot create tokenizer promise");
        return nullptr;
    }
    try {
        auto job = std::make_unique<CountJob>();
        job->deferred = deferred;
        const char *inputError = ReadInputs(env, info, *job);
        if (inputError != nullptr) {
            Reject(env, deferred, inputError);
            return promise;
        }
        napi_value name = nullptr;
        if (napi_create_string_utf8(env, "AmberTokenCount", NAPI_AUTO_LENGTH, &name) != napi_ok ||
            napi_create_async_work(env, nullptr, name, Execute, Complete, job.get(), &job->work) != napi_ok) {
            Reject(env, deferred, "Cannot create tokenizer async work");
            return promise;
        }
        if (napi_queue_async_work(env, job->work) != napi_ok) {
            napi_delete_async_work(env, job->work);
            Reject(env, deferred, "Cannot queue tokenizer async work");
            return promise;
        }
        job.release();
    } catch (const std::exception &) {
        Reject(env, deferred, "Cannot allocate tokenizer inputs");
    }
    return promise;
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor functions[] = {
        {"countBatch", nullptr, CountBatch, nullptr, nullptr, nullptr, napi_default, nullptr}
    };
    if (napi_define_properties(env, exports, 1, functions) != napi_ok) return nullptr;
    if (RegisterSSH(env, exports) == nullptr) return nullptr;
    if (RegisterPython(env, exports) == nullptr) return nullptr;
    if (RegisterMosh(env, exports) == nullptr) return nullptr;
    if (RegisterPluginJs(env, exports) == nullptr) return nullptr;
    if (RegisterStreamingFrameRate(env, exports) == nullptr) return nullptr;
    return RegisterGoogleLoopback(env, exports);
}

napi_module module = {1, 0, nullptr, Init, "amber_native", nullptr, {0}};
} // namespace

extern "C" __attribute__((constructor)) void RegisterAmberNativeModule()
{
    napi_module_register(&module);
}
