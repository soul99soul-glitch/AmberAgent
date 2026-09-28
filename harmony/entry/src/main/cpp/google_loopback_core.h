#ifndef AMBER_GOOGLE_LOOPBACK_CORE_H
#define AMBER_GOOGLE_LOOPBACK_CORE_H
#include <cstdint>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>

namespace amber::googleloopback {
struct Exception : std::runtime_error {
    std::string code;
    Exception(std::string value, std::string message) : std::runtime_error(std::move(message)), code(std::move(value)) {}
};
struct Request {
    std::string connectionId;
    std::string requestTarget;
    std::string errorCode;
};
using Callback = std::function<void(Request)>;

// A single local OAuth listener; only the ArkTS client validates OAuth path/state/code.
class Server {
public:
    static std::shared_ptr<Server> Start(uint16_t port, Callback callback);
    ~Server();
    Server(const Server &) = delete;
    Server &operator=(const Server &) = delete;
    uint16_t Port() const;
    void Reply(const std::string &connectionId, int status);
    void Close();
private:
    Server();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace amber::googleloopback
#endif
