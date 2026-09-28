#ifndef AMBER_MOSH_CORE_H
#define AMBER_MOSH_CORE_H
#include <functional>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>
namespace amber::mosh {
struct Error { std::string code; std::string message; };
struct Exception : std::runtime_error {
    Error error;
    Exception(const char *code, const char *message) : std::runtime_error(message), error {code, message} {}
};
struct Options {
    std::string peerAddress;
    int port = 0;
    std::string sessionKey;
    int columns = 80;
    int rows = 24;
    int connectTimeoutMs = 10000;
    ~Options();
    void ClearKey();
};
struct Handle { std::string id; std::string kind = "mosh"; };
struct Packet {
    std::string state = "running";
    std::vector<uint8_t> bytes;
    std::optional<std::string> errorCode;
    std::optional<int64_t> lastHeardMs;
};
struct Result { std::optional<Error> error; Handle handle; Packet packet; };
using Complete = std::function<void(Result)>;
// All environments share one process-wide Mosh owner: upstream timestamp/crypto/
// compressor caches are not thread-safe. Read only copies its synchronized cache.
class Runtime {
public:
    Runtime();
    ~Runtime();
    Runtime(const Runtime &) = delete;
    Runtime &operator=(const Runtime &) = delete;
    void Start(const std::string &requestId, Options options, Complete complete);
    void Cancel(const std::string &requestId);
    Packet Read(const Handle &handle, size_t maxBytes);
    void Write(const Handle &handle, std::vector<uint8_t> bytes, Complete complete);
    void Resize(const Handle &handle, int columns, int rows, Complete complete);
    void Close(const Handle &handle, const std::string &reason, Complete complete);
    void Shutdown();
    size_t HandleCount() const;
private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};
}
#endif
