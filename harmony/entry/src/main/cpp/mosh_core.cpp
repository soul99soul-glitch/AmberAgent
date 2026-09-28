#include "mosh_core.h"
#include "user.h"
#include "completeterminal.h"
#include "fatal_assert.h"
#include "networktransport-impl.h"
#include "terminaldisplay.h"
#include "timestamp.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <future>
#include <locale.h>
#include <mutex>
#include <poll.h>
#include <thread>
#include <unordered_map>
#include <arpa/inet.h>
#include <openssl/crypto.h>
namespace amber::mosh {
Options::~Options() { ClearKey(); }
void Options::ClearKey() { if (!sessionKey.empty()) OPENSSL_cleanse(sessionKey.data(), sessionKey.size()); sessionKey.clear(); }
namespace {
using Transport = Network::Transport<Network::UserStream, Terminal::Complete>;
constexpr size_t INPUT_LIMIT = 65536;
constexpr size_t OUTPUT_LIMIT = 1048576;
constexpr uint64_t RECONNECT_MS = 5000; // Above upstream 3000ms idle ACK interval.
using Clock = std::chrono::steady_clock;
void Shape(int columns, int rows) {
    if (columns < 1 || columns > 1000 || rows < 1 || rows > 1000 || columns * rows > 30000)
        throw Exception("invalid_arguments", "Mosh terminal dimensions exceed limits");
}
struct Session {
    std::string id;
    std::string requestId;
    uint64_t tenant = 0;
    Options options;
    std::atomic<bool> cancelled {false};
    std::mutex cacheMutex;
    Packet cache;
    std::deque<uint8_t> output;
    size_t queuedInput = 0;
    std::unique_ptr<Transport> transport;
    Terminal::Display display {false};
    Terminal::Framebuffer frame {1, 1};
    Complete startDone;
    bool started = false;
    bool repaint = true;
    bool heard = false;
    uint64_t lastHeard = 0;
    uint64_t deadline = 0;
    std::string closeReason;
    uint64_t closeDeadline = 0;
    Complete closeDone;
    bool released = false;
    Packet Drain(size_t maxBytes) {
        std::lock_guard<std::mutex> lock(cacheMutex);
        Packet packet = cache;
        size_t count = std::min(maxBytes, output.size()); packet.bytes.reserve(count);
        while (count--) { packet.bytes.push_back(output.front()); output.pop_front(); }
        // Consumers release terminal handles on the first final packet. Preserve
        // all pending VT bytes before exposing that final state/error.
        if (!output.empty() && packet.state != "running" && packet.state != "reconnecting") {
            packet.state = "running"; packet.errorCode.reset();
        }
        return packet;
    }
    void Finish(const std::string &state, const char *code = nullptr) {
        if (transport) {
            auto tail = display.close();
            std::lock_guard<std::mutex> lock(cacheMutex);
            if (output.size() + tail.size() <= OUTPUT_LIMIT) output.insert(output.end(), tail.begin(), tail.end());
        }
        transport.reset(); options.ClearKey();
        { std::lock_guard<std::mutex> lock(cacheMutex); cache.state = state;
          if (code) cache.errorCode = code; }
        if (startDone) {
            Result result; result.error = Error {code ? code : "cancelled", "Mosh connection did not complete"};
            auto done = std::move(startDone); done(std::move(result));
        }
        if (closeDone) {
            Result result; result.packet = Drain(OUTPUT_LIMIT);
            auto done = std::move(closeDone); done(std::move(result));
        }
    }
};
class Engine {
public:
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<std::function<void()>> commands;
    std::unordered_map<std::string, std::shared_ptr<Session>> sessions;
    std::atomic<uint64_t> next {1};
    bool stopping = false;
    bool utf8 = false;
    std::thread owner;
    Engine() : owner([this] { Loop(); }) {}
    ~Engine() {
        { std::lock_guard<std::mutex> lock(mutex); stopping = true; } wake.notify_one();
        if (owner.joinable()) owner.join();
    }
    void Enqueue(std::function<void()> command) {
        { std::lock_guard<std::mutex> lock(mutex); if (stopping) throw Exception("native_closed", "Mosh owner has closed"); commands.push_back(std::move(command)); }
        wake.notify_one();
    }
    std::shared_ptr<Session> Find(uint64_t tenant, const Handle &handle) {
        std::lock_guard<std::mutex> lock(mutex);
        auto it = sessions.find(handle.id);
        if (handle.kind != "mosh" || it == sessions.end() || it->second->tenant != tenant || it->second->released)
            throw Exception("unknown_handle", "Mosh session is unavailable");
        return it->second;
    }
    void Initialize(const std::shared_ptr<Session> &session) {
        if (session->cancelled) { session->Finish("cancelled", "cancelled"); return; }
        try {
            if (!utf8) { session->Finish("failed", "locale_unavailable"); return; }
            freeze_timestamp();
            Network::UserStream empty;
            Terminal::Complete remote(session->options.columns, session->options.rows);
            auto port = std::to_string(session->options.port);
            session->transport = std::make_unique<Transport>(empty, remote, session->options.sessionKey.c_str(), session->options.peerAddress.c_str(), port.c_str());
            session->options.ClearKey();
            session->transport->set_send_delay(1);
            session->transport->get_current_state().push_back(Parser::Resize(session->options.columns, session->options.rows));
            session->frame = Terminal::Framebuffer(session->options.columns, session->options.rows);
            auto init = session->display.open() + session->display.new_frame(false, session->frame, session->frame);
            { std::lock_guard<std::mutex> lock(session->cacheMutex); session->output.insert(session->output.end(), init.begin(), init.end()); }
            session->deadline = frozen_timestamp() + session->options.connectTimeoutMs;
        } catch (const std::exception &) { session->Finish("failed", "network_error"); }
    }
    void Service(const std::shared_ptr<Session> &session) {
        if (!session->transport) return;
        if (session->cancelled && session->closeReason.empty()) {
            session->closeReason = "cancelled";
            session->closeDeadline = frozen_timestamp() + 200;
            session->transport->start_shutdown();
        }
        try {
            for (int fd : session->transport->fds()) {
                for (int count = 0; count < 32; ++count) {
                    pollfd descriptor {fd, POLLIN, 0};
                    if (poll(&descriptor, 1, 0) <= 0 || !(descriptor.revents & POLLIN)) break;
                    try {
                        session->transport->recv();
                        // Upstream updates freshness only after authentication and
                        // sequence checks. recv() can also return an old replay.
                        auto fresh = session->transport->get_last_heard_timestamp();
                        if (fresh != uint64_t(-1)) { session->heard = true; session->lastHeard = fresh; }
                    } catch (const Crypto::CryptoException &error) {
                        if (error.fatal) throw;
                        // Unauthenticated datagrams/replays cannot end a session.
                    } catch (const Network::NetworkException &error) {
                        // MSG_TRUNC is detected before authentication by upstream.
                        // Drop that invalid datagram without ending an SSP session.
                        if (error.function == "Received oversize datagram") break;
                        if (error.the_errno != EAGAIN && error.the_errno != EWOULDBLOCK &&
                            error.the_errno != ENETUNREACH && error.the_errno != ENETDOWN &&
                            error.the_errno != EHOSTUNREACH && error.the_errno != ECONNREFUSED) throw;
                        break;
                    }
                }
            }
            auto now = frozen_timestamp();
            if (!session->started && session->heard) {
                session->started = true;
                Result result; result.handle = {session->id, "mosh"};
                auto done = std::move(session->startDone); done(std::move(result));
            }
            if (!session->started && now >= session->deadline) { session->Finish("failed", "connect_timeout"); return; }
            { std::lock_guard<std::mutex> lock(session->cacheMutex);
              session->cache.state = session->heard && now - session->lastHeard >= RECONNECT_MS ? "reconnecting" : "running";
              session->cache.lastHeardMs = session->heard ? std::optional<int64_t>(now - session->lastHeard) : std::nullopt; }
            if (session->started && session->closeReason.empty()) {
                auto nextFrame = session->transport->get_latest_remote_state().state.get_fb();
                size_t buffered; { std::lock_guard<std::mutex> lock(session->cacheMutex); buffered = session->output.size(); }
                // Back pressure: retain the last displayed frame, then produce one
                // correct diff to the latest server frame once the reader drains.
                if (buffered < OUTPUT_LIMIT / 2) {
                    auto diff = session->display.new_frame(!session->repaint, session->frame, nextFrame);
                    if (diff.size() > OUTPUT_LIMIT / 2) { session->Finish("failed", "output_limit"); return; }
                    { std::lock_guard<std::mutex> lock(session->cacheMutex); session->output.insert(session->output.end(), diff.begin(), diff.end()); }
                    session->frame = nextFrame; session->repaint = false;
                }
            }
            session->transport->tick();
            if (session->transport->counterparty_shutdown_ack_sent()) { session->Finish("closed"); return; }
            if (!session->closeReason.empty() && (session->transport->shutdown_acknowledged() || now >= session->closeDeadline)) {
                auto state = session->closeReason == "release" ? "closed" : session->closeReason;
                session->Finish(state); return;
            }
        } catch (const std::exception &) { session->Finish("failed", "network_error"); }
    }
    void Loop() {
        locale_t locale = newlocale(LC_CTYPE_MASK, "C.UTF-8", nullptr);
        if (!locale) locale = newlocale(LC_CTYPE_MASK, "en_US.UTF-8", nullptr);
        locale_t previous = locale ? uselocale(locale) : nullptr;
        utf8 = locale && MB_CUR_MAX > 1;
        while (true) {
            std::deque<std::function<void()>> pending;
            std::vector<std::shared_ptr<Session>> active;
            bool stop;
            { std::unique_lock<std::mutex> lock(mutex);
              if (commands.empty() && sessions.empty()) wake.wait(lock, [this] { return stopping || !commands.empty(); });
              else if (commands.empty()) wake.wait_for(lock, std::chrono::milliseconds(10));
              pending.swap(commands); stop = stopping;
              for (auto &entry : sessions) active.push_back(entry.second); }
            freeze_timestamp();
            for (auto &command : pending) command();
            if (stop) {
                for (auto &session : active) session->Finish("disconnected", "native_closed");
                break;
            }
            for (auto &session : active) Service(session);
            { std::lock_guard<std::mutex> lock(mutex);
              for (auto it = sessions.begin(); it != sessions.end();) {
                  if (!it->second->transport && (it->second->released || !it->second->started)) it = sessions.erase(it); else ++it;
              } }
        }
        if (locale) { uselocale(previous); freelocale(locale); }
    }
};
std::shared_ptr<Engine> SharedEngine() {
    // Process lifetime owns the owner, never an owner-thread queue closure.
    // This also prevents a new env spawning a second owner while the old one
    // destroys upstream's process-global timestamp/crypto/compressor caches.
    static const auto engine = std::make_shared<Engine>();
    return engine;
}
}
struct Runtime::Impl {
    std::shared_ptr<Engine> engine = SharedEngine();
    uint64_t tenant = engine->next++;
    std::atomic<bool> stopping {false};
};
Runtime::Runtime() : impl_(std::make_shared<Impl>()) {}
Runtime::~Runtime() { Shutdown(); }
void Runtime::Start(const std::string &requestId, Options options, Complete complete) {
    Shape(options.columns, options.rows);
    uint8_t address[16];
    if (requestId.empty() || requestId.size() > 256 || options.peerAddress.empty() ||
        (inet_pton(AF_INET, options.peerAddress.c_str(), address) != 1 && inet_pton(AF_INET6, options.peerAddress.c_str(), address) != 1) ||
        options.port < 1 || options.port > 65535 || options.sessionKey.size() != 22 ||
        options.connectTimeoutMs < 1 || options.connectTimeoutMs > 60000)
        throw Exception("invalid_arguments", "Mosh connection arguments are invalid");
    for (char byte : options.sessionKey) if (!(byte >= 'A' && byte <= 'Z') && !(byte >= 'a' && byte <= 'z') && !(byte >= '0' && byte <= '9') && byte != '+' && byte != '/')
        throw Exception("invalid_arguments", "Mosh session key format is invalid");
    if (options.sessionKey.back() != 'A' && options.sessionKey.back() != 'Q' && options.sessionKey.back() != 'g' && options.sessionKey.back() != 'w')
        throw Exception("invalid_arguments", "Mosh session key is not canonical base64");
    auto session = std::make_shared<Session>(); session->options = std::move(options);
    session->requestId = requestId; session->tenant = impl_->tenant;
    session->id = "mosh-" + std::to_string(impl_->engine->next++); session->startDone = std::move(complete);
    { std::lock_guard<std::mutex> lock(impl_->engine->mutex);
      if (impl_->stopping) throw Exception("native_closed", "Mosh native environment closed");
      for (auto &entry : impl_->engine->sessions) if (entry.second->tenant == impl_->tenant && entry.second->requestId == requestId)
          throw Exception("duplicate_request", "Mosh request ID is already active");
      if (impl_->engine->sessions.size() >= 16) throw Exception("session_limit", "Mosh session limit reached");
      impl_->engine->sessions.emplace(session->id, session); }
    auto engine = impl_->engine;
    engine->Enqueue([engine, session] { engine->Initialize(session); });
}
void Runtime::Cancel(const std::string &requestId) {
    std::lock_guard<std::mutex> lock(impl_->engine->mutex);
    for (auto &entry : impl_->engine->sessions) if (entry.second->tenant == impl_->tenant && entry.second->requestId == requestId) entry.second->cancelled = true;
    impl_->engine->wake.notify_one();
}
Packet Runtime::Read(const Handle &handle, size_t maxBytes) {
    if (maxBytes < 1 || maxBytes > OUTPUT_LIMIT) throw Exception("invalid_arguments", "Mosh read size is invalid");
    auto session = impl_->engine->Find(impl_->tenant, handle);
    return session->Drain(maxBytes);
}
void Runtime::Write(const Handle &handle, std::vector<uint8_t> bytes, Complete complete) {
    auto session = impl_->engine->Find(impl_->tenant, handle);
    { std::lock_guard<std::mutex> lock(session->cacheMutex);
      if (bytes.size() > INPUT_LIMIT || session->queuedInput + bytes.size() > INPUT_LIMIT) throw Exception("queue_full", "Mosh input queue is full");
      session->queuedInput += bytes.size(); }
    impl_->engine->Enqueue([session, bytes = std::move(bytes), complete = std::move(complete)]() mutable {
        Result result;
        if (!session->transport || !session->closeReason.empty() || session->cancelled) result.error = Error {"session_closed", "Mosh session has ended"};
        else if (session->transport->get_current_state().size() + bytes.size() > INPUT_LIMIT) result.error = Error {"queue_full", "Mosh unacknowledged input limit reached"};
        else for (uint8_t byte : bytes) session->transport->get_current_state().push_back(Parser::UserByte(static_cast<char>(byte)));
        { std::lock_guard<std::mutex> lock(session->cacheMutex); session->queuedInput -= bytes.size(); }
        complete(std::move(result));
    });
}
void Runtime::Resize(const Handle &handle, int columns, int rows, Complete complete) {
    Shape(columns, rows); auto session = impl_->engine->Find(impl_->tenant, handle);
    impl_->engine->Enqueue([session, columns, rows, complete = std::move(complete)] {
        Result result;
        if (!session->transport || !session->closeReason.empty() || session->cancelled) result.error = Error {"session_closed", "Mosh session has ended"};
        else if (session->transport->get_current_state().size() >= INPUT_LIMIT) result.error = Error {"queue_full", "Mosh unacknowledged input limit reached"};
        else { session->transport->get_current_state().push_back(Parser::Resize(columns, rows)); session->repaint = true; }
        complete(std::move(result));
    });
}
void Runtime::Close(const Handle &handle, const std::string &reason, Complete complete) {
    if (reason != "cancelled" && reason != "disconnected" && reason != "release") throw Exception("invalid_arguments", "Mosh close reason is invalid");
    auto session = impl_->engine->Find(impl_->tenant, handle); auto engine = impl_->engine;
    // Synchronous cancellation wins before a previously queued write is consumed.
    session->cancelled = true;
    engine->Enqueue([session, engine, reason, complete = std::move(complete)]() mutable {
        if (!session->transport) { Result result; result.packet = session->Drain(OUTPUT_LIMIT); complete(std::move(result)); }
        else if (session->closeDone) { Result result; result.error = Error {"session_closed", "Mosh session is closing"}; complete(std::move(result)); }
        else { session->closeReason = reason; session->closeDeadline = frozen_timestamp() + 200; session->closeDone = std::move(complete); session->transport->start_shutdown(); }
        if (reason == "release") { std::lock_guard<std::mutex> lock(engine->mutex); session->released = true; }
    });
}
void Runtime::Shutdown() {
    if (impl_->stopping.exchange(true)) return;
    auto engine = impl_->engine; auto tenant = impl_->tenant;
    auto done = std::make_shared<std::promise<void>>(); auto future = done->get_future();
    engine->Enqueue([engine, tenant, done] {
        std::vector<std::shared_ptr<Session>> owned;
        { std::lock_guard<std::mutex> lock(engine->mutex);
          for (auto it = engine->sessions.begin(); it != engine->sessions.end();) {
              if (it->second->tenant == tenant) { owned.push_back(it->second); it = engine->sessions.erase(it); } else ++it;
          } }
        for (auto &session : owned) session->Finish("disconnected", "native_closed");
        done->set_value();
    });
    future.wait();
}
size_t Runtime::HandleCount() const {
    std::lock_guard<std::mutex> lock(impl_->engine->mutex); size_t count = 0;
    for (const auto &entry : impl_->engine->sessions) if (entry.second->tenant == impl_->tenant) ++count;
    return count;
}
}
