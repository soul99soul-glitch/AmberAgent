// settings-runtime.jsx — Agent 运行环境

function RuntimeEnvScreen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="Agent 运行环境" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        <SubGroupLabel t={t} style={{ paddingLeft: 4 }}>Workspace</SubGroupLabel>
        <SubCard t={t}>
          <div style={{ padding: '14px 14px', display: 'flex', alignItems: 'flex-start', gap: 14 }}>
            <IconCircle t={t}><XIcon kind="folder" color={t.inkSoft} /></IconCircle>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>Agent Workspace</div>
              <div style={{ marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>
                选择一个目录，AmberAgent 会将它作为文件工具和命令运行的 /workspace。
              </div>
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ padding: '4px 10px', borderRadius: 999, background: t.accentSoft, color: t.accent, fontSize: 11.5, fontWeight: 500, letterSpacing: 0.3 }}>已授权</span>
                <span style={{ fontSize: 13, color: t.ink, fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace' }}>Amber</span>
              </div>
            </div>
          </div>
        </SubCard>

        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 22 }}>运行时</SubGroupLabel>
        <SubCard t={t}>
          <div style={{
            padding: '12px 14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
            borderBottom: `1px solid ${t.hair}`,
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ width: 6, height: 6, borderRadius: '50%', background: '#5DBE8A' }} />
              <span style={{ fontSize: 13.5, color: t.ink, letterSpacing: 0.2 }}>终端运行环境已就绪</span>
            </div>
            <span style={{
              padding: '5px 12px', borderRadius: 999, background: t.accent, color: '#FFFFFF',
              fontSize: 11.5, fontWeight: 500, letterSpacing: 0.4,
            }}>修复</span>
          </div>
          <IconRow t={t} icon={<XIcon kind="braces" color={t.inkSoft}/>} title="默认运行时"
            desc="终端 job 默认使用的运行时，工具调用可单次覆盖。" right={<ValueChip t={t} value="Alpine" />} />
          <HairDivider t={t} indent={60} />
          <IconRow t={t} icon={<XIcon kind="server" color={t.inkSoft}/>} title="并发 Job"
            desc="默认 1，避免并发写 /workspace。" right={<ValueChip t={t} value="1 个" />} />
          <HairDivider t={t} indent={60} />
          <IconRow t={t} icon={<XIcon kind="braces" color={t.inkSoft}/>} title="输出尾部"
            desc="工具结果保留的输出尾部大小，完整日志仍保存在应用目录。" right={<ValueChip t={t} value="256 KB" />} />
        </SubCard>
      </div>
    </SubShell>
  );
}

window.RuntimeEnvScreen = RuntimeEnvScreen;
