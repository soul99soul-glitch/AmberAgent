#include "ssh_core.h"
#include <libssh2.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <unordered_map>

namespace amber::ssh {
namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t MAX_PENDING = 1024 * 1024;
constexpr size_t MAX_QUEUED_INPUT = 1024 * 1024;
constexpr int MAX_OWNERS = 32;
std::atomic<uint64_t> nextHandle {1};
using Failure = Exception;
Result Failed(const Error &error) { Result result; result.error = error; return result; }
void Clear(std::string &value) { if (!value.empty()) OPENSSL_cleanse(value.data(), value.size()); value.clear(); }
bool Text(const std::string &value, size_t limit) {
    return !value.empty() && value.size() <= limit && value.find('\0') == std::string::npos;
}
void Nonblocking(int fd) {
    if (fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) < 0 ||
        fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) throw Failure("network_error", "Cannot configure SSH socket");
}
void Validate(const Endpoint &options) {
    if (!Text(options.host, 1024) || options.port < 1 || options.port > 65535 ||
        options.timeoutMs < 1 || options.timeoutMs > 300000)
        throw Failure("invalid_arguments", "Invalid SSH endpoint or connection timeout");
}
std::string Fingerprint(LIBSSH2_SESSION *session) {
    const char *hash = libssh2_hostkey_hash(session, LIBSSH2_HOSTKEY_HASH_SHA256);
    if (!hash) throw Failure("network_error", "SSH host fingerprint is unavailable");
    unsigned char encoded[45] {};
    int length = EVP_EncodeBlock(encoded, reinterpret_cast<const unsigned char *>(hash), 32);
    while (length > 0 && encoded[length - 1] == '=') --length;
    return "SHA256:" + std::string(reinterpret_cast<char *>(encoded), static_cast<size_t>(length));
}
std::string KeyType(LIBSSH2_SESSION *session) {
    size_t length = 0; int type = 0;
    libssh2_session_hostkey(session, &length, &type);
    switch (type) {
        case LIBSSH2_HOSTKEY_TYPE_RSA: return "ssh-rsa";
        case LIBSSH2_HOSTKEY_TYPE_DSS: return "ssh-dss";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_256: return "ecdsa-sha2-nistp256";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_384: return "ecdsa-sha2-nistp384";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_521: return "ecdsa-sha2-nistp521";
        case LIBSSH2_HOSTKEY_TYPE_ED25519: return "ssh-ed25519";
        default: return "unknown";
    }
}
struct Addresses {
    std::mutex mutex;
    std::condition_variable ready;
    bool done = false;
    addrinfo *head = nullptr;
    int error = EAI_FAIL;
    ~Addresses() { if (head) freeaddrinfo(head); }
};
#ifdef AMBER_SSH_TESTING
std::atomic<int> resolverDelay {0};
std::atomic<int> resolverTasks {0};
#endif
struct Operation {
    enum Kind { WRITE, RESIZE } kind;
    std::vector<uint8_t> bytes;
    int columns = 0;
    int rows = 0;
    Complete complete;
};
struct Owner : std::enable_shared_from_this<Owner> {
    Endpoint endpoint;
    ConnectionOptions connection;
    StartOptions start;
    bool probe = false;
    Complete started;
    std::function<void()> finished;
    Clock::time_point deadline;
    Clock::time_point connectDeadline;
    int socket = -1;
    int wake[2] {-1, -1};
    LIBSSH2_SESSION *session = nullptr;
    LIBSSH2_CHANNEL *channel = nullptr;
    std::mutex mutex;
    std::condition_variable ended;
    std::deque<Chunk> output;
    size_t outputBytes = 0;
    std::deque<Operation> operations;
    size_t inputBytes = 0;
    std::vector<Complete> closes;
    std::string stopReason;
    std::string state = "running";
    std::optional<Error> error;
    std::optional<int64_t> exitCode;
    bool done = false;
    Handle handle;
    ~Owner() { connection.ClearSecrets(); for (int fd : wake) if (fd >= 0) ::close(fd); }
    void Wake() { const char byte = 0; (void)::write(wake[1], &byte, 1); }
    void Stop(const std::string &reason) {
        { std::lock_guard<std::mutex> lock(mutex); if (!done && stopReason.empty()) stopReason = reason; }
        Wake();
    }
    void Check(bool connecting) {
        { std::lock_guard<std::mutex> lock(mutex);
          if (!stopReason.empty()) throw Failure(stopReason == "cancelled" ? "cancelled" : "network_error",
              stopReason == "cancelled" ? "SSH operation cancelled" : "SSH connection disconnected"); }
        if (Clock::now() >= deadline) throw Failure("connection_timeout", "SSH command timed out");
        if (connecting && Clock::now() >= connectDeadline)
            throw Failure("connection_timeout", "SSH connection timed out");
    }
    void Wait(short events, bool connecting, bool backpressure = false) {
        Check(connecting);
        auto until = connecting ? std::min(deadline, connectDeadline) : deadline;
        int delay = 100;
        if (until != Clock::time_point::max()) delay = std::max(1, std::min(delay,
            static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(until - Clock::now()).count())));
        pollfd fds[2] {{wake[0], POLLIN, 0}, {backpressure ? -1 : socket, events, 0}};
        int result = poll(fds, 2, delay);
        if (result < 0 && errno != EINTR) throw Failure("network_error", "SSH socket polling failed");
        if (fds[0].revents & POLLIN) { char buffer[64]; while (::read(wake[0], buffer, sizeof(buffer)) > 0) {} }
        Check(connecting);
        if (fds[1].revents & (POLLNVAL | POLLERR)) throw Failure("network_error", "SSH socket disconnected");
        // POLLHUP can coexist with the final SSH channel close packet. Read it before classifying transport loss.
    }
    void WaitSSH(bool connecting) {
        int directions = libssh2_session_block_directions(session);
        short events = 0;
        if (directions & LIBSSH2_SESSION_BLOCK_INBOUND) events |= POLLIN;
        if (directions & LIBSSH2_SESSION_BLOCK_OUTBOUND) events |= POLLOUT;
        Wait(events ? events : POLLIN, connecting);
    }
    template<class Call> void Retry(Call call, bool connecting, const char *code, const char *message) {
        while (true) {
            Check(connecting);
            int result = call();
            if (!result) return;
            if (result != LIBSSH2_ERROR_EAGAIN) throw Failure(code, message);
            WaitSSH(connecting);
        }
    }
    void Connect() {
        auto addresses = std::make_shared<Addresses>();
        addrinfo hints {}; hints.ai_socktype = SOCK_STREAM; hints.ai_family = AF_UNSPEC; hints.ai_flags = AI_NUMERICHOST;
        const std::string service = std::to_string(endpoint.port);
        addresses->error = getaddrinfo(endpoint.host.c_str(), service.c_str(), &hints, &addresses->head);
        if (addresses->error == EAI_NONAME) {
            const std::string host = endpoint.host;
            hints.ai_flags = 0;
            std::thread([addresses, host, service, hints]() {
#ifdef AMBER_SSH_TESTING
                ++resolverTasks;
                std::this_thread::sleep_for(std::chrono::milliseconds(resolverDelay.load()));
#endif
                addrinfo *head = nullptr;
                int error = getaddrinfo(host.c_str(), service.c_str(), &hints, &head);
                { std::lock_guard<std::mutex> lock(addresses->mutex);
                  addresses->head = head; addresses->error = error; addresses->done = true; }
                addresses->ready.notify_all();
#ifdef AMBER_SSH_TESTING
                --resolverTasks;
#endif
            }).detach();
            std::unique_lock<std::mutex> lock(addresses->mutex);
            while (!addresses->done) {
                lock.unlock(); Check(true); lock.lock();
                addresses->ready.wait_for(lock, std::chrono::milliseconds(50));
            }
        }
        Check(true);
        if (addresses->error || !addresses->head) throw Failure("network_error", "SSH host resolution failed");
        for (auto address = addresses->head; address; address = address->ai_next) {
            Check(true);
            socket = ::socket(address->ai_family, address->ai_socktype, address->ai_protocol);
            if (socket < 0) continue;
            Nonblocking(socket);
#ifdef SO_NOSIGPIPE
            int noSignal = 1; setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, sizeof(noSignal));
#endif
            int result = ::connect(socket, address->ai_addr, address->ai_addrlen);
            if (result && errno == EINPROGRESS) {
                Wait(POLLOUT, true);
                int socketError = 0; socklen_t size = sizeof(socketError);
                result = getsockopt(socket, SOL_SOCKET, SO_ERROR, &socketError, &size);
                if (!result) result = socketError;
            }
            if (!result) {
                sockaddr_storage peer {};
                socklen_t peerLength = sizeof(peer);
                char numericHost[NI_MAXHOST] {};
                if (getpeername(socket, reinterpret_cast<sockaddr *>(&peer), &peerLength) != 0 ||
                    getnameinfo(reinterpret_cast<sockaddr *>(&peer), peerLength,
                        numericHost, sizeof(numericHost), nullptr, 0, NI_NUMERICHOST) != 0)
                    throw Failure("network_error", "Cannot obtain connected SSH peer address");
                handle.peerAddress = numericHost;
                return;
            }
            ::close(socket); socket = -1;
        }
        throw Failure("network_error", "SSH TCP connection failed");
    }
    void Handshake() {
        Connect();
        session = libssh2_session_init();
        if (!session) throw Failure("network_error", "Cannot create SSH session");
        libssh2_session_set_blocking(session, 0);
        Retry([&]() { return libssh2_session_handshake(session, socket); }, true,
            "network_error", "SSH handshake failed");
    }
    void Authenticate() {
        // Pin verification is deliberately before any userauth call.
        const std::string actual = Fingerprint(session);
        if (actual.size() != connection.fingerprint.size() ||
            CRYPTO_memcmp(actual.data(), connection.fingerprint.data(), actual.size()))
            throw Failure("host_key_mismatch", "SSH host key does not match the trusted fingerprint");
        if (connection.authMethod == "password") {
            Retry([&]() { return libssh2_userauth_password_ex(session, connection.username.data(),
                static_cast<unsigned>(connection.username.size()), connection.secret.data(),
                static_cast<unsigned>(connection.secret.size()), nullptr); }, true,
                "authentication_failed", "SSH password authentication failed");
        } else {
            Retry([&]() { return libssh2_userauth_publickey_frommemory(session, connection.username.data(),
                connection.username.size(), nullptr, 0, connection.secret.data(), connection.secret.size(),
                connection.passphrase ? connection.passphrase->c_str() : nullptr); }, true,
                "authentication_failed", "SSH private key authentication failed");
        }
        connection.ClearSecrets();
    }
    void OpenChannel() {
        while (!channel) {
            Check(true);
            channel = libssh2_channel_open_session(session);
            if (!channel && libssh2_session_last_errno(session) != LIBSSH2_ERROR_EAGAIN)
                throw Failure("channel_error", "Cannot open SSH channel");
            if (!channel) WaitSSH(true);
        }
        if (start.kind == "pty") {
            Retry([&]() { return libssh2_channel_request_pty_ex(channel, start.term.c_str(),
                static_cast<unsigned>(start.term.size()), nullptr, 0, start.columns, start.rows, 0, 0); }, true,
                "channel_error", "SSH PTY request failed");
            Retry([&]() { return libssh2_channel_shell(channel); }, true, "channel_error", "SSH shell request failed");
        } else {
            Retry([&]() { return libssh2_channel_process_startup(channel, "exec", 4, start.command.data(),
                static_cast<unsigned>(start.command.size())); }, true, "channel_error", "SSH exec request failed");
        }
    }
    void Operations() {
        while (true) {
            Operation operation;
            { std::lock_guard<std::mutex> lock(mutex);
              if (operations.empty()) return;
              operation = std::move(operations.front()); operations.pop_front(); }
            try {
                if (operation.kind == Operation::RESIZE) {
                    Retry([&]() { return libssh2_channel_request_pty_size_ex(channel, operation.columns,
                        operation.rows, 0, 0); }, false, "channel_error", "SSH PTY resize failed");
                } else {
                    size_t offset = 0;
                    while (offset < operation.bytes.size()) {
                        Check(false);
                        auto result = libssh2_channel_write_ex(channel, 0,
                            reinterpret_cast<const char *>(operation.bytes.data() + offset), operation.bytes.size() - offset);
                        if (result > 0) offset += static_cast<size_t>(result);
                        else if (!result || result == LIBSSH2_ERROR_EAGAIN) WaitSSH(false);
                        else throw Failure("channel_error", "SSH PTY write failed");
                    }
                }
                { std::lock_guard<std::mutex> lock(mutex); inputBytes -= operation.bytes.size(); }
                operation.complete({});
            } catch (const Failure &failure) {
                { std::lock_guard<std::mutex> lock(mutex); inputBytes -= operation.bytes.size(); }
                operation.complete(Failed(failure.error));
                throw;
            }
        }
    }
    void Pump() {
        unsigned nextStream = 0;
        while (true) {
            Check(false);
            Operations();
            bool progress = false;
            bool full = false;
            for (unsigned turn = 0; turn < 2; ++turn) {
                int stream = static_cast<int>((nextStream + turn) % 2);
                size_t capacity;
                { std::lock_guard<std::mutex> lock(mutex); capacity = MAX_PENDING - outputBytes; }
                if (!capacity) { full = true; break; }
                Chunk chunk; chunk.isStderr = stream == 1; chunk.bytes.resize(std::min<size_t>(16384, capacity));
                auto count = libssh2_channel_read_ex(channel, stream, reinterpret_cast<char *>(chunk.bytes.data()), chunk.bytes.size());
                if (count > 0) {
                    chunk.bytes.resize(static_cast<size_t>(count));
                    { std::lock_guard<std::mutex> lock(mutex); outputBytes += chunk.bytes.size(); output.push_back(std::move(chunk)); }
                    progress = true;
                } else if (count < 0 && count != LIBSSH2_ERROR_EAGAIN)
                    throw Failure("network_error", "SSH channel transport interrupted");
            }
            nextStream = (nextStream + 1) % 2;
            if (full) { Wait(0, false, true); continue; }
            if (progress) continue;
            if (libssh2_channel_eof(channel)) {
                Retry([&]() { return libssh2_channel_close(channel); }, false, "channel_error", "SSH channel close failed");
                Retry([&]() { return libssh2_channel_wait_closed(channel); }, false, "network_error", "SSH remote channel close failed");
                char *signal = nullptr; size_t signalLength = 0;
                int signalResult = libssh2_channel_get_exit_signal(channel, &signal, &signalLength, nullptr, nullptr, nullptr, nullptr);
                if (signal) libssh2_free(session, signal);
                if (!signalResult && signalLength) throw Failure("channel_error", "SSH process exited by signal");
                if (!libssh2_channel_exit_status_received(channel))
                    throw Failure("channel_error", "SSH channel closed without a remote exit status");
                std::lock_guard<std::mutex> lock(mutex);
                exitCode = static_cast<uint32_t>(libssh2_channel_get_exit_status(channel)); state = "exited";
                return;
            }
            WaitSSH(false);
        }
    }
    Packet DrainLocked(size_t maxBytes, bool hideTerminal) {
        Packet packet;
        while (!output.empty() && maxBytes) {
            auto &front = output.front();
            size_t take = std::min(maxBytes, front.bytes.size());
            Chunk chunk; chunk.isStderr = front.isStderr;
            chunk.bytes.assign(front.bytes.begin(), front.bytes.begin() + static_cast<ptrdiff_t>(take));
            packet.chunks.push_back(std::move(chunk));
            if (take == front.bytes.size()) output.pop_front();
            else front.bytes.erase(front.bytes.begin(), front.bytes.begin() + static_cast<ptrdiff_t>(take));
            outputBytes -= take; maxBytes -= take;
        }
        packet.state = hideTerminal && outputBytes ? "running" : state;
        if (packet.state != "running") { packet.exitCode = exitCode; packet.error = error; }
        return packet;
    }
    void Cleanup() {
        connection.ClearSecrets();
        // All libssh2 destruction stays on the owner. Closing the fd bounds EAGAIN cleanup.
        bool stopped;
        { std::lock_guard<std::mutex> lock(mutex); stopped = !stopReason.empty(); }
        if (channel && stopped && start.kind == "exec") (void)libssh2_channel_signal_ex(channel, "TERM", 4);
        if (socket >= 0) ::shutdown(socket, SHUT_RDWR);
        if (channel) { (void)libssh2_channel_free(channel); channel = nullptr; }
        if (session) { (void)libssh2_session_free(session); session = nullptr; }
        if (socket >= 0) { ::close(socket); socket = -1; }
    }
    void Run() {
        try {
            Handshake();
            if (probe) {
                Result result; result.fingerprint = Fingerprint(session); result.hostKeyType = KeyType(session);
                Cleanup(); started(std::move(result)); started = nullptr;
            } else {
                Authenticate(); OpenChannel();
                Result result; result.handle = handle;
                started(std::move(result)); started = nullptr;
                Pump();
            }
        } catch (const Failure &failure) {
            { std::lock_guard<std::mutex> lock(mutex);
              state = !stopReason.empty() ? (stopReason == "cancelled" ? "cancelled" : "disconnected") :
                  failure.error.code == "connection_timeout" ? "timed_out" :
                  failure.error.code == "network_error" ? "disconnected" : "failed";
              error = failure.error; exitCode.reset(); }
            if (started) { started(Failed(failure.error)); started = nullptr; }
        } catch (const std::exception &) {
            Error failure {"network_error", "SSH native operation failed"};
            { std::lock_guard<std::mutex> lock(mutex); state = "failed"; error = failure; exitCode.reset(); }
            if (started) { started(Failed(failure)); started = nullptr; }
        }
        Cleanup();
        std::deque<Operation> pending;
        std::vector<Complete> callbacks;
        Packet packet;
        { std::lock_guard<std::mutex> lock(mutex);
          pending.swap(operations); inputBytes = 0; callbacks.swap(closes);
          if (!callbacks.empty()) packet = DrainLocked(MAX_PENDING, false);
          done = true; }
        for (auto &operation : pending) operation.complete(Failed({"channel_error", "SSH session closed before operation completed"}));
        for (auto &callback : callbacks) { Result result; result.packet = packet; callback(std::move(result)); }
        finished(); ended.notify_all();
    }
};
} // namespace
void ConnectionOptions::ClearSecrets() { Clear(secret); if (passphrase) Clear(*passphrase); passphrase.reset(); }
struct Runtime::Impl {
    mutable std::mutex mutex;
    bool stopping = false;
    std::unordered_map<std::string, std::shared_ptr<Owner>> requests;
    std::unordered_map<std::string, std::shared_ptr<Owner>> handles;
    std::unordered_map<Owner *, std::shared_ptr<Owner>> owners;
    std::shared_ptr<Owner> Find(const Handle &handle) {
        std::lock_guard<std::mutex> lock(mutex);
        auto found = handles.find(handle.id);
        if (found == handles.end() || found->second->handle.kind != handle.kind)
            throw Failure("unknown_handle", "Unknown SSH handle or handle kind");
        return found->second;
    }
    void Launch(const std::string &id, const std::shared_ptr<Owner> &owner, Complete complete) {
        if (!Text(id, 256)) throw Failure("invalid_arguments", "SSH request ID is required");
        { std::lock_guard<std::mutex> lock(mutex);
          if (stopping || requests.count(id) || owners.size() >= MAX_OWNERS)
              throw Failure("invalid_arguments", "SSH request duplicate, owner limit reached, or runtime closed");
          if (pipe(owner->wake)) throw Failure("network_error", "Cannot create SSH cancellation wake pipe");
          Nonblocking(owner->wake[0]); Nonblocking(owner->wake[1]);
          requests.emplace(id, owner); owners.emplace(owner.get(), owner); }
        owner->started = std::move(complete);
    }
};
Runtime::Runtime() : impl_(std::make_shared<Impl>()) {
    static const int initialized = libssh2_init(0);
    if (initialized) throw std::runtime_error("Cannot initialize SSH crypto library");
}
Runtime::~Runtime() { Shutdown(); }
namespace {
void StartThread(const std::shared_ptr<Owner> &owner) {
    try { std::thread([owner]() { owner->Run(); }).detach(); }
    catch (const std::exception &) {
        owner->state = "failed"; owner->error = Error {"network_error", "Cannot create SSH owner thread"};
        owner->done = true; owner->started(Failed(*owner->error)); owner->finished(); owner->ended.notify_all();
    }
}
}
void Runtime::Probe(const std::string &id, Endpoint options, Complete complete) {
    try {
        Validate(options);
        auto owner = std::make_shared<Owner>(); owner->endpoint = std::move(options); owner->probe = true;
        owner->deadline = owner->connectDeadline = Clock::now() + std::chrono::milliseconds(owner->endpoint.timeoutMs);
        impl_->Launch(id, owner, complete);
        auto impl = impl_;
        owner->finished = [impl, id, pointer = owner.get()]() {
            std::lock_guard<std::mutex> lock(impl->mutex); impl->requests.erase(id); impl->owners.erase(pointer);
        };
        StartThread(owner);
    } catch (const Failure &failure) { complete(Failed(failure.error)); }
}
void Runtime::Start(const std::string &id, ConnectionOptions connection, StartOptions start, Complete complete) {
    try {
        Validate(connection);
        if (!Text(connection.username, 256) || !Text(connection.fingerprint, 128) || connection.secret.empty() ||
            connection.secret.size() > 65536 || (connection.authMethod != "password" && connection.authMethod != "privateKey") ||
            (connection.passphrase && (connection.passphrase->size() > 65536 || connection.passphrase->find('\0') != std::string::npos)) ||
            (start.kind != "exec" && start.kind != "pty") ||
            (start.kind == "exec" && (!Text(start.command, 65536) || start.timeoutMs < 1 || start.timeoutMs > 86400000)) ||
            (start.kind == "pty" && (!Text(start.term, 128) || start.columns < 1 || start.columns > 1000 || start.rows < 1 || start.rows > 1000)))
            throw Failure("invalid_arguments", "Invalid SSH credentials, trusted fingerprint, or channel options");
        auto owner = std::make_shared<Owner>(); owner->endpoint = connection;
        owner->connection = std::move(connection); owner->start = std::move(start);
        owner->handle = {"ssh-" + std::to_string(nextHandle.fetch_add(1)), owner->start.kind, ""};
        auto now = Clock::now(); owner->connectDeadline = now + std::chrono::milliseconds(owner->endpoint.timeoutMs);
        owner->deadline = owner->start.kind == "exec" ? now + std::chrono::milliseconds(owner->start.timeoutMs) : Clock::time_point::max();
        auto impl = impl_;
        impl_->Launch(id, owner, [impl, id, owner, complete](Result result) {
            { std::lock_guard<std::mutex> lock(impl->mutex);
              impl->requests.erase(id); if (!result.error) impl->handles.emplace(owner->handle.id, owner); }
            complete(std::move(result));
        });
        owner->finished = [impl, id, pointer = owner.get()]() {
            std::lock_guard<std::mutex> lock(impl->mutex); impl->requests.erase(id); impl->owners.erase(pointer);
        };
        StartThread(owner);
    } catch (const Failure &failure) { connection.ClearSecrets(); complete(Failed(failure.error)); }
}
void Runtime::Cancel(const std::string &id) {
    std::shared_ptr<Owner> owner;
    { std::lock_guard<std::mutex> lock(impl_->mutex); auto it = impl_->requests.find(id); if (it != impl_->requests.end()) owner = it->second; }
    if (owner) owner->Stop("cancelled");
}
Packet Runtime::Read(const Handle &handle, size_t maxBytes) {
    if (!maxBytes || maxBytes > MAX_PENDING) throw Failure("invalid_arguments", "SSH read limit must be 1 to 1048576 bytes");
    auto owner = impl_->Find(handle);
    Packet packet;
    { std::lock_guard<std::mutex> lock(owner->mutex); packet = owner->DrainLocked(maxBytes, true); }
    owner->Wake(); return packet;
}
void Runtime::Write(const Handle &handle, std::vector<uint8_t> bytes, Complete complete) {
    try {
        auto owner = impl_->Find(handle);
        if (handle.kind != "pty" || bytes.size() > MAX_QUEUED_INPUT) throw Failure("invalid_arguments", "SSH write requires PTY and at most 1048576 bytes");
        { std::lock_guard<std::mutex> lock(owner->mutex);
          if (owner->done || !owner->stopReason.empty() || owner->state != "running") throw Failure("channel_error", "SSH PTY is closed");
          if (owner->inputBytes + bytes.size() > MAX_QUEUED_INPUT || owner->operations.size() >= 128)
              throw Failure("channel_error", "SSH PTY input queue is full");
          owner->inputBytes += bytes.size();
          owner->operations.push_back({Operation::WRITE, std::move(bytes), 0, 0, std::move(complete)}); }
        owner->Wake();
    } catch (const Failure &failure) { complete(Failed(failure.error)); }
}
void Runtime::Resize(const Handle &handle, int columns, int rows, Complete complete) {
    try {
        auto owner = impl_->Find(handle);
        if (handle.kind != "pty" || columns < 1 || columns > 1000 || rows < 1 || rows > 1000)
            throw Failure("invalid_arguments", "SSH resize requires valid PTY dimensions");
        { std::lock_guard<std::mutex> lock(owner->mutex);
          if (owner->done || !owner->stopReason.empty() || owner->state != "running") throw Failure("channel_error", "SSH PTY is closed");
          if (owner->operations.size() >= 128) throw Failure("channel_error", "SSH PTY operation queue is full");
          owner->operations.push_back({Operation::RESIZE, {}, columns, rows, std::move(complete)}); }
        owner->Wake();
    } catch (const Failure &failure) { complete(Failed(failure.error)); }
}
void Runtime::Close(const Handle &handle, const std::string &reason, Complete complete) {
    try {
        if (reason != "cancelled" && reason != "disconnected" && reason != "release")
            throw Failure("invalid_arguments", "Invalid SSH close reason");
        auto owner = impl_->Find(handle);
        auto impl = impl_;
        Complete remove = [impl, handle, complete](Result result) {
            { std::lock_guard<std::mutex> lock(impl->mutex); impl->handles.erase(handle.id); }
            complete(std::move(result));
        };
        bool now = false; Result result;
        { std::lock_guard<std::mutex> lock(owner->mutex);
          if (reason == "release" && owner->state == "running") throw Failure("channel_error", "Active SSH handle cannot be released");
          if (owner->done) { result.packet = owner->DrainLocked(MAX_PENDING, false); now = true; }
          else { owner->closes.push_back(std::move(remove)); if (reason != "release" && owner->state == "running" && owner->stopReason.empty()) owner->stopReason = reason; } }
        if (now) remove(std::move(result)); else owner->Wake();
    } catch (const Failure &failure) { complete(Failed(failure.error)); }
}
void Runtime::Shutdown() {
    std::vector<std::shared_ptr<Owner>> owners;
    { std::lock_guard<std::mutex> lock(impl_->mutex);
      impl_->stopping = true; for (auto &entry : impl_->owners) owners.push_back(entry.second); }
    for (auto &owner : owners) owner->Stop("disconnected");
    for (auto &owner : owners) { std::unique_lock<std::mutex> lock(owner->mutex); owner->ended.wait(lock, [&]() { return owner->done; }); }
    { std::lock_guard<std::mutex> lock(impl_->mutex); impl_->requests.clear(); impl_->handles.clear(); }
}
size_t Runtime::HandleCount() const { std::lock_guard<std::mutex> lock(impl_->mutex); return impl_->handles.size(); }
size_t Runtime::OwnerCount() const { std::lock_guard<std::mutex> lock(impl_->mutex); return impl_->owners.size(); }
#ifdef AMBER_SSH_TESTING
void SetResolverDelayForTests(int milliseconds) { resolverDelay = milliseconds; }
int ResolverTasksForTests() { return resolverTasks; }
#endif
} // namespace amber::ssh
