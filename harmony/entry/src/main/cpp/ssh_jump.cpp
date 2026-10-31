#include "ssh_jump.h"
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace amber::ssh {
namespace {

void ConfigureSocketNonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 ||
        fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) {
        throw Exception("network_error", "Cannot configure SSH jump bridge socket");
    }
#ifdef SO_NOSIGPIPE
    int noSignal = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, sizeof(noSignal));
#endif
}

std::string ToLowerAscii(const std::string &input) {
    std::string out = input;
    for (char &ch : out) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return out;
}

} // namespace

JumpBridge::JumpBridge(int transportFd, LIBSSH2_SESSION *session, LIBSSH2_CHANNEL *channel,
    int localFd, int wakeReadFd, int wakeWriteFd)
    : transportFd_(transportFd),
      session_(session),
      channel_(channel),
      localFd_(localFd) {
    wakeFd_[0] = wakeReadFd;
    wakeFd_[1] = wakeWriteFd;
}

JumpBridge::~JumpBridge() {
    StopAndJoin();
}

std::unique_ptr<JumpBridge> JumpBridge::CreateAndStart(
    int transportFd,
    LIBSSH2_SESSION *session,
    LIBSSH2_CHANNEL *channel,
    int &outNextSocketFd) {
    int pair[2] {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) {
        throw Exception("network_error", "Cannot create SSH jump socketpair");
    }
    int wake[2] {-1, -1};
    if (pipe(wake) != 0) {
        ::close(pair[0]);
        ::close(pair[1]);
        throw Exception("network_error", "Cannot create SSH jump wake pipe");
    }
    try {
        ConfigureSocketNonblocking(pair[0]);
        ConfigureSocketNonblocking(pair[1]);
        ConfigureSocketNonblocking(wake[0]);
        ConfigureSocketNonblocking(wake[1]);
    } catch (...) {
        ::close(pair[0]);
        ::close(pair[1]);
        ::close(wake[0]);
        ::close(wake[1]);
        throw;
    }

    std::unique_ptr<JumpBridge> bridge;
    try {
        bridge = std::make_unique<JumpBridge>(transportFd, session, channel, pair[0], wake[0], wake[1]);
        bridge->worker_ = std::thread([raw = bridge.get()]() { raw->RunLoop(); });
    } catch (...) {
        ::close(pair[1]);
        if (bridge) {
            bridge->session_ = nullptr;
            bridge->channel_ = nullptr;
            bridge->transportFd_ = -1;
            bridge->CloseResources();
        } else {
            ::close(pair[0]); ::close(wake[0]); ::close(wake[1]);
        }
        throw Exception("network_error", "Cannot start SSH jump bridge thread");
    }
    outNextSocketFd = pair[1];
    return bridge;
}

void JumpBridge::StopAndJoin() {
    stopping_.store(true);
    if (wakeFd_[1] >= 0) {
        const char byte = 1;
        (void)::write(wakeFd_[1], &byte, 1);
    }
    if (worker_.joinable()) {
        worker_.join();
    }
    CloseResources();
}

void JumpBridge::CloseResources() {
    if (resourcesClosed_) {
        return;
    }
    resourcesClosed_ = true;
    if (localFd_ >= 0) {
        (void)::shutdown(localFd_, SHUT_RDWR);
    }
    if (transportFd_ >= 0) {
        (void)::shutdown(transportFd_, SHUT_RDWR);
    }
    if (channel_) {
        (void)libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
    if (session_) {
        (void)libssh2_session_free(session_);
        session_ = nullptr;
    }
    if (localFd_ >= 0) {
        ::close(localFd_);
        localFd_ = -1;
    }
    if (transportFd_ >= 0) {
        ::close(transportFd_);
        transportFd_ = -1;
    }
    for (int &fd : wakeFd_) {
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
    }
}

void JumpBridge::RunLoop() {
    constexpr size_t kBufferLimit = 65536;
    std::vector<uint8_t> toChannel;
    std::vector<uint8_t> toLocal;
    toChannel.reserve(16384);
    toLocal.reserve(16384);

    bool localEof = false;
    bool sentChannelEof = false;
    bool channelEof = false;
    bool sentLocalEof = false;

    uint8_t scratch[16384];

    while (!stopping_.load()) {
        bool progress = false;

        // 1. Flush pending bytes from local socket -> direct-tcpip channel
        if (!toChannel.empty()) {
            ssize_t written = libssh2_channel_write_ex(
                channel_, 0, reinterpret_cast<const char *>(toChannel.data()), toChannel.size());
            if (written > 0) {
                toChannel.erase(toChannel.begin(), toChannel.begin() + written);
                progress = true;
            } else if (written < 0 && written != LIBSSH2_ERROR_EAGAIN) {
                break;
            }
        }

        // 2. Flush pending bytes from direct-tcpip channel -> local socket
        if (!toLocal.empty()) {
            ssize_t written = ::write(localFd_, toLocal.data(), toLocal.size());
            if (written > 0) {
                toLocal.erase(toLocal.begin(), toLocal.begin() + written);
                progress = true;
            } else if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                break;
            }
        }

        // 3. Read from local socket into toChannel buffer
        if (!localEof && toChannel.size() < kBufferLimit) {
            size_t room = std::min(sizeof(scratch), kBufferLimit - toChannel.size());
            ssize_t count = ::read(localFd_, scratch, room);
            if (count > 0) {
                toChannel.insert(toChannel.end(), scratch, scratch + count);
                progress = true;
            } else if (count == 0) {
                localEof = true;
                progress = true;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                localEof = true;
                progress = true;
            }
        }

        // 4. Read from direct-tcpip channel into toLocal buffer
        if (!channelEof && toLocal.size() < kBufferLimit) {
            size_t room = std::min(sizeof(scratch), kBufferLimit - toLocal.size());
            ssize_t count = libssh2_channel_read_ex(
                channel_, 0, reinterpret_cast<char *>(scratch), room);
            if (count > 0) {
                toLocal.insert(toLocal.end(), scratch, scratch + count);
                progress = true;
            } else if (count == 0) {
                if (libssh2_channel_eof(channel_)) {
                    channelEof = true;
                    progress = true;
                }
            } else if (count != LIBSSH2_ERROR_EAGAIN) {
                break;
            } else if (libssh2_channel_eof(channel_)) {
                channelEof = true;
                progress = true;
            }
        }

        // 5. Propagate EOFs once buffers drain
        if (localEof && toChannel.empty() && !sentChannelEof) {
            int rc = libssh2_channel_send_eof(channel_);
            if (rc == 0) {
                sentChannelEof = true;
                progress = true;
            } else if (rc != LIBSSH2_ERROR_EAGAIN) {
                break;
            }
        }
        if (channelEof && toLocal.empty() && !sentLocalEof) {
            (void)::shutdown(localFd_, SHUT_WR);
            sentLocalEof = true;
            progress = true;
        }

        if (sentChannelEof && sentLocalEof) {
            break;
        }

        if (progress) {
            continue;
        }

        short localEvents = 0;
        if (!localEof && toChannel.size() < kBufferLimit) localEvents |= POLLIN;
        if (!toLocal.empty()) localEvents |= POLLOUT;

        short transportEvents = POLLIN;
        int directions = libssh2_session_block_directions(session_);
        if ((directions & LIBSSH2_SESSION_BLOCK_OUTBOUND)) {
            transportEvents |= POLLOUT;
        }

        pollfd fds[3] {
            {wakeFd_[0], POLLIN, 0},
            {localFd_, localEvents, 0},
            {transportFd_, transportEvents, 0}
        };
        int pollRc = poll(fds, 3, 50);
        if (pollRc < 0 && errno != EINTR) {
            break;
        }
        if (fds[0].revents & POLLIN) {
            char drainBuf[64];
            while (::read(wakeFd_[0], drainBuf, sizeof(drainBuf)) > 0) {}
        }
        if (fds[2].revents & (POLLERR | POLLNVAL)) {
            break;
        }
        if ((fds[2].revents & POLLHUP) && toLocal.empty() && channelEof) {
            break;
        }
    }

    if (localFd_ >= 0) {
        (void)::shutdown(localFd_, SHUT_RDWR);
    }
}

Error ClassifyDirectTcpipError(
    LIBSSH2_SESSION *session,
    int hopIndex,
    int hopCount,
    const std::string &jumpProfileId,
    const std::string &nextProfileId,
    const std::string &nextHost,
    int nextPort) {
    char *errMsg = nullptr;
    int errLen = 0;
    libssh2_session_last_error(session, &errMsg, &errLen, 0);
    std::string rawDetail = (errMsg && errLen > 0)
        ? std::string(errMsg, static_cast<size_t>(errLen))
        : "";
    std::string lower = ToLowerAscii(rawDetail);

    const std::string endpointLabel = nextHost + ":" + std::to_string(nextPort);
    if (lower.find("administratively prohibited") != std::string::npos ||
        lower.find("prohibited") != std::string::npos ||
        lower.find("forwarding disabled") != std::string::npos ||
        lower.find("not permitted") != std::string::npos) {
        return Error {
            "jump_forward_denied",
            "SSH jump host denied TCP forwarding to " + endpointLabel +
                " (AllowTcpForwarding/PermitOpen)",
            hopIndex,
            hopCount,
            jumpProfileId,
            "",
            ""
        };
    }

    return Error {
        "jump_target_unreachable",
        "SSH jump host could not connect to next hop " + endpointLabel,
        hopIndex + 1,
        hopCount,
        nextProfileId,
        "",
        ""
    };
}

} // namespace amber::ssh
