#include "google_loopback_napi.h"
#include "google_loopback_core.h"
#include <atomic>
#include <cmath>
#include <unordered_map>
#include <vector>

namespace {
using amber::googleloopback::Exception;
using amber::googleloopback::Request;
using amber::googleloopback::Server;
void Check(napi_status status) {
    if (status != napi_ok) throw Exception("loopback_io_error", "Cannot create the native loopback result");
}
napi_value String(napi_env env, const std::string &text) {
    napi_value result = nullptr; Check(napi_create_string_utf8(env, text.data(), text.size(), &result)); return result;
}
napi_value Object(napi_env env) { napi_value result = nullptr; Check(napi_create_object(env, &result)); return result; }
void Set(napi_env env, napi_value object, const char *key, napi_value value) { Check(napi_set_named_property(env, object, key, value)); }
napi_value Undefined(napi_env env) { napi_value result = nullptr; Check(napi_get_undefined(env, &result)); return result; }
void Reject(napi_env env, napi_deferred deferred, const Exception &error) {
    napi_value value = nullptr; Check(napi_create_error(env, nullptr, String(env, error.what()), &value));
    Set(env, value, "code", String(env, error.code)); Check(napi_reject_deferred(env, deferred, value));
}
struct Channel {
    napi_threadsafe_function function = nullptr;
    std::atomic<bool> closed {false};
    void Post(Request request) {
        if (closed) return;
        auto data = std::make_unique<Request>(std::move(request));
        if (napi_call_threadsafe_function(function, data.get(), napi_tsfn_nonblocking) == napi_ok) data.release();
    }
};
struct Session { std::shared_ptr<Server> server; std::shared_ptr<Channel> channel; };
struct Environment {
    uint64_t nextHandle = 0;
    std::unordered_map<std::string, Session> sessions;
};
void Finalize(napi_env, void *data, void *) { delete static_cast<std::shared_ptr<Channel> *>(data); }
void Deliver(napi_env env, napi_value callback, void *context, void *data) {
    std::unique_ptr<Request> request(static_cast<Request *>(data));
    auto *channel = static_cast<Channel *>(context);
    if (!env || channel->closed) return;
    try {
        napi_value value = Object(env), errorCode = nullptr;
        Set(env, value, "connectionId", String(env, request->connectionId));
        Set(env, value, "requestTarget", String(env, request->requestTarget));
        if (request->errorCode.empty()) Check(napi_get_null(env, &errorCode));
        else errorCode = String(env, request->errorCode);
        Set(env, value, "errorCode", errorCode);
        napi_value ignored = nullptr;
        (void)napi_call_function(env, Undefined(env), callback, 1, &value, &ignored);
    } catch (const std::exception &) {}
}
std::shared_ptr<Channel> MakeChannel(napi_env env, napi_value callback) {
    auto channel = std::make_shared<Channel>();
    auto holder = std::make_unique<std::shared_ptr<Channel>>(channel);
    Check(napi_create_threadsafe_function(env, callback, nullptr, String(env, "AmberGoogleLoopback"), 8, 1,
        holder.get(), Finalize, channel.get(), Deliver, &channel->function));
    holder.release(); return channel;
}
void CloseSession(Session &session) {
    session.channel->closed = true;
    session.server->Close();
    napi_release_threadsafe_function(session.channel->function, napi_tsfn_abort);
}
void Cleanup(void *data) {
    std::unique_ptr<Environment> environment(static_cast<Environment *>(data));
    for (auto &entry : environment->sessions) CloseSession(entry.second);
    environment->sessions.clear();
}
std::string ReadString(napi_env env, napi_value value) {
    napi_valuetype type; size_t length = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok || length == 0 || length > 64) {
        throw Exception("invalid_arguments", "A loopback handle must be a bounded string");
    }
    std::string result(length + 1, '\0'); size_t copied = 0;
    Check(napi_get_value_string_utf8(env, value, result.data(), result.size(), &copied)); result.resize(copied); return result;
}
int Number(napi_env env, napi_value value, int minimum, int maximum) {
    napi_valuetype type; double number = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_number ||
        napi_get_value_double(env, value, &number) != napi_ok || !std::isfinite(number) ||
        std::floor(number) != number || number < minimum || number > maximum) {
        throw Exception("invalid_arguments", "The loopback number argument is out of range");
    }
    return static_cast<int>(number);
}
struct Call {
    std::vector<napi_value> args;
    Environment *environment = nullptr;
    Call(napi_env env, napi_callback_info info, size_t count) : args(count + 1, nullptr) {
        size_t actual = args.size(); void *data = nullptr;
        Check(napi_get_cb_info(env, info, &actual, args.data(), nullptr, &data));
        if (actual != count || !data) throw Exception("invalid_arguments", "Incorrect loopback arguments");
        environment = static_cast<Environment *>(data);
    }
};
template<class Action> napi_value Invoke(napi_env env, napi_callback_info info, size_t count, Action action) {
    napi_value promise = nullptr; napi_deferred deferred = nullptr;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) return nullptr;
    try { Call call(env, info, count); Check(napi_resolve_deferred(env, deferred, action(call))); }
    catch (const Exception &error) { Reject(env, deferred, error); }
    catch (const std::exception &) { Reject(env, deferred, Exception("loopback_io_error", "The native loopback operation failed")); }
    return promise;
}
napi_value Start(napi_env env, napi_callback_info info) {
    return Invoke(env, info, 2, [&](Call &call) {
        const int port = Number(env, call.args[0], 0, 65535);
        napi_valuetype type;
        if (napi_typeof(env, call.args[1], &type) != napi_ok || type != napi_function) {
            throw Exception("invalid_arguments", "The loopback callback must be a function");
        }
        auto channel = MakeChannel(env, call.args[1]);
        std::shared_ptr<Server> server;
        try { server = Server::Start(static_cast<uint16_t>(port), [channel](Request request) { channel->Post(std::move(request)); }); }
        catch (...) { channel->closed = true; napi_release_threadsafe_function(channel->function, napi_tsfn_abort); throw; }
        const auto id = "google-loopback-" + std::to_string(++call.environment->nextHandle);
        try {
            auto value = Object(env); napi_value actualPort = nullptr;
            Set(env, value, "handleId", String(env, id)); Check(napi_create_uint32(env, server->Port(), &actualPort));
            Set(env, value, "port", actualPort);
            call.environment->sessions.emplace(id, Session {server, channel});
            return value;
        } catch (...) {
            Session session {server, channel}; CloseSession(session); throw;
        }
    });
}
napi_value Reply(napi_env env, napi_callback_info info) {
    return Invoke(env, info, 3, [&](Call &call) {
        const auto id = ReadString(env, call.args[0]);
        const auto connectionId = ReadString(env, call.args[1]);
        const int status = Number(env, call.args[2], 200, 404);
        auto found = call.environment->sessions.find(id);
        if (found == call.environment->sessions.end()) throw Exception("loopback_closed", "The loopback listener is closed");
        found->second.server->Reply(connectionId, status); return Undefined(env);
    });
}
napi_value Close(napi_env env, napi_callback_info info) {
    return Invoke(env, info, 1, [&](Call &call) {
        const auto id = ReadString(env, call.args[0]);
        auto found = call.environment->sessions.find(id);
        if (found != call.environment->sessions.end()) { CloseSession(found->second); call.environment->sessions.erase(found); }
        return Undefined(env);
    });
}
} // namespace

napi_value RegisterGoogleLoopback(napi_env env, napi_value exports) {
    try {
        auto environment = std::make_unique<Environment>();
        napi_property_descriptor descriptors[] = {
            {"startGoogleLoopback", nullptr, Start, nullptr, nullptr, nullptr, napi_default, environment.get()},
            {"replyGoogleLoopback", nullptr, Reply, nullptr, nullptr, nullptr, napi_default, environment.get()},
            {"closeGoogleLoopback", nullptr, Close, nullptr, nullptr, nullptr, napi_default, environment.get()}
        };
        Check(napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors));
        Check(napi_add_env_cleanup_hook(env, Cleanup, environment.get())); environment.release(); return exports;
    } catch (const std::exception &) { napi_throw_error(env, "loopback_io_error", "Cannot initialize the native loopback module"); return nullptr; }
}
