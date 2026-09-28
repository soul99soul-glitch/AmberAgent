#include "plugin_js_core.h"
#include <ark_runtime/jsvm.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <unordered_set>

namespace amber::pluginjs {
namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t JSON_BYTES = 64 * 1024;
constexpr size_t LOG_BYTES = 8 * 1024;
struct Session;
struct ProcessSlot {
    std::mutex mutex;
    std::mutex vmCreation;
    bool initialized = false;
    std::shared_ptr<Session> active;
};
// Process-lifetime synchronization must outlive an abandoned, uninterruptible VM.
ProcessSlot &Slot() { static auto *slot = new ProcessSlot(); return *slot; }

// Capture JSON intrinsics before user code runs. Copy only actual JSON data;
// JSON.stringify alone silently drops undefined/functions and calls user toJSON.
constexpr const char *SERIALIZER = R"JS((() => {
  const stringify = JSON.stringify, ownKeys = Reflect.ownKeys;
  const descriptor = Object.getOwnPropertyDescriptor, prototype = Object.getPrototypeOf;
  const create = Object.create, setPrototype = Object.setPrototypeOf;
  const array = Array.isArray, finite = Number.isFinite, objectProto = Object.prototype;
  const SetType = Set, setHas = Set.prototype.has, setAdd = Set.prototype.add, setDelete = Set.prototype.delete;
  const apply = Reflect.apply, ErrorType = Error, StringType = String, hasOwn = Object.prototype.hasOwnProperty;
  return function(value, limit) {
    const seen = new SetType(); let nodes = 0;
    function copy(v, depth) {
      if (++nodes > 65536 || depth > 256) throw new ErrorType('json_limit');
      if (v === null || typeof v === 'boolean' || typeof v === 'string') return v;
      if (typeof v === 'number' && finite(v)) return v;
      if (typeof v !== 'object') throw new ErrorType('non_json_result');
      if (apply(setHas, seen, [v])) throw new ErrorType('non_json_result');
      apply(setAdd, seen, [v]);
      const isArray = array(v), proto = prototype(v);
      if (!isArray && proto !== null && proto !== objectProto) throw new ErrorType('non_json_result');
      const output = isArray ? [] : create(null);
      if (isArray) setPrototype(output, null);
      const keys = ownKeys(v);
      for (let index = 0; index < keys.length; index++) {
        const key = keys[index];
        const property = descriptor(v, key);
        if (!property || !property.enumerable) continue;
        if (typeof key !== 'string' || !apply(hasOwn, property, ['value'])) throw new ErrorType('non_json_result');
        if (isArray && (StringType(key >>> 0) !== key || (key >>> 0) >= v.length)) throw new ErrorType('non_json_result');
        if (!isArray) output[key] = copy(property.value, depth + 1);
      }
      if (isArray) {
        for (let i = 0; i < v.length; i++) {
          const property = descriptor(v, StringType(i));
          if (!property || !apply(hasOwn, property, ['value'])) throw new ErrorType('non_json_result');
          output[i] = copy(property.value, depth + 1);
        }
        output.length = v.length;
      }
      apply(setDelete, seen, [v]); return output;
    }
    const json = stringify(copy(value, 0));
    if (json.length > limit) throw new ErrorType('output_limit');
    return json;
  };
})())JS";

struct Session : std::enable_shared_from_this<Session> {
    explicit Session(Options request, Complete completion) : options(std::move(request)), done(std::move(completion)) {}
    Options options;
    std::mutex mutex;
    std::condition_variable changed;
    bool gateOpen = true;
    bool hostEnabled = true;
    bool ownerExited = false;
    bool paused = false;
    bool waiting = false;
    bool replyReady = false;
    uint64_t sequence = 0;
    std::string callId;
    std::string replyJson;
    Complete done;
    std::optional<Event> terminal;
    std::vector<std::string> logs;
    size_t logBytes = 0;
    bool logTruncated = false;
    Clock::duration used {};
    Clock::time_point activeSince = Clock::now();
    // Owner-only JSVM values; never read by NAPI/watchdog threads.
    JSVM_Env env = nullptr;
    JSVM_Value serializer = nullptr;

    Event Base(const char *kind) const {
        Event event; event.type = kind; event.sessionId = options.executionId; event.logs = logs; return event;
    }
    bool Open() { std::lock_guard<std::mutex> lock(mutex); return gateOpen; }
    bool HostOpen() { std::lock_guard<std::mutex> lock(mutex); return gateOpen && hostEnabled; }
    void SealHost() { std::lock_guard<std::mutex> lock(mutex); hostEnabled = false; }
    void Close(const std::string &code, const std::string &message) {
        Complete completion; Event event;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!gateOpen) return;
            gateOpen = false; event = Base("failed"); event.errorCode = code; event.message = message;
            event.abandoned = !ownerExited; terminal = event; completion = std::move(done);
        }
        changed.notify_all(); if (completion) completion(std::move(event));
    }
    void Watch() {
        std::unique_lock<std::mutex> lock(mutex);
        const auto budget = std::chrono::milliseconds(options.timeoutMs);
        while (gateOpen && !ownerExited) {
            if (paused) { changed.wait(lock, [this] { return !gateOpen || ownerExited || !paused; }); continue; }
            const auto remaining = budget - used - (Clock::now() - activeSince);
            if (remaining <= Clock::duration::zero()) {
                lock.unlock(); Close("timeout", "JavaScript execution budget exhausted"); return;
            }
            changed.wait_for(lock, remaining);
        }
    }
    void Finish(Event event) {
        Complete completion;
        {
            std::lock_guard<std::mutex> lock(mutex);
            ownerExited = true;
            if (gateOpen) {
                if (event.type == "finished" && used + (Clock::now() - activeSince) >= std::chrono::milliseconds(options.timeoutMs)) {
                    event.type = "failed"; event.resultJson.reset(); event.errorCode = "timeout";
                    event.message = "JavaScript execution budget exhausted";
                }
                gateOpen = false; event.logs = logs; terminal = event; completion = std::move(done);
            } else if (terminal) terminal->abandoned = false;
        }
        changed.notify_all();
        { std::lock_guard<std::mutex> lock(Slot().mutex); if (Slot().active.get() == this) Slot().active.reset(); }
        if (completion) completion(std::move(event));
    }
    std::string CallHost(const std::string &name, std::string args) {
        Complete completion; Event event;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!gateOpen || !hostEnabled) throw Exception("cancelled", "JavaScript host gate is closed");
            used += Clock::now() - activeSince;
            if (used >= std::chrono::milliseconds(options.timeoutMs)) throw Exception("timeout", "JavaScript execution budget exhausted");
            paused = true; waiting = true; replyReady = false;
            callId = options.executionId + ":" + std::to_string(++sequence);
            event = Base("host_call"); event.callId = callId; event.toolName = name; event.argsJson = std::move(args);
            completion = std::move(done);
        }
        changed.notify_all();
        if (!completion) throw Exception("runtime_error", "Missing JavaScript event receiver");
        completion(std::move(event));
        std::unique_lock<std::mutex> lock(mutex);
        changed.wait(lock, [this] { return !gateOpen || replyReady; });
        if (!gateOpen) throw Exception(terminal && terminal->errorCode ? *terminal->errorCode : "cancelled",
            "JavaScript host gate is closed");
        waiting = false; paused = false; activeSince = Clock::now();
        std::string result = std::move(replyJson); lock.unlock(); changed.notify_all(); return result;
    }
    void AddLog(std::string text) {
        std::lock_guard<std::mutex> lock(mutex);
        if (!gateOpen || !hostEnabled || logTruncated) return;
        if (logs.size() >= 128 || text.size() + logBytes > LOG_BYTES) {
            logTruncated = true; logs.emplace_back("[console log limit reached]"); return;
        }
        logBytes += text.size(); logs.push_back(std::move(text));
    }
};

void Check(JSVM_Status status, const char *code = "runtime_error") {
    if (status != JSVM_OK) throw Exception(code, "JavaScript VM operation failed");
}
JSVM_Value String(JSVM_Env env, const std::string &text) {
    JSVM_Value value; Check(OH_JSVM_CreateStringUtf8(env, text.data(), text.size(), &value)); return value;
}
std::string Text(JSVM_Env env, JSVM_Value value, size_t limit) {
    JSVM_ValueType type; size_t size = 0;
    Check(OH_JSVM_Typeof(env, value, &type));
    if (type != JSVM_STRING) throw Exception("non_json_result", "Expected a JSON string");
    Check(OH_JSVM_GetValueStringUtf8(env, value, nullptr, 0, &size));
    if (size > limit) throw Exception("output_limit", "JavaScript output exceeds its limit");
    std::string text(size + 1, '\0'); size_t copied;
    Check(OH_JSVM_GetValueStringUtf8(env, value, text.data(), text.size(), &copied)); text.resize(copied); return text;
}
JSVM_Value Evaluate(JSVM_Env env, const std::string &source, const char *code) {
    JSVM_Script script; bool cacheRejected = false; JSVM_Value result;
    Check(OH_JSVM_CompileScript(env, String(env, source), nullptr, 0, true, &cacheRejected, &script), code);
    Check(OH_JSVM_RunScript(env, script, &result), code); return result;
}
std::string Serialize(Session &session, JSVM_Value value, int limit) {
    bool promise = false; Check(OH_JSVM_IsPromise(session.env, value, &promise));
    if (promise) throw Exception("promise_not_supported", "Plugin scripts must return synchronous JSON");
    JSVM_Value maximum, receiver, result;
    Check(OH_JSVM_CreateInt32(session.env, limit, &maximum)); Check(OH_JSVM_GetUndefined(session.env, &receiver));
    JSVM_Value args[] = {value, maximum};
    Check(OH_JSVM_CallFunction(session.env, receiver, session.serializer, 2, args, &result), "non_json_result");
    return Text(session.env, result, static_cast<size_t>(limit) * 4);
}
void ThrowCallback(JSVM_Env env, const Exception &error) {
    bool pending = false;
    if (OH_JSVM_IsExceptionPending(env, &pending) == JSVM_OK && pending) return;
    OH_JSVM_ThrowError(env, error.code.c_str(), error.what());
}
struct ToolBinding { Session *session; std::string name; JSVM_CallbackStruct callback {}; };
JSVM_Value ToolCall(JSVM_Env env, JSVM_CallbackInfo info) {
    Session *owner = nullptr;
    try {
        size_t count = 0; void *data = nullptr;
        Check(OH_JSVM_GetCbInfo(env, info, &count, nullptr, nullptr, &data));
        if (count != 1 || !data) throw Exception("invalid_arguments", "Host tools require one JSON argument");
        auto &binding = *static_cast<ToolBinding *>(data);
        owner = binding.session;
        if (!binding.session->HostOpen()) throw Exception("cancelled", "JavaScript host gate is closed");
        JSVM_Value argument; count = 1;
        Check(OH_JSVM_GetCbInfo(env, info, &count, &argument, nullptr, nullptr));
        std::string args = Serialize(*binding.session, argument, static_cast<int>(JSON_BYTES / 4));
        std::string json = binding.session->CallHost(binding.name, std::move(args)); JSVM_Value result;
        Check(OH_JSVM_JsonParse(env, String(env, json), &result), "invalid_host_result"); return result;
    } catch (const Exception &error) {
        if (owner && error.code == "invalid_host_result") owner->Close(error.code, error.what());
        ThrowCallback(env, error);
    }
    catch (const std::exception &) { ThrowCallback(env, Exception("runtime_error", "JavaScript tool bridge failed")); }
    return nullptr;
}
JSVM_Value ConsoleLog(JSVM_Env env, JSVM_CallbackInfo info) {
    try {
        void *data; size_t count = 0; Check(OH_JSVM_GetCbInfo(env, info, &count, nullptr, nullptr, &data));
        if (!data || count > 32) throw Exception("output_limit", "Console accepts at most 32 arguments");
        auto &session = *static_cast<Session *>(data);
        if (!session.HostOpen()) throw Exception("cancelled", "JavaScript host gate is closed");
        std::vector<JSVM_Value> args(count); Check(OH_JSVM_GetCbInfo(env, info, &count, args.data(), nullptr, nullptr));
        std::string line;
        for (auto value : args) {
            JSVM_Value string; Check(OH_JSVM_CoerceToString(env, value, &string));
            if (!line.empty()) line += " ";
            line += Text(env, string, LOG_BYTES);
            if (line.size() > LOG_BYTES) throw Exception("output_limit", "Console line exceeds its limit");
        }
        session.AddLog(std::move(line)); JSVM_Value result; Check(OH_JSVM_GetUndefined(env, &result)); return result;
    } catch (const Exception &error) { ThrowCallback(env, error); }
    catch (const std::exception &) { ThrowCallback(env, Exception("runtime_error", "JavaScript console bridge failed")); }
    return nullptr;
}
// Reverse destruction stays on the owner, including every partial-init failure.
struct VM {
    JSVM_VM vm = nullptr; JSVM_VMScope vmScope = nullptr;
    JSVM_Env env = nullptr; JSVM_EnvScope envScope = nullptr; JSVM_HandleScope handles = nullptr;
    std::vector<ToolBinding> bindings;
    JSVM_CallbackStruct logCallback {};
    ~VM() {
        if (handles) OH_JSVM_CloseHandleScope(env, handles);
        if (envScope) OH_JSVM_CloseEnvScope(env, envScope);
        if (env) OH_JSVM_DestroyEnv(env);
        if (vmScope) OH_JSVM_CloseVMScope(vm, vmScope);
        if (vm) OH_JSVM_DestroyVM(vm);
    }
};
std::string ExceptionMessage(JSVM_Env env, const Exception &failure, std::string &code) {
    bool pending = false; JSVM_Value exception, message;
    if (OH_JSVM_IsExceptionPending(env, &pending) != JSVM_OK || !pending ||
        OH_JSVM_GetAndClearLastException(env, &exception) != JSVM_OK) return failure.what();
    JSVM_ValueType type;
    if (OH_JSVM_Typeof(env, exception, &type) == JSVM_OK && type == JSVM_STRING) {
        try { return Text(env, exception, 2048); } catch (const Exception &) { return failure.what(); }
    }
    // Read a string message only; never coerce the entire arbitrary thrown value.
    if (OH_JSVM_GetNamedProperty(env, exception, "message", &message) != JSVM_OK) return failure.what();
    try {
        std::string text = Text(env, message, 2048);
        if (text == "output_limit" || text == "json_limit" || text == "non_json_result") code = text;
        return text.empty() ? failure.what() : text;
    } catch (const Exception &) { return failure.what(); }
}
void Execute(const std::shared_ptr<Session> &session) {
    Event event; event.type = "failed"; event.sessionId = session->options.executionId;
    {
        VM runtime;
        try {
            if (!session->Open()) throw Exception("cancelled", "JavaScript host gate is closed");
            {
                std::lock_guard<std::mutex> lock(Slot().vmCreation);
                if (!Slot().initialized) {
                    auto status = OH_JSVM_Init(nullptr);
                    if (status != JSVM_OK && status != JSVM_GENERIC_FAILURE) Check(status);
                    Slot().initialized = true;
                }
                JSVM_CreateVMOptions options {};
                options.maxOldGenerationSize = 32 * 1024 * 1024;
                options.maxYoungGenerationSize = 8 * 1024 * 1024;
                options.initialOldGenerationSize = 4 * 1024 * 1024;
                options.initialYoungGenerationSize = 2 * 1024 * 1024;
                Check(OH_JSVM_CreateVM(&options, &runtime.vm));
            }
            Check(OH_JSVM_OpenVMScope(runtime.vm, &runtime.vmScope));
            Check(OH_JSVM_CreateEnv(runtime.vm, 0, nullptr, &runtime.env));
            Check(OH_JSVM_OpenEnvScope(runtime.env, &runtime.envScope));
            Check(OH_JSVM_OpenHandleScope(runtime.env, &runtime.handles));
            session->env = runtime.env; session->serializer = Evaluate(runtime.env, SERIALIZER, "runtime_error");
            JSVM_Value tools, console; Check(OH_JSVM_CreateObject(runtime.env, &tools));
            Check(OH_JSVM_CreateObject(runtime.env, &console));
            auto &bindings = runtime.bindings; bindings.reserve(session->options.hostTools.size());
            for (const auto &name : session->options.hostTools) {
                bindings.push_back({session.get(), name, {}}); auto &binding = bindings.back();
                binding.callback = {ToolCall, &binding}; JSVM_Value function;
                Check(OH_JSVM_CreateFunction(runtime.env, name.data(), name.size(), &binding.callback, &function));
                JSVM_PropertyDescriptor property {}; property.utf8name = name.c_str(); property.value = function;
                Check(OH_JSVM_DefineProperties(runtime.env, tools, 1, &property));
            }
            Check(OH_JSVM_ObjectFreeze(runtime.env, tools));
            runtime.logCallback = {ConsoleLog, session.get()}; JSVM_Value logFunction;
            Check(OH_JSVM_CreateFunction(runtime.env, "log", JSVM_AUTO_LENGTH, &runtime.logCallback, &logFunction));
            for (const char *name : {"log", "info", "warn", "error"}) Check(OH_JSVM_SetNamedProperty(runtime.env, console, name, logFunction));
            Check(OH_JSVM_ObjectFreeze(runtime.env, console));
            JSVM_Value input; Check(OH_JSVM_JsonParse(runtime.env, String(runtime.env, session->options.inputJson), &input), "invalid_arguments");
            JSVM_Value function = Evaluate(runtime.env,
                "(function(input, tools, console) { 'use strict';\n" + session->options.source + "\n})", "script_error");
            if (!session->Open()) throw Exception("cancelled", "JavaScript host gate is closed");
            JSVM_Value receiver, result; Check(OH_JSVM_GetUndefined(runtime.env, &receiver));
            JSVM_Value args[] = {input, tools, console};
            Check(OH_JSVM_CallFunction(runtime.env, receiver, function, 3, args, &result), "script_error");
            event.resultJson = Serialize(*session, result, session->options.maxOutputChars); event.type = "finished";
        } catch (const Exception &error) {
            session->SealHost();
            std::string code = error.code;
            event.message = runtime.env && runtime.handles ? ExceptionMessage(runtime.env, error, code) : error.what();
            event.errorCode = code;
        } catch (const std::exception &) { event.errorCode = "runtime_error"; event.message = "JavaScript native runtime failed"; }
        session->SealHost();
    }
    session->env = nullptr; session->serializer = nullptr; session->Finish(std::move(event));
}
bool Identifier(const std::string &name) {
    if (name.empty() || name.size() > 128 || name.find('\0') != std::string::npos) return false;
    for (unsigned char c : name) if (c < 32 || c == 127) return false;
    return true;
}
void Validate(const Options &options) {
    if (!Identifier(options.executionId) || options.source.empty() || options.source.size() > 256 * 1024 ||
        options.inputJson.empty() || options.inputJson.size() > JSON_BYTES || options.timeoutMs < 1000 ||
        options.timeoutMs > 30000 || options.maxOutputChars < 1000 || options.maxOutputChars > 32000 ||
        options.hostTools.size() > 64) throw Exception("invalid_arguments", "Invalid plugin JavaScript request");
    std::unordered_set<std::string> names;
    for (const auto &name : options.hostTools) {
        if (name.empty() || name.size() > 128 || !names.emplace(name).second)
            throw Exception("invalid_arguments", "Invalid declared tool name");
        for (unsigned char c : name) if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '_')) throw Exception("invalid_arguments", "Invalid declared tool name");
    }
}
} // namespace
struct Runtime::Impl { mutable std::mutex mutex; bool stopping = false; std::shared_ptr<Session> session; };
Runtime::Runtime() : impl_(std::make_shared<Impl>()) {}
Runtime::~Runtime() { Shutdown(); }
void Runtime::Start(Options options, Complete done) {
    Validate(options); auto session = std::make_shared<Session>(std::move(options), std::move(done));
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->stopping) throw Exception("runtime_closed", "Plugin JavaScript environment closed");
        std::lock_guard<std::mutex> slotLock(Slot().mutex);
        if (Slot().active) throw Exception("runtime_busy", "Previous JavaScript VM has not exited");
        Slot().active = session; impl_->session = session;
    }
    bool ownerStarted = false;
    try {
        std::thread owner([session] { Execute(session); }); owner.detach();
        ownerStarted = true;
        std::thread watcher([session] { session->Watch(); }); watcher.detach();
    } catch (const std::exception &) {
        if (ownerStarted) session->Close("runtime_error", "Cannot create JavaScript budget watchdog");
        else {
            Event event; event.type = "failed"; event.sessionId = session->options.executionId;
            event.errorCode = "runtime_error"; event.message = "Cannot create JavaScript owner thread";
            session->Finish(std::move(event));
        }
        throw Exception("runtime_error", "Cannot create JavaScript native thread");
    }
}
void Runtime::Reply(const std::string &id, const std::string &call, std::string json, Complete done) {
    if (json.empty() || json.size() > JSON_BYTES) throw Exception("invalid_host_result", "Host result exceeds JSON limit");
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(impl_->mutex); session = impl_->session; }
    if (!session || session->options.executionId != id) throw Exception("session_not_found", "JavaScript session is absent");
    std::optional<Event> terminal;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->gateOpen) terminal = session->terminal;
        else {
            if (!session->waiting || session->replyReady || session->callId != call || session->done)
                throw Exception("stale_host_call", "JavaScript host call is not pending");
            session->done = std::move(done); session->replyJson = std::move(json); session->replyReady = true;
        }
    }
    session->changed.notify_all(); if (terminal) done(std::move(*terminal));
}
void Runtime::Reject(const std::string &id, const std::string &call, std::string reason, Complete done) {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(impl_->mutex); session = impl_->session; }
    if (!session || session->options.executionId != id) throw Exception("session_not_found", "JavaScript session is absent");
    std::optional<Event> terminal;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->gateOpen) terminal = session->terminal;
        else {
            if (!session->waiting || session->replyReady || session->callId != call || session->done)
                throw Exception("stale_host_call", "JavaScript host call is not pending");
            session->done = std::move(done);
        }
    }
    if (terminal) done(std::move(*terminal));
    else session->Close("host_rejected", reason.empty() ? "Host capability denied" : reason.substr(0, 2048));
}
void Runtime::Cancel(const std::string &id) {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(impl_->mutex); session = impl_->session; }
    if (session && session->options.executionId == id) session->Close("cancelled", "Plugin JavaScript execution cancelled");
}
bool Runtime::HasSession(const std::string &id) const {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(impl_->mutex); session = impl_->session; }
    if (!session || session->options.executionId != id) return false;
    std::lock_guard<std::mutex> lock(session->mutex); return session->gateOpen && !session->ownerExited;
}
void Runtime::Shutdown() {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(impl_->mutex); impl_->stopping = true; session = impl_->session; impl_->session.reset(); }
    if (session) session->Close("cancelled", "Plugin JavaScript environment closed");
}
} // namespace amber::pluginjs
