#include "python_napi.h"
#include "python_core.h"
#include <atomic>
#include <cmath>
#include <mutex>
#include <unordered_map>
#include <vector>
namespace {
using amber::python::Exception;
using amber::python::Options;
using amber::python::Result;
struct Completion;
struct Environment {
    amber::python::Runtime runtime;
    std::atomic<bool> stopping {false};
    std::mutex mutex;
    std::unordered_map<Completion *, std::shared_ptr<Completion>> pending;
};
struct Payload { Result result; std::string rejectionCode; };
struct Completion {
    napi_deferred deferred = nullptr;
    napi_threadsafe_function function = nullptr;
    std::shared_ptr<Environment> environment;
    std::atomic<bool> posted {false};
    void Post(Payload payload) {
        if (posted.exchange(true)) return;
        if (!environment->stopping) {
            auto data = std::make_unique<Payload>(std::move(payload));
            if (napi_call_threadsafe_function(function, data.get(), napi_tsfn_nonblocking) == napi_ok) data.release();
        }
        napi_release_threadsafe_function(function, napi_tsfn_release);
    }
};
void Check(napi_status status) {
    if (status != napi_ok) throw Exception("runtime_error", "Cannot create embedded Python result");
}
napi_value String(napi_env env, const std::string &text) {
    napi_value result; Check(napi_create_string_utf8(env, text.data(), text.size(), &result)); return result;
}
napi_value Null(napi_env env) { napi_value value; Check(napi_get_null(env, &value)); return value; }
void Set(napi_env env, napi_value value, const char *key, napi_value item) { Check(napi_set_named_property(env, value, key, item)); }
napi_value Error(napi_env env, const std::string &code) {
    napi_value error; Check(napi_create_error(env, nullptr, String(env, "Embedded Python native request failed"), &error));
    Set(env, error, "code", String(env, code)); return error;
}
void Reject(napi_env env, napi_deferred deferred, const std::string &code) {
    try { napi_reject_deferred(env, deferred, Error(env, code)); } catch (const std::exception &) {}
}
napi_value Value(napi_env env, const Result &result) {
    napi_value value; Check(napi_create_object(env, &value));
    Set(env, value, "status", String(env, result.status));
    napi_value exit = Null(env); if (result.exitCode) Check(napi_create_int32(env, *result.exitCode, &exit));
    Set(env, value, "exitCode", exit); Set(env, value, "stdout", String(env, result.stdoutText));
    Set(env, value, "stderr", String(env, result.stderrText));
    Set(env, value, "errorCode", result.errorCode ? String(env, *result.errorCode) : Null(env)); return value;
}
void Finalize(napi_env, void *data, void *) { delete static_cast<std::shared_ptr<Completion> *>(data); }
void Deliver(napi_env env, napi_value, void *context, void *data) {
    auto *completion = static_cast<Completion *>(context);
    std::unique_ptr<Payload> payload(static_cast<Payload *>(data));
    if (!env || completion->environment->stopping) return;
    try {
        if (payload->rejectionCode.empty()) Check(napi_resolve_deferred(env, completion->deferred, Value(env, payload->result)));
        else Reject(env, completion->deferred, payload->rejectionCode);
    } catch (const std::exception &) { Reject(env, completion->deferred, "runtime_error"); }
    { std::lock_guard<std::mutex> lock(completion->environment->mutex); completion->environment->pending.erase(completion); }
    napi_release_threadsafe_function(completion->function, napi_tsfn_release);
}
std::shared_ptr<Completion> MakeCompletion(napi_env env, const std::shared_ptr<Environment> &environment, napi_deferred deferred) {
    auto completion = std::make_shared<Completion>(); completion->environment = environment; completion->deferred = deferred;
    auto holder = std::make_unique<std::shared_ptr<Completion>>(completion);
    Check(napi_create_threadsafe_function(env, nullptr, nullptr, String(env, "AmberPython"), 0, 2,
        holder.get(), Finalize, completion.get(), Deliver, &completion->function));
    holder.release();
    { std::lock_guard<std::mutex> lock(environment->mutex); environment->pending.emplace(completion.get(), completion); }
    return completion;
}
void Cleanup(void *data) {
    std::unique_ptr<std::shared_ptr<Environment>> holder(static_cast<std::shared_ptr<Environment> *>(data));
    auto environment = *holder; environment->stopping = true;
    std::vector<std::shared_ptr<Completion>> pending;
    { std::lock_guard<std::mutex> lock(environment->mutex);
      for (auto &entry : environment->pending) pending.push_back(entry.second);
      environment->pending.clear(); }
    for (auto &completion : pending) napi_release_threadsafe_function(completion->function, napi_tsfn_abort);
    environment->runtime.Shutdown();
}
std::string ReadString(napi_env env, napi_value value, size_t limit) {
    napi_valuetype type; size_t size = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok || size > limit)
        throw Exception("invalid_arguments", "Python argument must be a bounded string");
    std::string text(size + 1, '\0'); size_t copied;
    Check(napi_get_value_string_utf8(env, value, text.data(), text.size(), &copied)); text.resize(copied); return text;
}
napi_value Property(napi_env env, napi_value value, const char *name) {
    napi_valuetype type; napi_value property;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_object ||
        napi_get_named_property(env, value, name, &property) != napi_ok)
        throw Exception("invalid_arguments", "Python options must have typed properties");
    return property;
}
int Timeout(napi_env env, napi_value value) {
    napi_valuetype type; double number;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_number ||
        napi_get_value_double(env, value, &number) != napi_ok || !std::isfinite(number) ||
        number < 1 || number > 60000 || std::floor(number) != number)
        throw Exception("invalid_arguments", "Python timeout must be 1 to 60000 milliseconds");
    return static_cast<int>(number);
}
struct Call {
    std::shared_ptr<Environment> environment;
    napi_value args[3] = {};
    Call(napi_env env, napi_callback_info info, size_t expected) {
        size_t count = 3; void *data;
        Check(napi_get_cb_info(env, info, &count, args, nullptr, &data));
        if (count != expected || !data) throw Exception("invalid_arguments", "Incorrect Python argument count");
        environment = *static_cast<std::shared_ptr<Environment> *>(data);
        if (environment->stopping) throw Exception("runtime_closed", "Python environment closed");
    }
};
napi_value Execute(napi_env env, napi_callback_info info) {
    napi_value promise; napi_deferred deferred;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) return nullptr;
    std::shared_ptr<Completion> completion;
    try {
        Call call(env, info, 2); auto id = ReadString(env, call.args[0], 256);
        Options options; options.source = ReadString(env, Property(env, call.args[1], "source"), 256 * 1024);
        options.stdinText = ReadString(env, Property(env, call.args[1], "stdin"), 64 * 1024);
        options.resourceRoot = ReadString(env, Property(env, call.args[1], "resourceRoot"), 4096);
        options.timeoutMs = Timeout(env, Property(env, call.args[1], "timeoutMs"));
        completion = MakeCompletion(env, call.environment, deferred);
        call.environment->runtime.Execute(id, std::move(options), [completion](Result result) { completion->Post({std::move(result), {}}); });
    } catch (const Exception &error) {
        if (completion) completion->Post({{}, error.code}); else Reject(env, deferred, error.code);
    } catch (const std::exception &) {
        if (completion) completion->Post({{}, "runtime_error"}); else Reject(env, deferred, "runtime_error");
    }
    return promise;
}
napi_value Cancel(napi_env env, napi_callback_info info) {
    try { Call call(env, info, 1); call.environment->runtime.Cancel(ReadString(env, call.args[0], 256));
        napi_value value; Check(napi_get_undefined(env, &value)); return value; }
    catch (const Exception &error) { napi_throw(env, Error(env, error.code)); }
    catch (const std::exception &) { napi_throw_error(env, "runtime_error", "Cannot cancel Python request"); }
    return nullptr;
}
napi_value Version(napi_env env, napi_callback_info info) {
    try { Call call(env, info, 0); return String(env, amber::python::Version()); }
    catch (const Exception &error) { napi_throw(env, Error(env, error.code)); return nullptr; }
}
} // namespace
napi_value RegisterPython(napi_env env, napi_value exports) {
    try {
        auto holder = std::make_unique<std::shared_ptr<Environment>>(std::make_shared<Environment>());
        napi_property_descriptor properties[] = {
            {"pythonVersion", nullptr, Version, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pythonExecute", nullptr, Execute, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pythonCancel", nullptr, Cancel, nullptr, nullptr, nullptr, napi_default, holder.get()}
        };
        Check(napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties));
        Check(napi_add_env_cleanup_hook(env, Cleanup, holder.get())); holder.release(); return exports;
    } catch (const std::exception &) { napi_throw_error(env, "runtime_error", "Cannot initialize Python native module"); return nullptr; }
}
