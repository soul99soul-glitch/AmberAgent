#ifndef AMBER_SSH_KEYS_H
#define AMBER_SSH_KEYS_H

#include <optional>
#include <string>

namespace amber::ssh {

struct GeneratedKey {
    std::string algorithm;
    std::string publicKey;
    std::string fingerprint;
    std::string privateKey;
    ~GeneratedKey() { ClearSecrets(); }
    void ClearSecrets();
};

struct InspectedKey {
    std::string algorithm;
    std::string publicKey;
    std::string fingerprint;
    bool hasPassphrase = false;
};

GeneratedKey GenerateKey(const std::string &name, const std::string &type);
InspectedKey InspectKey(const std::string &name, const std::string &privateKey,
    const std::optional<std::string> &passphrase);

} // namespace amber::ssh

#endif
