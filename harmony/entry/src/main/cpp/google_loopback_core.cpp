#include "google_loopback_core.h"
#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <mutex>
#include <poll.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace amber::googleloopback {
namespace {
constexpr size_t MAX_HEADER = 8192;
constexpr size_t MAX_CONNECTIONS = 4;
constexpr auto CONNECTION_TIMEOUT = std::chrono::seconds(15);
void CloseSocket(int &fd) {
    if (fd < 0) return;
    shutdown(fd, SHUT_RDWR); close(fd); fd = -1;
}
void Nonblocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 || fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) {
        throw Exception("loopback_io_error", "Cannot configure the loopback socket");
    }
}
std::string Response(int status) {
    const char *reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
    const std::string body = status == 200
        ? "<!doctype html><meta charset=utf-8><p>Sign-in response received. You can return to Amber.</p>"
        : "<!doctype html><meta charset=utf-8><p>Sign-in request was not accepted.</p>";
    return "HTTP/1.1 " + std::to_string(status) + " " + reason + "\r\nContent-Type: text/html; charset=utf-8"
        "\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + std::to_string(body.size()) + "\r\n\r\n" + body;
}
bool SendResponse(int fd, int status) {
    const auto response = Response(status);
#ifdef MSG_NOSIGNAL
    constexpr int flags = MSG_NOSIGNAL;
#else
    constexpr int flags = 0;
#endif
    return send(fd, response.data(), response.size(), flags) == static_cast<ssize_t>(response.size());
}
std::string GetTarget(const std::string &header) {
    const auto end = header.find("\r\n");
    if (end == std::string::npos || header.compare(0, 4, "GET ") != 0) return {};
    const auto space = header.find(' ', 4);
    if (space == std::string::npos || space >= end) return {};
    const auto version = header.substr(space + 1, end - space - 1);
    if (version != "HTTP/1.1" && version != "HTTP/1.0") return {};
    const auto target = header.substr(4, space - 4);
    if (target.empty() || target[0] != '/') return {};
    for (const unsigned char character : target) if (character <= 32 || character == 127) return {};
    return target;
}
} // namespace

struct Server::Impl {
    struct Connection {
        int fd;
        std::string header;
        std::chrono::steady_clock::time_point deadline;
        bool pending = false;
    };
    int listener = -1;
    int wake[2] = {-1, -1};
    uint16_t port = 0;
    uint64_t nextConnection = 0;
    std::atomic<bool> stopping {false};
    std::mutex stopMutex;
    std::mutex mutex;
    std::unordered_map<std::string, Connection> connections;
    Callback callback;
    std::thread worker;

    void Open(uint16_t requestedPort, Callback handler) {
        callback = std::move(handler);
        listener = socket(AF_INET, SOCK_STREAM, 0);
        if (listener < 0) throw Exception("loopback_io_error", "Cannot open the loopback socket");
        Nonblocking(listener);
        int reuse = 1;
        if (setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) < 0) {
            throw Exception("loopback_io_error", "Cannot configure the loopback listener");
        }
        sockaddr_in address {};
        address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = htons(requestedPort);
        if (bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) < 0) {
            throw Exception("loopback_bind_failed", "The registered loopback port is unavailable");
        }
        socklen_t length = sizeof(address);
        if (getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) < 0 || listen(listener, 4) < 0) {
            throw Exception("loopback_io_error", "Cannot start the loopback listener");
        }
        port = ntohs(address.sin_port);
        if (pipe(wake) < 0) throw Exception("loopback_io_error", "Cannot create loopback cancellation");
        Nonblocking(wake[0]); Nonblocking(wake[1]);
        worker = std::thread([this] { Run(); });
    }
    void CloseConnections() {
        CloseSocket(listener);
        for (auto &entry : connections) CloseSocket(entry.second.fd);
        connections.clear();
    }
    void Stop() {
        std::lock_guard<std::mutex> stopLock(stopMutex);
        stopping = true;
        if (wake[1] >= 0) { const char byte = 1; (void)write(wake[1], &byte, 1); }
        if (worker.joinable()) worker.join();
        std::lock_guard<std::mutex> lock(mutex);
        CloseConnections();
        for (int &fd : wake) if (fd >= 0) { close(fd); fd = -1; }
    }
    void Accept() {
        for (size_t i = 0; i < MAX_CONNECTIONS && !stopping; ++i) {
            int fd = accept(listener, nullptr, nullptr);
            if (fd < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
                throw Exception("loopback_io_error", "Cannot accept the loopback request");
            }
            if (connections.size() >= MAX_CONNECTIONS) { CloseSocket(fd); continue; }
            try { Nonblocking(fd); }
            catch (...) { CloseSocket(fd); throw; }
#ifdef SO_NOSIGPIPE
            int suppress = 1;
            if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &suppress, sizeof(suppress)) < 0) {
                CloseSocket(fd); throw Exception("loopback_io_error", "Cannot configure the loopback connection");
            }
#endif
            const auto id = std::to_string(++nextConnection);
            connections.emplace(id, Connection {fd, {}, std::chrono::steady_clock::now() + CONNECTION_TIMEOUT, false});
        }
    }
    bool Read(const std::string &id, Connection &connection, std::vector<Request> &ready) {
        char bytes[2048];
        while (!stopping) {
            const auto count = recv(connection.fd, bytes, sizeof(bytes), 0);
            if (count == 0) return false;
            if (count < 0) {
                if (errno == EINTR) continue;
                return errno == EAGAIN || errno == EWOULDBLOCK;
            }
            connection.header.append(bytes, static_cast<size_t>(count));
            if (connection.header.size() > MAX_HEADER) { (void)SendResponse(connection.fd, 400); return false; }
            if (connection.header.find("\r\n\r\n") == std::string::npos) continue;
            auto target = GetTarget(connection.header);
            if (target.empty()) { (void)SendResponse(connection.fd, 400); return false; }
            connection.pending = true;
            connection.header.clear();
            ready.push_back({id, std::move(target), {}});
            return true;
        }
        return false;
    }
    void Run() {
        try {
            while (!stopping) {
                std::vector<pollfd> polls;
                std::vector<std::string> ids;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    polls.push_back({listener, POLLIN, 0}); polls.push_back({wake[0], POLLIN, 0});
                    for (auto &entry : connections) {
                        polls.push_back({entry.second.fd, static_cast<short>(entry.second.pending ? 0 : POLLIN), 0});
                        ids.push_back(entry.first);
                    }
                }
                const int result = poll(polls.data(), polls.size(), 250);
                if (result < 0) { if (errno == EINTR) continue; throw Exception("loopback_io_error", "Loopback polling failed"); }
                if (stopping) break;
                std::vector<Request> ready;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (polls[1].revents & POLLIN) { char bytes[32]; while (read(wake[0], bytes, sizeof(bytes)) > 0) {} }
                    if (polls[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                        throw Exception("loopback_io_error", "The loopback listener closed unexpectedly");
                    }
                    if (polls[0].revents & POLLIN) Accept();
                    for (size_t i = 0; i < ids.size(); ++i) {
                        auto found = connections.find(ids[i]);
                        if (found == connections.end()) continue;
                        auto &connection = found->second;
                        const short events = polls[i + 2].revents;
                        const bool expired = std::chrono::steady_clock::now() >= connection.deadline;
                        if (expired || (events & (POLLERR | POLLHUP | POLLNVAL)) ||
                            ((events & POLLIN) && !connection.pending && !Read(found->first, connection, ready))) {
                            CloseSocket(connection.fd); connections.erase(found);
                        }
                    }
                }
                for (auto &request : ready) if (!stopping) callback(std::move(request));
            }
        } catch (const std::exception &) {
            if (!stopping) callback({{}, {}, "loopback_io_error"});
            stopping = true;
        }
        std::lock_guard<std::mutex> lock(mutex);
        CloseConnections();
    }
    void Reply(const std::string &id, int status) {
        if (status != 200 && status != 400 && status != 404) {
            throw Exception("invalid_arguments", "Unsupported loopback response status");
        }
        std::lock_guard<std::mutex> stopLock(stopMutex);
        std::lock_guard<std::mutex> lock(mutex);
        auto found = connections.find(id);
        if (stopping || found == connections.end() || !found->second.pending) {
            throw Exception("loopback_request_expired", "The loopback request is no longer available");
        }
        const bool sent = SendResponse(found->second.fd, status);
        CloseSocket(found->second.fd); connections.erase(found);
        if (!sent) throw Exception("loopback_io_error", "Cannot reply to the loopback request");
    }
};

Server::Server() : impl_(std::make_unique<Impl>()) {}
std::shared_ptr<Server> Server::Start(uint16_t port, Callback callback) {
    auto server = std::shared_ptr<Server>(new Server());
    server->impl_->Open(port, std::move(callback)); return server;
}
Server::~Server() { Close(); }
uint16_t Server::Port() const { return impl_->port; }
void Server::Reply(const std::string &connectionId, int status) { impl_->Reply(connectionId, status); }
void Server::Close() { impl_->Stop(); }
} // namespace amber::googleloopback
