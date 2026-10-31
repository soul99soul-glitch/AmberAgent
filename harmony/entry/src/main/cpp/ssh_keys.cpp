#include "ssh_keys.h"
#include "ssh_core.h"
#include <libssh2.h>
#include <openssl/bio.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <algorithm>
#include <cstring>
#include <vector>

extern "C" int _libssh2_pub_priv_keyfilememory(
    LIBSSH2_SESSION *session,
    unsigned char **method,
    size_t *method_len,
    unsigned char **pubkeydata,
    size_t *pubkeydata_len,
    const char *privatekeydata,
    size_t privatekeydata_len,
    const char *passphrase);

namespace amber::ssh {
namespace {

void ClearString(std::string &value) {
    if (!value.empty()) {
        OPENSSL_cleanse(value.data(), value.size());
        value.clear();
    }
}

std::string SanitizeComment(const std::string &name) {
    std::string out;
    out.reserve(name.size());
    for (char ch : name) {
        if (ch == '\r' || ch == '\n' || ch == '\0' || ch == '\t' || ch == ' ') {
            out.push_back('-');
        } else {
            out.push_back(ch);
        }
    }
    if (out.empty()) {
        out = "key";
    }
    return "amber-" + out;
}

std::string Base64Encode(const uint8_t *data, size_t len, bool stripPadding = false) {
    if (len == 0) {
        return "";
    }
    std::vector<unsigned char> encoded(4 * ((len + 2) / 3) + 1, 0);
    int outLen = EVP_EncodeBlock(encoded.data(), data, static_cast<int>(len));
    if (outLen <= 0) {
        throw Exception("network_error", "Base64 encoding failed");
    }
    if (stripPadding) {
        while (outLen > 0 && encoded[static_cast<size_t>(outLen - 1)] == '=') {
            --outLen;
        }
    }
    return std::string(reinterpret_cast<const char *>(encoded.data()), static_cast<size_t>(outLen));
}

std::vector<uint8_t> Base64DecodeBlock(const std::string &b64) {
    std::string clean;
    clean.reserve(b64.size());
    for (char ch : b64) {
        if (ch != '\r' && ch != '\n' && ch != ' ' && ch != '\t') {
            clean.push_back(ch);
        }
    }
    if (clean.empty() || (clean.size() % 4) != 0) {
        return {};
    }
    std::vector<uint8_t> decoded((clean.size() / 4) * 3, 0);
    int len = EVP_DecodeBlock(decoded.data(),
        reinterpret_cast<const unsigned char *>(clean.data()),
        static_cast<int>(clean.size()));
    if (len < 0) {
        return {};
    }
    size_t pad = 0;
    if (!clean.empty() && clean.back() == '=') ++pad;
    if (clean.size() > 1 && clean[clean.size() - 2] == '=') ++pad;
    size_t finalLen = static_cast<size_t>(len) >= pad ? static_cast<size_t>(len) - pad : 0;
    decoded.resize(finalLen);
    return decoded;
}

void AppendU32(std::vector<uint8_t> &buf, uint32_t val) {
    buf.push_back(static_cast<uint8_t>((val >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((val >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(val & 0xFF));
}

void AppendSSHString(std::vector<uint8_t> &buf, const void *data, size_t len) {
    AppendU32(buf, static_cast<uint32_t>(len));
    const auto *bytes = static_cast<const uint8_t *>(data);
    buf.insert(buf.end(), bytes, bytes + len);
}

void AppendSSHText(std::vector<uint8_t> &buf, const std::string &str) {
    AppendSSHString(buf, str.data(), str.size());
}

std::string WrapOpenSSHPrivateKey(const std::vector<uint8_t> &blob) {
    std::string b64 = Base64Encode(blob.data(), blob.size(), false);
    std::string out = "-----BEGIN OPENSSH PRIVATE KEY-----\n";
    for (size_t i = 0; i < b64.size(); i += 70) {
        out.append(b64, i, std::min<size_t>(70, b64.size() - i));
        out.push_back('\n');
    }
    out += "-----END OPENSSH PRIVATE KEY-----\n";
    ClearString(b64);
    return out;
}

std::string MapAlgorithm(const std::string &method) {
    if (method == "ssh-ed25519") return "ed25519";
    if (method == "ecdsa-sha2-nistp256") return "ecdsa-p256";
    if (method == "ecdsa-sha2-nistp384") return "ecdsa-p384";
    if (method == "ecdsa-sha2-nistp521") return "ecdsa-p521";
    if (method == "ssh-rsa") return "rsa";
    return method;
}

std::string ComputeFingerprint(const uint8_t *pubkeyBlob, size_t pubkeyBlobLen) {
    unsigned char digest[SHA256_DIGEST_LENGTH] {};
    SHA256(pubkeyBlob, pubkeyBlobLen, digest);
    return "SHA256:" + Base64Encode(digest, SHA256_DIGEST_LENGTH, true);
}

enum class KeyEncryptionState {
    UNENCRYPTED,
    ENCRYPTED,
    UNSUPPORTED_CIPHER,
    MALFORMED
};

KeyEncryptionState DetectPrivateKeyEncryption(const std::string &privateKey) {
    if (privateKey.find("-----BEGIN ") == std::string::npos) {
        return KeyEncryptionState::MALFORMED;
    }
    if (privateKey.find("-----BEGIN ENCRYPTED PRIVATE KEY-----") != std::string::npos ||
        privateKey.find("Proc-Type: 4,ENCRYPTED") != std::string::npos) {
        return KeyEncryptionState::ENCRYPTED;
    }
    const std::string beginMarker = "-----BEGIN OPENSSH PRIVATE KEY-----";
    const std::string endMarker = "-----END OPENSSH PRIVATE KEY-----";
    size_t start = privateKey.find(beginMarker);
    if (start != std::string::npos) {
        start += beginMarker.size();
        size_t end = privateKey.find(endMarker, start);
        if (end == std::string::npos) {
            return KeyEncryptionState::MALFORMED;
        }
        std::vector<uint8_t> raw = Base64DecodeBlock(privateKey.substr(start, end - start));
        const char magic[] = "openssh-key-v1";
        constexpr size_t magicLen = sizeof(magic); // includes trailing NUL (15 bytes)
        if (raw.size() < magicLen + 4 || std::memcmp(raw.data(), magic, magicLen) != 0) {
            return KeyEncryptionState::MALFORMED;
        }
        size_t offset = magicLen;
        uint32_t cipherLen = (static_cast<uint32_t>(raw[offset]) << 24) |
                             (static_cast<uint32_t>(raw[offset + 1]) << 16) |
                             (static_cast<uint32_t>(raw[offset + 2]) << 8) |
                             static_cast<uint32_t>(raw[offset + 3]);
        offset += 4;
        if (offset + cipherLen > raw.size()) {
            return KeyEncryptionState::MALFORMED;
        }
        std::string cipher(reinterpret_cast<const char *>(raw.data() + offset), cipherLen);
        if (cipher == "none") {
            return KeyEncryptionState::UNENCRYPTED;
        }
        if (cipher.find("aes") != std::string::npos || cipher.find("3des") != std::string::npos) {
            return KeyEncryptionState::ENCRYPTED;
        }
        return KeyEncryptionState::UNSUPPORTED_CIPHER;
    }
    return KeyEncryptionState::UNENCRYPTED;
}

std::string GenerateEd25519OpenSSH(const std::string &comment) {
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, nullptr);
    if (!ctx) throw Exception("network_error", "Cannot allocate Ed25519 key context");
    EVP_PKEY *pkey = nullptr;
    if (EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_keygen(ctx, &pkey) <= 0 || !pkey) {
        EVP_PKEY_CTX_free(ctx);
        throw Exception("network_error", "Failed to generate Ed25519 key pair");
    }
    EVP_PKEY_CTX_free(ctx);

    uint8_t seed[32] {};
    uint8_t pub[32] {};
    size_t seedLen = sizeof(seed);
    size_t pubLen = sizeof(pub);
    bool ok = EVP_PKEY_get_raw_private_key(pkey, seed, &seedLen) == 1 &&
              EVP_PKEY_get_raw_public_key(pkey, pub, &pubLen) == 1 &&
              seedLen == 32 && pubLen == 32;
    EVP_PKEY_free(pkey);
    if (!ok) {
        OPENSSL_cleanse(seed, sizeof(seed));
        throw Exception("network_error", "Failed to extract Ed25519 raw key bytes");
    }

    std::vector<uint8_t> pubBlob;
    AppendSSHText(pubBlob, "ssh-ed25519");
    AppendSSHString(pubBlob, pub, sizeof(pub));

    uint32_t checkInt = 0;
    RAND_bytes(reinterpret_cast<unsigned char *>(&checkInt), sizeof(checkInt));

    uint8_t priv64[64] {};
    std::memcpy(priv64, seed, 32);
    std::memcpy(priv64 + 32, pub, 32);
    OPENSSL_cleanse(seed, sizeof(seed));

    std::vector<uint8_t> privSection;
    AppendU32(privSection, checkInt);
    AppendU32(privSection, checkInt);
    AppendSSHText(privSection, "ssh-ed25519");
    AppendSSHString(privSection, pub, sizeof(pub));
    AppendSSHString(privSection, priv64, sizeof(priv64));
    OPENSSL_cleanse(priv64, sizeof(priv64));
    AppendSSHText(privSection, comment);

    uint8_t pad = 1;
    while ((privSection.size() % 8) != 0) {
        privSection.push_back(pad++);
    }

    std::vector<uint8_t> fullBlob;
    const char magic[] = "openssh-key-v1";
    fullBlob.insert(fullBlob.end(), magic, magic + sizeof(magic));
    AppendSSHText(fullBlob, "none");
    AppendSSHText(fullBlob, "none");
    AppendSSHText(fullBlob, "");
    AppendU32(fullBlob, 1);
    AppendSSHString(fullBlob, pubBlob.data(), pubBlob.size());
    AppendSSHString(fullBlob, privSection.data(), privSection.size());
    OPENSSL_cleanse(privSection.data(), privSection.size());

    std::string result = WrapOpenSSHPrivateKey(fullBlob);
    OPENSSL_cleanse(fullBlob.data(), fullBlob.size());
    return result;
}

std::string GenerateEcdsaP256Pem() {
    EVP_PKEY *pkey = EVP_EC_gen("prime256v1");
    if (!pkey) {
        throw Exception("network_error", "Failed to generate ECDSA P-256 key pair");
    }
    BIO *bio = BIO_new(BIO_s_mem());
    if (!bio) {
        EVP_PKEY_free(pkey);
        throw Exception("network_error", "Failed to allocate key BIO");
    }
    int written = PEM_write_bio_PrivateKey(bio, pkey, nullptr, nullptr, 0, nullptr, nullptr);
    EVP_PKEY_free(pkey);
    if (written != 1) {
        BIO_free(bio);
        throw Exception("network_error", "Failed to serialize ECDSA P-256 private key");
    }
    char *data = nullptr;
    long len = BIO_get_mem_data(bio, &data);
    std::string pem(data && len > 0 ? data : "", len > 0 ? static_cast<size_t>(len) : 0);
    if (data && len > 0) {
        OPENSSL_cleanse(data, static_cast<size_t>(len));
    }
    BIO_free(bio);
    return pem;
}

} // namespace

void GeneratedKey::ClearSecrets() {
    ClearString(privateKey);
}

InspectedKey InspectKey(const std::string &name, const std::string &privateKey,
    const std::optional<std::string> &passphrase) {
    if (name.empty() || name.size() > 256 || name.find('\0') != std::string::npos) {
        throw Exception("invalid_arguments", "SSH key name must be 1 to 64 characters");
    }
    if (privateKey.empty() || privateKey.size() > 65536 || privateKey.find('\0') != std::string::npos) {
        throw Exception("unsupported_key", "SSH private key must be a valid non-empty text block");
    }
    if (passphrase && (passphrase->size() > 65536 || passphrase->find('\0') != std::string::npos)) {
        throw Exception("invalid_arguments", "SSH key passphrase is invalid");
    }

    static const int initialized = libssh2_init(0);
    if (initialized) {
        throw Exception("network_error", "Cannot initialize SSH crypto library");
    }

    KeyEncryptionState encState = DetectPrivateKeyEncryption(privateKey);
    if (encState == KeyEncryptionState::MALFORMED || encState == KeyEncryptionState::UNSUPPORTED_CIPHER) {
        throw Exception("unsupported_key", "Unsupported or malformed SSH private key format");
    }
    const bool hasPass = passphrase.has_value() && !passphrase->empty();
    if (encState == KeyEncryptionState::ENCRYPTED && !hasPass) {
        throw Exception("passphrase_required", "Encrypted SSH private key requires a passphrase");
    }

    LIBSSH2_SESSION *session = libssh2_session_init();
    if (!session) {
        throw Exception("network_error", "Cannot allocate SSH session for key inspection");
    }

    unsigned char *method = nullptr;
    size_t methodLen = 0;
    unsigned char *pubkeyData = nullptr;
    size_t pubkeyLen = 0;
    // Always pass a non-null C string so OpenSSL never falls back to /dev/tty password prompt.
    const char *passPtr = hasPass ? passphrase->c_str() : "";

    int rc = _libssh2_pub_priv_keyfilememory(
        session,
        &method,
        &methodLen,
        &pubkeyData,
        &pubkeyLen,
        privateKey.data(),
        privateKey.size(),
        passPtr);

    if (rc != 0 || !method || !pubkeyData || methodLen == 0 || pubkeyLen == 0) {
        if (method) libssh2_free(session, method);
        if (pubkeyData) libssh2_free(session, pubkeyData);
        libssh2_session_free(session);
        if (encState == KeyEncryptionState::ENCRYPTED) {
            throw Exception("invalid_passphrase", "Incorrect passphrase for encrypted SSH private key");
        }
        throw Exception("unsupported_key", "Unsupported or malformed SSH private key");
    }

    std::string methodStr(reinterpret_cast<const char *>(method), methodLen);
    std::string pubB64 = Base64Encode(pubkeyData, pubkeyLen, false);
    std::string fingerprint = ComputeFingerprint(pubkeyData, pubkeyLen);
    libssh2_free(session, method);
    libssh2_free(session, pubkeyData);
    libssh2_session_free(session);

    InspectedKey result;
    result.algorithm = MapAlgorithm(methodStr);
    result.publicKey = methodStr + " " + pubB64 + " " + SanitizeComment(name);
    result.fingerprint = std::move(fingerprint);
    result.hasPassphrase = (encState == KeyEncryptionState::ENCRYPTED);
    return result;
}

GeneratedKey GenerateKey(const std::string &name, const std::string &type) {
    if (name.empty() || name.size() > 256 || name.find('\0') != std::string::npos) {
        throw Exception("invalid_arguments", "SSH key name must be 1 to 64 characters");
    }
    const std::string algorithm = type.empty() ? "ed25519" : type;
    if (algorithm != "ed25519" && algorithm != "ecdsa-p256") {
        throw Exception("invalid_arguments", "SSH key type must be ed25519 or ecdsa-p256");
    }

    const std::string comment = SanitizeComment(name);
    GeneratedKey generated;
    generated.privateKey = (algorithm == "ed25519")
        ? GenerateEd25519OpenSSH(comment)
        : GenerateEcdsaP256Pem();

    InspectedKey inspected = InspectKey(name, generated.privateKey, std::nullopt);
    generated.algorithm = inspected.algorithm;
    generated.publicKey = std::move(inspected.publicKey);
    generated.fingerprint = std::move(inspected.fingerprint);
    return generated;
}

} // namespace amber::ssh
