# JSch 2.28.7 resolves these implementations from its string config with
# Class.forName(...).getDeclaredConstructor().newInstance(). Keep the exact
# implementations used by the Android SSH backend, including their names and
# members. The backend fixes KEX/host-key/public-key algorithms in SshClient.kt
# and deliberately does not enable Bouncy Castle, GSSAPI, compression, or agent
# implementations.

# KEX selected by SshClient.SUPPORTED_KEX.
-keep class com.jcraft.jsch.DHEC256 { *; }
-keep class com.jcraft.jsch.DHEC384 { *; }
-keep class com.jcraft.jsch.DHEC521 { *; }
-keep class com.jcraft.jsch.DHGEX256 { *; }
-keep class com.jcraft.jsch.DHG16 { *; }
-keep class com.jcraft.jsch.DHG18 { *; }
-keep class com.jcraft.jsch.DHG14256 { *; }

# JCA primitives used by the selected KEX and host-key algorithms.
-keep class com.jcraft.jsch.jce.DH { *; }
-keep class com.jcraft.jsch.jce.ECDHN { *; }
-keep class com.jcraft.jsch.jce.Random { *; }
-keep class com.jcraft.jsch.jce.SHA1 { *; }
-keep class com.jcraft.jsch.jce.SHA256 { *; }
-keep class com.jcraft.jsch.jce.SHA384 { *; }
-keep class com.jcraft.jsch.jce.SHA512 { *; }
-keep class com.jcraft.jsch.jce.MD5 { *; }

# Session ciphers and legacy/private-key decryption ciphers.
-keep class com.jcraft.jsch.jce.AES128GCM { *; }
-keep class com.jcraft.jsch.jce.AES256GCM { *; }
-keep class com.jcraft.jsch.jce.AES128CTR { *; }
-keep class com.jcraft.jsch.jce.AES192CTR { *; }
-keep class com.jcraft.jsch.jce.AES256CTR { *; }
-keep class com.jcraft.jsch.jce.AES128CBC { *; }
-keep class com.jcraft.jsch.jce.AES192CBC { *; }
-keep class com.jcraft.jsch.jce.AES256CBC { *; }

# Session MACs (JSch skips these for the AEAD ciphers above).
-keep class com.jcraft.jsch.jce.HMACSHA1 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA1ETM { *; }
-keep class com.jcraft.jsch.jce.HMACSHA256 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA256ETM { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512ETM { *; }

# Host-key verification and RSA/ECDSA private-key authentication.
-keep class com.jcraft.jsch.jce.SignatureECDSA256 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA384 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA521 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA256 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA512 { *; }

# Encrypted private-key formats supported by JSch's byte-array loader.
-keep class com.jcraft.jsch.jce.PBKDF2 { *; }
-keep class com.jcraft.jsch.jbcrypt.JBCrypt { *; }

# Session.connect() always probes userauth.none before the selected method.
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPassword { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
