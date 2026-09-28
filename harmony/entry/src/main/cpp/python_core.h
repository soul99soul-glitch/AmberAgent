#ifndef AMBER_PYTHON_CORE_H
#define AMBER_PYTHON_CORE_H
#include <functional>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
namespace amber::python {
struct Exception : std::runtime_error {
    std::string code;
    Exception(const char *value, const char *message) : std::runtime_error(message), code(value) {}
};
struct Options {
    std::string source;
    std::string stdinText;
    std::string resourceRoot;
    int timeoutMs = 15000;
};
struct Result {
    std::string status = "failed";
    std::optional<int> exitCode;
    std::string stdoutText;
    std::string stderrText;
    std::optional<std::string> errorCode;
};
using Complete = std::function<void(Result)>;
// All Runtime instances share one CPython owner; no Python code runs on NAPI threads.
class Runtime {
public:
    Runtime();
    ~Runtime();
    Runtime(const Runtime &) = delete;
    Runtime &operator=(const Runtime &) = delete;
    void Execute(const std::string &requestId, Options options, Complete done);
    void Cancel(const std::string &requestId);
    void Shutdown();
    size_t PendingCount() const;
private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};
std::string Version();
} // namespace amber::python
#endif
