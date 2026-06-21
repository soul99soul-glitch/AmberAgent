import subprocess, collections
REPO="/Users/arquiel/Downloads/AI/amberagent-ios"
MB="8ea4bdebd93b40bedc586e41a8e6f9f333780fd9"
out=subprocess.check_output(["git","-C",REPO,"log","--no-merges","--reverse","--format=__C__%h|%s","--name-only",f"{MB}..HEAD"],text=True)
FORB=("main/","legacy/","jank-opt/","ui-graphite/","arch/","OpenOmniBot/")
NEW=("shared/","ai-core/","ai-provider-openai/","core/types/","core/conversation-storage/","core/native/","native/")
def is_shared(f):
    if f.startswith(FORB) or f.startswith("iosApp/") or "/iosMain/" in f or f.endswith(".ios.kt"): return False
    if f.startswith(NEW) or f.startswith("docs/") or f.endswith(".md"): return False
    if "/androidMain/" in f: return True
    if "/commonMain/" in f or "/src/main/" in f: return True
    return False
commits=[];cur=None
for l in out.splitlines():
    if l.startswith("__C__"):
        h,s=l[5:].split("|",1);cur={"h":h,"s":s,"sf":[]};commits.append(cur)
    elif l.strip() and cur and is_shared(l): cur["sf"].append("/".join(l.split("/")[:3]))
C={"2488e2536","f38596f6d"}
A=[c for c in commits if not c["sf"]]
BC=[c for c in commits if c["sf"]]
B=[c for c in BC if c["h"] not in C]
Cc=[c for c in BC if c["h"] in C]
print(f"A纯iOS={len(A)}  B良性下沉={len(B)}  C网络迁移={len(Cc)}\n")
print("### B 档 15 个 (KMP 下沉, 逐个验 Android 不回归)")
for c in B:
    print(f"- `{c['h']}` {c['s'][:68]}  → {','.join(sorted(set(m.split('/')[-1] for m in c['sf'])))}")
print("\n### A 档涉及顶层目录分布")
top=collections.Counter()
o2=subprocess.check_output(["git","-C",REPO,"log","--no-merges","--format=","--name-only",f"{MB}..HEAD"],text=True)
