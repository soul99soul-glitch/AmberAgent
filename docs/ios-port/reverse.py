import subprocess, collections
REPO="/Users/arquiel/Downloads/AI/amberagent"
MB="8ea4bdebd93b40bedc586e41a8e6f9f333780fd9"
AND="b51648c93e5621f0f3a02321bd34d3fba8908cc7"
out=subprocess.check_output(["git","-C",REPO,"log","--no-merges","--reverse","--format=__C__%h|%s","--name-only",f"{MB}..{AND}"],text=True)
def cat(f):
    if f.startswith("docs/") or f.endswith(".md"): return "DOC"
    if f.startswith("app/"): return "APP"      # Android UI/VM/service
    if f.endswith(".gradle.kts") or f.startswith("gradle/") or f.endswith(".toml"): return "BUILD"
    # 共享内核/能力层 —— iOS 需评估是否补齐
    if f.split("/")[0] in ("core","feature","ai","ai-core","search","tts","common","document","highlight","native","locale-tui"):
        return "KERNEL"
    return "OTHER"
commits=[];cur=None
for l in out.splitlines():
    if l.startswith("__C__"):
        h,s=l[5:].split("|",1);cur={"h":h,"s":s,"c":collections.Counter(),"mods":set()};commits.append(cur)
    elif l.strip() and cur:
        c=cat(l);cur["c"][c]+=1
        if c=="KERNEL": cur["mods"].add("/".join(l.split("/")[:2]))
print(f"Android 领先 merge-base 提交: {len(commits)}")
kernel=[c for c in commits if c["c"]["KERNEL"]>0]
apponly=[c for c in commits if c["c"]["KERNEL"]==0 and c["c"]["APP"]>0]
docbuild=[c for c in commits if c["c"]["KERNEL"]==0 and c["c"]["APP"]==0]
print(f"  触及共享内核/能力层 (iOS 需评估补齐): {len(kernel)}")
print(f"  仅 app 模块 (Android UI/VM, iOS 重写不直接搬, 仅对齐语义): {len(apponly)}")
print(f"  仅 docs/build/其它: {len(docbuild)}\n")
print("=== 触及内核的 Android 提交 (iOS 补齐候选) ===")
for c in kernel:
    print(f"- `{c['h']}` {c['s'][:62]}  → {','.join(sorted(set(m.split('/')[-1] for m in c['mods'])))}")
