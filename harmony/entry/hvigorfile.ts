import * as ohosPlugin from '@ohos/hvigor-ohos-plugin';

// Hvigor 6.26.2 exports this hook at runtime but omits it from its .d.ts.
// Keep the SDK intact when its bundled Ninja cannot execute on the build host.
interface NativeCommandHooks {
  CommandBuilderType: { NINJA: number };
  registryCommandModifier(type: number, modifier: { modify(command: string[]): string[] }): void;
}
const nativeNinja = process.env.AMBER_NATIVE_NINJA;
if (nativeNinja) {
  const hooks = ohosPlugin as typeof ohosPlugin & NativeCommandHooks;
  hooks.registryCommandModifier(hooks.CommandBuilderType.NINJA, {
    modify: (command: string[]): string[] => [nativeNinja, ...command.slice(1)],
  });
}

export default {
  system: ohosPlugin.hapTasks,
  plugins: [],
};
