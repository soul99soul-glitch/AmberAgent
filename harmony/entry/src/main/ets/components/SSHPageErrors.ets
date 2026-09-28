import type { SSHNativeError } from '@amber/chat-domain';

export const sshPageErrorMessage = (message: string, code: string | null = null): string => {
  const key: string = code ?? message;
  if (key === 'invalid_arguments') return '请检查配置名称、主机和用户名，端口须为 1–65535 的整数';
  if (key === 'authentication_failed') return '认证失败，请核对用户名、密码或私钥及其口令';
  if (key === 'host_key_mismatch') return '主机指纹已变化，请在 SSH 配置中重新核对后再连接';
  if (key === 'profile_conflict') return '配置已更改，请取消并重新打开编辑';
  if (key === 'network_error') return '远端连接已断开，请检查网络后重新连接';
  if (key === 'mosh_server_missing') return '远端未找到 mosh-server，请先安装并确认它在 PATH 中';
  if (key === 'mosh_bootstrap_invalid' || key === 'mosh_bootstrap_failed' || key === 'mosh_bootstrap_too_large') {
    return 'Mosh 启动失败，请检查远端 mosh-server 和所选 UTF-8 区域';
  }
  if (key === 'udp_connection_timeout') return 'Mosh UDP 未响应，请检查服务端 UDP 端口和防火墙';
  if (key === 'background_interrupted') return '连接已在后台中断，请重新连接';
  return message;
};

export const sshPageError = (error: Error): string =>
  sshPageErrorMessage(error.message, (error as SSHNativeError).code ?? null);
