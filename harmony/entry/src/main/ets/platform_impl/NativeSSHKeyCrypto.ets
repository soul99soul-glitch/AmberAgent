import native from 'libamber_native.so';
import type { SSHKeyCryptoPort, SSHGeneratedKeyMaterial, SSHInspectedKeyMaterial } from '@amber/chat-domain';

export const createNativeSSHKeyCrypto = (): SSHKeyCryptoPort => ({
  generateKey: (name: string, type: 'ed25519' | 'ecdsa-p256'): Promise<SSHGeneratedKeyMaterial> =>
    native.sshGenerateKey({ name, type }),
  inspectKey: (name: string, privateKey: string, passphrase: string | null): Promise<SSHInspectedKeyMaterial> =>
    native.sshInspectKey({ name, privateKey, passphrase }),
});
