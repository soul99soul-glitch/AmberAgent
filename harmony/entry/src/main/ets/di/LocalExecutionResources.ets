// Owns SSH credentials/profiles and the terminal, Python and Mosh runtime initialization promises.
import type { KeyValueStore, SSHCredentialStorePort, PythonTransportPort } from '@amber/chat-domain';
import { AgentTaskStore, SSHProfileStore, TerminalRuntime, SSHTargetResolver, PythonRuntime, MoshRuntime } from '@amber/chat-domain';
import { createEntrySSHCredentialStore } from '../platform_impl/SSHCredentialStore.ets';
import { createNativeSSHTransport } from '../platform_impl/NativeSSHTransport.ets';
import { createNativePythonTransport } from '../platform_impl/NativePythonTransport.ets';
import { createNativeMoshTransport } from '../platform_impl/NativeMoshTransport.ets';
import { createEntryTerminalLogPort } from '../platform_impl/TerminalLogPort.ets';
export interface LocalExecutionResourcesPorts {
    getChatKvStore: () => KeyValueStore;
    getAgentTaskStore: () => Promise<AgentTaskStore>;
}
export interface LocalExecutionResources {
    getSSHProfileStore: () => Promise<SSHProfileStore>;
    getTerminalRuntime: () => Promise<TerminalRuntime>;
    getPythonRuntime: () => Promise<PythonRuntime>;
    getMoshRuntime: () => Promise<MoshRuntime>;
    interruptTerminalForBackground: () => Promise<void>;
}
export const createLocalExecutionResources = (ports: LocalExecutionResourcesPorts): LocalExecutionResources => {
    let sshCredentialStore: SSHCredentialStorePort | null = null;
    let sshProfileStorePromise: Promise<SSHProfileStore> | null = null;
    let terminalRuntimePromise: Promise<TerminalRuntime> | null = null;
    let pythonRuntimePromise: Promise<PythonRuntime> | null = null;
    let moshRuntimePromise: Promise<MoshRuntime> | null = null;
    const getSSHCredentials = (): SSHCredentialStorePort => {
        if (sshCredentialStore === null)
            sshCredentialStore = createEntrySSHCredentialStore();
        return sshCredentialStore;
    };
    const getSSHProfileStore = (): Promise<SSHProfileStore> => {
        if (sshProfileStorePromise === null) {
            const init: Promise<SSHProfileStore> = SSHProfileStore.create({
                kv: ports.getChatKvStore(), credentials: getSSHCredentials(),
            });
            init.catch((): void => {
                if (sshProfileStorePromise === init)
                    sshProfileStorePromise = null;
            });
            sshProfileStorePromise = init;
        }
        return sshProfileStorePromise;
    };
    const getTerminalRuntime = (): Promise<TerminalRuntime> => {
        if (terminalRuntimePromise === null) {
            const init: Promise<TerminalRuntime> = getSSHProfileStore().then(async (profiles: SSHProfileStore): Promise<TerminalRuntime> => new TerminalRuntime({
                profiles, credentials: getSSHCredentials(), transport: createNativeSSHTransport(),
                logs: createEntryTerminalLogPort(), taskStore: await ports.getAgentTaskStore(),
            }));
            init.catch((): void => {
                if (terminalRuntimePromise === init)
                    terminalRuntimePromise = null;
            });
            terminalRuntimePromise = init;
        }
        return terminalRuntimePromise;
    };
    const getPythonRuntime = (): Promise<PythonRuntime> => {
        if (pythonRuntimePromise === null) {
            const init: Promise<PythonRuntime> = createNativePythonTransport().then(async (transport: PythonTransportPort): Promise<PythonRuntime> => new PythonRuntime({
                transport, taskStore: await ports.getAgentTaskStore(),
            }));
            init.catch((): void => {
                if (pythonRuntimePromise === init)
                    pythonRuntimePromise = null;
            });
            pythonRuntimePromise = init;
        }
        return pythonRuntimePromise;
    };
    const getMoshRuntime = (): Promise<MoshRuntime> => {
        if (moshRuntimePromise === null) {
            const init: Promise<MoshRuntime> = getSSHProfileStore().then(async (profiles: SSHProfileStore): Promise<MoshRuntime> => new MoshRuntime({
                targetResolver: new SSHTargetResolver({ profiles, credentials: getSSHCredentials() }),
                sshTransport: createNativeSSHTransport(), transport: createNativeMoshTransport(),
                taskStore: await ports.getAgentTaskStore(),
            }));
            init.catch((): void => {
                if (moshRuntimePromise === init)
                    moshRuntimePromise = null;
            });
            moshRuntimePromise = init;
        }
        return moshRuntimePromise;
    };
    // Do not initialize a runtime merely because the application entered background.
    const interruptTerminalForBackground = (): Promise<void> => {
        const operations: Promise<void>[] = [];
        if (terminalRuntimePromise !== null)
            operations.push(terminalRuntimePromise.then((runtime: TerminalRuntime): Promise<void> => runtime.interruptForBackground()));
        if (pythonRuntimePromise !== null)
            operations.push(pythonRuntimePromise.then((runtime: PythonRuntime): Promise<void> => runtime.interruptForBackground()));
        if (moshRuntimePromise !== null)
            operations.push(moshRuntimePromise.then((runtime: MoshRuntime): Promise<void> => runtime.interruptForBackground()));
        return Promise.all(operations).then((): void => { });
    };
    return { getSSHProfileStore, getTerminalRuntime, getPythonRuntime, getMoshRuntime, interruptTerminalForBackground };
};
