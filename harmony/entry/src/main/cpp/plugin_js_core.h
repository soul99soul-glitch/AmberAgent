#ifndef AMBER_PLUGIN_JS_CORE_H
#define AMBER_PLUGIN_JS_CORE_H
#include <functional>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>
namespace amber::pluginjs {
struct Exception : std::runtime_error {
    std::string code;
    Exception(std::string value, std::string message) : std::runtime_error(std::move(message)), code(std::move(value)) {}
};
struct Options {
    std::string executionId;
    std::string source;
    std::string inputJson;
    std::vector<std::string> hostTools;
    int timeoutMs = 10000;
    int maxOutputChars = 10000;
};
struct Event {
    std::string type;
    std::string sessionId;
    std::optional<std::string> callId;
    std::optional<std::string> toolName;
    std::optional<std::string> argsJson;
    std::optional<std::string> resultJson;
    std::optional<std::string> errorCode;
    std::optional<std::string> message;
    std::vector<std::string> logs;
    bool abandoned = false;
};
using Complete = std::function<void(Event)>;
// Every execution owns a fresh JSVM on its native thread. A timed-out VM keeps
// the process-wide slot until that same thread can destroy it; cleanup never joins it.
class Runtime {
public:
    Runtime();
    ~Runtime();
    Runtime(const Runtime &) = delete;
    Runtime &operator=(const Runtime &) = delete;
    void Start(Options options, Complete done);
    void Reply(const std::string &sessionId, const std::string &callId, std::string resultJson, Complete done);
    void Reject(const std::string &sessionId, const std::string &callId, std::string reason, Complete done);
    void Cancel(const std::string &sessionId);
    bool HasSession(const std::string &sessionId) const;
    void Shutdown();
private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};
} // namespace amber::pluginjs
#endif
