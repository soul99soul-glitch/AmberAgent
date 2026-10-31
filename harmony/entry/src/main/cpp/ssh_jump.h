#ifndef AMBER_SSH_JUMP_H
#define AMBER_SSH_JUMP_H

#include "ssh_core.h"
#include <libssh2.h>
#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace amber::ssh {

class JumpBridge {
public:
    JumpBridge(int transportFd, LIBSSH2_SESSION *session, LIBSSH2_CHANNEL *channel,
        int localFd, int wakeReadFd, int wakeWriteFd);
    ~JumpBridge();
    JumpBridge(const JumpBridge &) = delete;
    JumpBridge &operator=(const JumpBridge &) = delete;

    static std::unique_ptr<JumpBridge> CreateAndStart(
        int transportFd,
        LIBSSH2_SESSION *session,
        LIBSSH2_CHANNEL *channel,
        int &outNextSocketFd);

    void StopAndJoin();

private:
    void RunLoop();
    void CloseResources();

    int transportFd_ = -1;
    LIBSSH2_SESSION *session_ = nullptr;
    LIBSSH2_CHANNEL *channel_ = nullptr;
    int localFd_ = -1;
    int wakeFd_[2] {-1, -1};
    std::atomic<bool> stopping_ {false};
    std::thread worker_;
    bool resourcesClosed_ = false;
};

Error ClassifyDirectTcpipError(
    LIBSSH2_SESSION *session,
    int hopIndex,
    int hopCount,
    const std::string &jumpProfileId,
    const std::string &nextProfileId,
    const std::string &nextHost,
    int nextPort);

} // namespace amber::ssh

#endif
