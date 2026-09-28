#include "plugin_js_napi.h"
#include "plugin_js_core.h"
#include <atomic>
#include <cmath>
#include <mutex>
#include <unordered_map>

namespace {
using amber::pluginjs::Event;
using amber::pluginjs::Exception;
struct Completion;
struct Environment {
    amber::pluginjs::Runtime runtime;
    std::atomic<bool> stopping {false};
    std::mutex mutex;
    std::unordered_map<Completion *, std::shared_ptr<Completion>> pending;
};
struct Payload { Event event; std::string rejectionCode; std::string rejectionMessage; };
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
    if (status != napi_ok) throw Exception("runtime_error", "Cannot create plugin JavaScript event");
}
napi_value String(napi_env env, const std::string &text) {
    napi_value value; Check(napi_create_string_utf8(env, text.data(), text.size(), &value)); return value;
}
void Set(napi_env env, napi_value value, const char *name, napi_value item) { Check(napi_set_named_property(env, value, name, item)); }
napi_value Error(napi_env env, const std::string &code, const std::string &message) {
    napi_value error; Check(napi_create_error(env, nullptr, String(env, message), &error));
    Set(env, error, "code", String(env, code)); return error;
}
void RejectPromise(napi_env env, napi_deferred deferred, const std::string &code, const std::string &message) {
    try { Check(napi_reject_deferred(env, deferred, Error(env, code, message))); } catch (const std::exception &) {}
}
napi_value Value(napi_env env, const Event &event) {
    napi_value value; Check(napi_create_object(env, &value));
    Set(env, value, "type", String(env, event.type)); Set(env, value, "sessionId", String(env, event.sessionId));
    if (event.type == "host_call") {
        Set(env, value, "callId", String(env, event.callId.value()));
        Set(env, value, "toolName", String(env, event.toolName.value()));
        Set(env, value, "argsJson", String(env, event.argsJson.value()));
    } else {
        napi_value logs; Check(napi_create_array_with_length(env, event.logs.size(), &logs));
        for (size_t i = 0; i < event.logs.size(); ++i) Check(napi_set_element(env, logs, i, String(env, event.logs[i])));
        Set(env, value, "logs", logs);
        if (event.type == "finished") Set(env, value, "resultJson", String(env, event.resultJson.value()));
        else {
            Set(env, value, "errorCode", String(env, event.errorCode.value()));
            Set(env, value, "message", String(env, event.message.value()));
            napi_value abandoned; Check(napi_get_boolean(env, event.abandoned, &abandoned));
            Set(env, value, "abandoned", abandoned);
        }
    }
    return value;
}
void Finalize(napi_env, void *data, void *) { delete static_cast<std::shared_ptr<Completion> *>(data); }
void Deliver(napi_env env, napi_value, void *context, void *data) {
    auto *completion = static_cast<Completion *>(context);
    std::unique_ptr<Payload> payload(static_cast<Payload *>(data));
    if (!env || completion->environment->stopping) return;
    try {
        if (payload->rejectionCode.empty()) Check(napi_resolve_deferred(env, completion->deferred, Value(env, payload->event)));
        else RejectPromise(env, completion->deferred, payload->rejectionCode, payload->rejectionMessage);
    } catch (const std::exception &) { RejectPromise(env, completion->deferred, "runtime_error", "Cannot deliver plugin JavaScript event"); }
    { std::lock_guard<std::mutex> lock(completion->environment->mutex); completion->environment->pending.erase(completion); }
    napi_release_threadsafe_function(completion->function, napi_tsfn_release);
}
std::shared_ptr<Completion> MakeCompletion(napi_env env, const std::shared_ptr<Environment> &environment, napi_deferred deferred) {
    auto completion = std::make_shared<Completion>(); completion->environment = environment; completion->deferred = deferred;
    auto holder = std::make_unique<std::shared_ptr<Completion>>(completion);
    Check(napi_create_threadsafe_function(env, nullptr, nullptr, String(env, "AmberPluginJs"), 0, 2,
        holder.get(), Finalize, completion.get(), Deliver, &completion->function));
    holder.release();
    { std::lock_guard<std::mutex> lock(environment->mutex); environment->pending.emplace(completion.get(), completion); }
    return completion;
}
void Cleanup(void *data) {
    std::unique_ptr<std::shared_ptr<Environment>> holder(static_cast<std::shared_ptr<Environment> *>(data));
    auto environment = *holder; environment->stopping = true;
    // Close the host gate and release the owner completion before aborting env refs.
    // Runtime::Shutdown never waits for an uninterruptible JavaScript thread.
    environment->runtime.Shutdown();
    std::vector<std::shared_ptr<Completion>> pending;
    { std::lock_guard<std::mutex> lock(environment->mutex);
      for (auto &entry : environment->pending) pending.push_back(entry.second);
      environment->pending.clear(); }
    for (auto &completion : pending) napi_release_threadsafe_function(completion->function, napi_tsfn_abort);
}
std::string ReadString(napi_env env, napi_value value, size_t limit) {
    napi_valuetype type; size_t size = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok || size > limit)
        throw Exception("invalid_arguments", "Plugin JavaScript requires bounded string arguments");
    std::string text(size + 1, '\0'); size_t copied;
    Check(napi_get_value_string_utf8(env, value, text.data(), text.size(), &copied)); text.resize(copied); return text;
}
napi_value Property(napi_env env, napi_value value, const char *name) {
    napi_valuetype type; napi_value property;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_object ||
        napi_get_named_property(env, value, name, &property) != napi_ok)
        throw Exception("invalid_arguments", "Plugin JavaScript request must have typed fields");
    return property;
}
int Integer(napi_env env, napi_value value, int minimum, int maximum) {
    napi_valuetype type; double number;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_number ||
        napi_get_value_double(env, value, &number) != napi_ok || !std::isfinite(number) ||
        number < minimum || number > maximum || std::floor(number) != number)
        throw Exception("invalid_arguments", "Plugin JavaScript numeric limit is outside the supported range");
    return static_cast<int>(number);
}
struct Call {
    std::shared_ptr<Environment> environment;
    napi_value args[3] = {};
    Call(napi_env env, napi_callback_info info, size_t expected) {
        size_t count = 0; void *data = nullptr;
        Check(napi_get_cb_info(env, info, &count, nullptr, nullptr, &data));
        if (count != expected || !data) throw Exception("invalid_arguments", "Incorrect plugin JavaScript argument count");
        count = 3; Check(napi_get_cb_info(env, info, &count, args, nullptr, nullptr));
        environment = *static_cast<std::shared_ptr<Environment> *>(data);
        if (environment->stopping) throw Exception("runtime_closed", "Plugin JavaScript environment closed");
    }
};
enum class Operation { START, REPLY, REJECT };
napi_value Request(napi_env env, napi_callback_info info, Operation operation) {
    napi_value promise; napi_deferred deferred;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) return nullptr;
    std::shared_ptr<Completion> completion;
    try {
        Call call(env, info, operation == Operation::START ? 1 : 3);
        amber::pluginjs::Options options; std::string id, callId, body;
        if (operation == Operation::START) {
            options.executionId = ReadString(env, Property(env, call.args[0], "executionId"), 128);
            options.source = ReadString(env, Property(env, call.args[0], "source"), 256 * 1024);
            options.inputJson = ReadString(env, Property(env, call.args[0], "inputJson"), 64 * 1024);
            options.timeoutMs = Integer(env, Property(env, call.args[0], "timeoutMs"), 1000, 30000);
            options.maxOutputChars = Integer(env, Property(env, call.args[0], "maxOutputChars"), 1000, 32000);
            napi_value tools = Property(env, call.args[0], "hostTools"); bool array; uint32_t length;
            Check(napi_is_array(env, tools, &array));
            if (!array) throw Exception("invalid_arguments", "hostTools must be an array");
            Check(napi_get_array_length(env, tools, &length));
            if (length > 64) throw Exception("invalid_arguments", "At most 64 declared host tools are supported");
            for (uint32_t i = 0; i < length; ++i) {
                napi_value tool; Check(napi_get_element(env, tools, i, &tool)); options.hostTools.push_back(ReadString(env, tool, 128));
            }
        } else {
            id = ReadString(env, call.args[0], 128); callId = ReadString(env, call.args[1], 160);
            body = ReadString(env, call.args[2], operation == Operation::REPLY ? 64 * 1024 : 2048);
        }
        completion = MakeCompletion(env, call.environment, deferred);
        auto done = [completion](Event event) { completion->Post({std::move(event), {}, {}}); };
        if (operation == Operation::START) call.environment->runtime.Start(std::move(options), std::move(done));
        else if (operation == Operation::REPLY) call.environment->runtime.Reply(id, callId, std::move(body), std::move(done));
        else call.environment->runtime.Reject(id, callId, std::move(body), std::move(done));
    } catch (const Exception &error) {
        if (completion) completion->Post({{}, error.code, error.what()}); else RejectPromise(env, deferred, error.code, error.what());
    } catch (const std::exception &) {
        if (completion) completion->Post({{}, "runtime_error", "Plugin JavaScript request failed"});
        else RejectPromise(env, deferred, "runtime_error", "Plugin JavaScript request failed");
    }
    return promise;
}
napi_value Start(napi_env env, napi_callback_info info) { return Request(env, info, Operation::START); }
napi_value Reply(napi_env env, napi_callback_info info) { return Request(env, info, Operation::REPLY); }
napi_value Reject(napi_env env, napi_callback_info info) { return Request(env, info, Operation::REJECT); }
napi_value Cancel(napi_env env, napi_callback_info info) {
    try { Call call(env, info, 1); call.environment->runtime.Cancel(ReadString(env, call.args[0], 128));
        napi_value value; Check(napi_get_undefined(env, &value)); return value; }
    catch (const Exception &error) { napi_throw(env, Error(env, error.code, error.what())); }
    catch (const std::exception &) { napi_throw_error(env, "runtime_error", "Cannot cancel plugin JavaScript"); }
    return nullptr;
}
napi_value HasSession(napi_env env, napi_callback_info info) {
    try { Call call(env, info, 1); napi_value value;
        Check(napi_get_boolean(env, call.environment->runtime.HasSession(ReadString(env, call.args[0], 128)), &value)); return value; }
    catch (const Exception &error) { napi_throw(env, Error(env, error.code, error.what())); }
    catch (const std::exception &) { napi_throw_error(env, "runtime_error", "Cannot inspect plugin JavaScript session"); }
    return nullptr;
}
} // namespace
napi_value RegisterPluginJs(napi_env env, napi_value exports) {
    try {
        auto holder = std::make_unique<std::shared_ptr<Environment>>(std::make_shared<Environment>());
        napi_property_descriptor properties[] = {
            {"pluginJsStart", nullptr, Start, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pluginJsReply", nullptr, Reply, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pluginJsReject", nullptr, Reject, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pluginJsCancel", nullptr, Cancel, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"pluginJsHasSession", nullptr, HasSession, nullptr, nullptr, nullptr, napi_default, holder.get()}
        };
        Check(napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties));
        Check(napi_add_env_cleanup_hook(env, Cleanup, holder.get())); holder.release(); return exports;
    } catch (const std::exception &) { napi_throw_error(env, "runtime_error", "Cannot initialize plugin JavaScript module"); return nullptr; }
}
