#ifndef AMBER_SSH_CORE_H
#define AMBER_SSH_CORE_H

#include <chrono>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <stdexcept>
#include <vector>

namespace amber::ssh {
struct Error {
    std::string code;
    std::string message;
};
struct Exception : std::runtime_error {
    Error error;
    Exception(const char *code, const char *message) : std::runtime_error(message), error {code, message} {}
};
struct Endpoint {
    std::string host;
    int port = 22;
    int timeoutMs = 10000;
};
struct ConnectionOptions : Endpoint {
    std::string username;
    std::string fingerprint;
    std::string authMethod;
    std::string secret;
    std::optional<std::string> passphrase;
    ~ConnectionOptions() { ClearSecrets(); }
    void ClearSecrets();
};
struct StartOptions {
    std::string kind;
    std::string command;
    int timeoutMs = 10000;
    std::string term = "xterm-256color";
    int columns = 80;
    int rows = 24;
};
struct Handle {
    std::string id;
    std::string kind;
    std::string peerAddress;
};
struct Chunk {
    std::vector<uint8_t> bytes;
    bool isStderr = false;
};
struct Packet {
    std::vector<Chunk> chunks;
    std::string state = "running";
    std::optional<int64_t> exitCode;
    std::optional<Error> error;
};
struct Result {
    std::optional<Error> error;
    std::string fingerprint;
    std::string hostKeyType;
    Handle handle;
    Packet packet;
};
using Complete = std::function<void(Result)>;

// One serial libssh2 owner per connection. Public methods only enqueue or copy.
class Runtime {
public:
    Runtime();
    ~Runtime();
    Runtime(const Runtime &) = delete;
    Runtime &operator=(const Runtime &) = delete;
    void Probe(const std::string &requestId, Endpoint options, Complete complete);
    void Start(const std::string &requestId, ConnectionOptions options,
        StartOptions start, Complete complete);
    void Cancel(const std::string &requestId);
    Packet Read(const Handle &handle, size_t maxBytes);
    void Write(const Handle &handle, std::vector<uint8_t> bytes, Complete complete);
    void Resize(const Handle &handle, int columns, int rows, Complete complete);
    void Close(const Handle &handle, const std::string &reason, Complete complete);
    void Shutdown();
    size_t HandleCount() const;
    size_t OwnerCount() const;
private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};
} // namespace amber::ssh
#endif
