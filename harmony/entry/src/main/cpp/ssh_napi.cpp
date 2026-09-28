#include "ssh_napi.h"
#include "ssh_core.h"
#include <cmath>
#include <cstring>
#include <atomic>
#include <mutex>
#include <unordered_map>

namespace {
using amber::ssh::ConnectionOptions;
using amber::ssh::Endpoint;
using amber::ssh::Error;
using amber::ssh::Exception;
using amber::ssh::Handle;
using amber::ssh::Packet;
using amber::ssh::Result;
using amber::ssh::Runtime;
using amber::ssh::StartOptions;
struct Completion;
struct Environment {
    Runtime runtime;
    std::atomic<bool> stopping {false};
    std::mutex mutex;
    std::unordered_map<Completion *, std::shared_ptr<Completion>> pending;
};
struct Completion {
    enum Kind { PROBE, START, VOID, CLOSE } kind;
    napi_deferred deferred = nullptr;
    napi_threadsafe_function function = nullptr;
    std::shared_ptr<Environment> environment;
    std::atomic<bool> posted {false};
    void Post(Result result) {
        if (posted.exchange(true)) return;
        if (!environment->stopping) {
            auto payload = std::make_unique<Result>(std::move(result));
            if (napi_call_threadsafe_function(function, payload.get(), napi_tsfn_nonblocking) == napi_ok) payload.release();
        }
        // The owner holds one reference; the env cleanup or JS callback holds the other.
        napi_release_threadsafe_function(function, napi_tsfn_release);
    }
};
void Check(napi_status status) {
    if (status != napi_ok) throw Exception("network_error", "Cannot create SSH native result");
}
napi_value String(napi_env env, const std::string &text) {
    napi_value result = nullptr; Check(napi_create_string_utf8(env, text.data(), text.size(), &result)); return result;
}
napi_value Null(napi_env env) { napi_value result = nullptr; Check(napi_get_null(env, &result)); return result; }
napi_value Object(napi_env env) { napi_value result = nullptr; Check(napi_create_object(env, &result)); return result; }
void Set(napi_env env, napi_value object, const char *key, napi_value value) { Check(napi_set_named_property(env, object, key, value)); }
napi_value NativeError(napi_env env, const Error &error) {
    napi_value result = nullptr; Check(napi_create_error(env, nullptr, String(env, error.message), &result));
    Set(env, result, "code", String(env, error.code)); return result;
}
void Reject(napi_env env, napi_deferred deferred, const Error &error) {
    try { napi_reject_deferred(env, deferred, NativeError(env, error)); } catch (const std::exception &) {}
}
napi_value PacketValue(napi_env env, const Packet &packet) {
    auto result = Object(env); napi_value chunks = nullptr;
    Check(napi_create_array_with_length(env, packet.chunks.size(), &chunks));
    for (size_t i = 0; i < packet.chunks.size(); ++i) {
        auto chunk = Object(env); napi_value buffer = nullptr; void *data = nullptr;
        Check(napi_create_arraybuffer(env, packet.chunks[i].bytes.size(), &data, &buffer));
        if (!packet.chunks[i].bytes.empty()) std::memcpy(data, packet.chunks[i].bytes.data(), packet.chunks[i].bytes.size());
        napi_value bytes = nullptr; Check(napi_create_typedarray(env, napi_uint8_array, packet.chunks[i].bytes.size(), buffer, 0, &bytes));
        Set(env, chunk, "bytes", bytes);
        napi_value stderrValue = nullptr; Check(napi_get_boolean(env, packet.chunks[i].isStderr, &stderrValue));
        Set(env, chunk, "isStderr", stderrValue); Check(napi_set_element(env, chunks, static_cast<uint32_t>(i), chunk));
    }
    Set(env, result, "chunks", chunks); Set(env, result, "state", String(env, packet.state));
    napi_value exit = Null(env);
    if (packet.exitCode) Check(napi_create_int64(env, *packet.exitCode, &exit));
    Set(env, result, "exitCode", exit);
    Set(env, result, "errorCode", packet.error ? String(env, packet.error->code) : Null(env));
    Set(env, result, "errorMessage", packet.error ? String(env, packet.error->message) : Null(env));
    return result;
}
void Finalize(napi_env, void *data, void *) { delete static_cast<std::shared_ptr<Completion> *>(data); }
void Deliver(napi_env env, napi_value, void *context, void *data) {
    auto *completion = static_cast<Completion *>(context);
    std::unique_ptr<Result> result(static_cast<Result *>(data));
    if (!env || completion->environment->stopping) return;
    try {
        if (result->error) Reject(env, completion->deferred, *result->error);
        else {
            napi_value value = nullptr;
            if (completion->kind == Completion::PROBE) {
                value = Object(env); Set(env, value, "fingerprintSHA256", String(env, result->fingerprint));
                Set(env, value, "hostKeyType", String(env, result->hostKeyType));
            } else if (completion->kind == Completion::START) {
                value = Object(env); Set(env, value, "id", String(env, result->handle.id));
                Set(env, value, "kind", String(env, result->handle.kind));
                Set(env, value, "peerAddress", String(env, result->handle.peerAddress));
            } else if (completion->kind == Completion::CLOSE) value = PacketValue(env, result->packet);
            else Check(napi_get_undefined(env, &value));
            Check(napi_resolve_deferred(env, completion->deferred, value));
        }
    } catch (const Exception &error) { Reject(env, completion->deferred, error.error); }
    { std::lock_guard<std::mutex> lock(completion->environment->mutex); completion->environment->pending.erase(completion); }
    napi_release_threadsafe_function(completion->function, napi_tsfn_release);
}
std::shared_ptr<Completion> MakeCompletion(napi_env env, const std::shared_ptr<Environment> &environment,
    napi_deferred deferred, Completion::Kind kind) {
    auto completion = std::make_shared<Completion>(); completion->kind = kind;
    completion->deferred = deferred; completion->environment = environment;
    auto holder = std::make_unique<std::shared_ptr<Completion>>(completion);
    Check(napi_create_threadsafe_function(env, nullptr, nullptr, String(env, "AmberSSH"), 0, 2,
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
std::string ReadString(napi_env env, napi_value value, size_t max = 65536) {
    napi_valuetype type; size_t length = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok || length > max)
        throw Exception("invalid_arguments", "SSH argument must be a bounded string");
    std::string result(length + 1, '\0'); size_t copied = 0;
    Check(napi_get_value_string_utf8(env, value, result.data(), result.size(), &copied)); result.resize(copied); return result;
}
napi_value Property(napi_env env, napi_value object, const char *key) {
    napi_valuetype type; napi_value result = nullptr;
    if (napi_typeof(env, object, &type) != napi_ok || type != napi_object ||
        napi_get_named_property(env, object, key, &result) != napi_ok)
        throw Exception("invalid_arguments", "SSH options must have the required typed properties");
    return result;
}
std::string Field(napi_env env, napi_value object, const char *key, size_t max = 65536) {
    return ReadString(env, Property(env, object, key), max);
}
int Number(napi_env env, napi_value value, int min, int max) {
    napi_valuetype type; double number = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_number ||
        napi_get_value_double(env, value, &number) != napi_ok || !std::isfinite(number) ||
        std::floor(number) != number || number < min || number > max)
        throw Exception("invalid_arguments", "SSH number argument is out of range");
    return static_cast<int>(number);
}
int NumericField(napi_env env, napi_value object, const char *key, int min, int max) {
    return Number(env, Property(env, object, key), min, max);
}
Endpoint ReadEndpoint(napi_env env, napi_value object, const char *timeoutKey) {
    Endpoint options; options.host = Field(env, object, "host", 1024);
    options.port = NumericField(env, object, "port", 1, 65535);
    options.timeoutMs = NumericField(env, object, timeoutKey, 1, 300000); return options;
}
ConnectionOptions ReadConnection(napi_env env, napi_value object) {
    ConnectionOptions options; static_cast<Endpoint &>(options) = ReadEndpoint(env, object, "connectTimeoutMs");
    options.username = Field(env, object, "username", 256);
    options.fingerprint = Field(env, object, "expectedFingerprintSHA256", 128);
    options.authMethod = Field(env, object, "authMethod", 16); options.secret = Field(env, object, "secret");
    auto passphrase = Property(env, object, "passphrase"); napi_valuetype type; Check(napi_typeof(env, passphrase, &type));
    if (type == napi_string) options.passphrase = ReadString(env, passphrase);
    else if (type != napi_null) throw Exception("invalid_arguments", "SSH passphrase must be string or null");
    return options;
}
Handle ReadHandle(napi_env env, napi_value object) {
    return {Field(env, object, "id", 256), Field(env, object, "kind", 16), ""};
}
struct Call {
    std::vector<napi_value> args;
    std::shared_ptr<Environment> environment;
    Call(napi_env env, napi_callback_info info, size_t count) : args(count + 1, nullptr) {
        size_t actual = args.size(); void *data = nullptr;
        Check(napi_get_cb_info(env, info, &actual, args.data(), nullptr, &data));
        if (actual != count) throw Exception("invalid_arguments", "Incorrect SSH argument count");
        if (!data) throw Exception("network_error", "SSH native environment unavailable");
        environment = *static_cast<std::shared_ptr<Environment> *>(data);
        if (environment->stopping) throw Exception("network_error", "SSH native environment closed");
    }
};
template<class Action> napi_value Async(napi_env env, napi_callback_info info, size_t count, Completion::Kind kind, Action action) {
    napi_value promise = nullptr; napi_deferred deferred = nullptr;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) return nullptr;
    std::shared_ptr<Completion> completion;
    try {
        Call call(env, info, count);
        // Register TSFN before launching the owner; request is synchronously registered by runtime before return.
        completion = MakeCompletion(env, call.environment, deferred, kind);
        action(call, [completion](Result result) { completion->Post(std::move(result)); });
    } catch (const Exception &error) {
        if (completion) { Result result; result.error = error.error; completion->Post(std::move(result)); }
        else Reject(env, deferred, error.error);
    } catch (const std::exception &) {
        Error error {"network_error", "Cannot allocate SSH native request"};
        if (completion) { Result result; result.error = error; completion->Post(std::move(result)); }
        else Reject(env, deferred, error);
    }
    return promise;
}
napi_value Probe(napi_env env, napi_callback_info info) {
    return Async(env, info, 2, Completion::PROBE, [&](Call &call, amber::ssh::Complete done) {
        auto id = ReadString(env, call.args[0], 256); auto options = ReadEndpoint(env, call.args[1], "timeoutMs");
        call.environment->runtime.Probe(id, std::move(options), std::move(done));
    });
}
napi_value Start(napi_env env, napi_callback_info info, const char *kind) {
    return Async(env, info, 3, Completion::START, [&](Call &call, amber::ssh::Complete done) {
        auto id = ReadString(env, call.args[0], 256); auto connection = ReadConnection(env, call.args[1]);
        StartOptions options; options.kind = kind;
        if (options.kind == "exec") {
            options.command = Field(env, call.args[2], "command"); options.timeoutMs = NumericField(env, call.args[2], "timeoutMs", 1, 86400000);
        } else {
            options.term = Field(env, call.args[2], "term", 128); options.columns = NumericField(env, call.args[2], "columns", 1, 1000);
            options.rows = NumericField(env, call.args[2], "rows", 1, 1000);
        }
        call.environment->runtime.Start(id, connection, std::move(options), std::move(done));
    });
}
napi_value StartExec(napi_env env, napi_callback_info info) { return Start(env, info, "exec"); }
napi_value StartPty(napi_env env, napi_callback_info info) { return Start(env, info, "pty"); }
napi_value Cancel(napi_env env, napi_callback_info info) {
    return Async(env, info, 1, Completion::VOID, [&](Call &call, amber::ssh::Complete done) {
        call.environment->runtime.Cancel(ReadString(env, call.args[0], 256)); done({});
    });
}
napi_value Read(napi_env env, napi_callback_info info) {
    try {
        Call call(env, info, 2); auto handle = ReadHandle(env, call.args[0]);
        int maxBytes = Number(env, call.args[1], 1, 1048576);
        return PacketValue(env, call.environment->runtime.Read(handle, static_cast<size_t>(maxBytes)));
    } catch (const Exception &error) { napi_throw(env, NativeError(env, error.error)); }
    catch (const std::exception &) { napi_throw_error(env, "network_error", "Cannot allocate SSH output packet"); }
    return nullptr;
}
napi_value Write(napi_env env, napi_callback_info info) {
    return Async(env, info, 2, Completion::VOID, [&](Call &call, amber::ssh::Complete done) {
        auto handle = ReadHandle(env, call.args[0]);
        bool typed = false; napi_typedarray_type type; size_t length = 0; void *data = nullptr; napi_value buffer = nullptr; size_t offset = 0;
        if (napi_is_typedarray(env, call.args[1], &typed) != napi_ok || !typed ||
            napi_get_typedarray_info(env, call.args[1], &type, &length, &data, &buffer, &offset) != napi_ok ||
            type != napi_uint8_array || length > 1048576 || (length && !data))
            throw Exception("invalid_arguments", "SSH PTY input must be Uint8Array of at most 1048576 bytes");
        std::vector<uint8_t> bytes(length); if (length) std::memcpy(bytes.data(), data, length);
        call.environment->runtime.Write(handle, std::move(bytes), std::move(done));
    });
}
napi_value Resize(napi_env env, napi_callback_info info) {
    return Async(env, info, 3, Completion::VOID, [&](Call &call, amber::ssh::Complete done) {
        auto handle = ReadHandle(env, call.args[0]);
        int columns = Number(env, call.args[1], 1, 1000); int rows = Number(env, call.args[2], 1, 1000);
        call.environment->runtime.Resize(handle, columns, rows, std::move(done));
    });
}
napi_value Close(napi_env env, napi_callback_info info) {
    return Async(env, info, 2, Completion::CLOSE, [&](Call &call, amber::ssh::Complete done) {
        auto handle = ReadHandle(env, call.args[0]); auto reason = ReadString(env, call.args[1], 32);
        call.environment->runtime.Close(handle, reason, std::move(done));
    });
}
} // namespace
napi_value RegisterSSH(napi_env env, napi_value exports) {
    try {
        auto holder = std::make_unique<std::shared_ptr<Environment>>(std::make_shared<Environment>());
        napi_property_descriptor descriptors[] = {
            {"sshProbe", nullptr, Probe, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshStartExec", nullptr, StartExec, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshStartPty", nullptr, StartPty, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshCancel", nullptr, Cancel, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshRead", nullptr, Read, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshWrite", nullptr, Write, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshResize", nullptr, Resize, nullptr, nullptr, nullptr, napi_default, holder.get()},
            {"sshClose", nullptr, Close, nullptr, nullptr, nullptr, napi_default, holder.get()}
        };
        Check(napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors));
        Check(napi_add_env_cleanup_hook(env, Cleanup, holder.get())); holder.release();
        return exports;
    } catch (const std::exception &) { napi_throw_error(env, "network_error", "Cannot initialize SSH native module"); return nullptr; }
}
