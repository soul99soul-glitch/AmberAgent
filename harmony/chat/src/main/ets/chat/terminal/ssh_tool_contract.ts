export const SSH_PROFILE_TOOL_NAMES: string[] = [
  'ssh_profile_list', 'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete',
];
export const SSH_KEY_TOOL_NAMES: string[] = ['ssh_key_list', 'ssh_key_generate', 'ssh_key_import', 'ssh_key_delete'];
export const SSH_KEY_MAIN_SESSION_ONLY_TOOLS: string[] = ['ssh_key_generate', 'ssh_key_import', 'ssh_key_delete'];
export const SSH_PROFILE_MAIN_SESSION_ONLY_TOOLS: string[] = [
  'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete', 'ssh_host_trust',
  ...SSH_KEY_MAIN_SESSION_ONLY_TOOLS,
];
export const SSH_PROFILE_SECRET_FIELDS: string[] = ['password', 'private_key', 'private_key_passphrase', 'passphrase'];
export const isSSHProfileMainSessionOnly = (name: string): boolean => SSH_PROFILE_MAIN_SESSION_ONLY_TOOLS.includes(name);
