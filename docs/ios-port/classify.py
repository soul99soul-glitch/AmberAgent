import subprocess, collections

REPO = "/Users/arquiel/Downloads/AI/amberagent-ios"
MB = "8ea4bdebd93b40bedc586e41a8e6f9f333780fd9"

out = subprocess.check_output(
    ["git","-C",REPO,"log","--no-merges","--format=__C__%h|%s",
     "--name-only", f"{MB}..HEAD"], text=True)

FORBIDDEN = ("main/","legacy/","jank-opt/","ui-graphite/","arch/","OpenOmniBot/")
IOS_NEW_MODULES = ("shared/","ai-core/","ai-provider-openai/",
                   "core/types/","core/conversation-storage/","core/native/","native/")

def bucket(f):
    if f.startswith(FORBIDDEN): return "SNAPSHOT"        # 禁区副本目录
    if f.startswith("iosApp/"): return "IOS_UI"
    if "/iosMain/" in f or f.endswith(".ios.kt"): return "IOS_PLATFORM"
    if f.startswith(IOS_NEW_MODULES): return "IOS_NEW_MODULE"
    if f.startswith("docs/") or f.endswith(".md"): return "DOCS"
    if "/androidMain/" in f: return "ANDROID"
    if "/commonMain/" in f or "/src/main/" in f:
        # 共享内核 (core/feature 的跨平台/通用代码) —— 影响 Android
        top = "/".join(f.split("/")[:3])
        return ("SHARED_CORE", top)
    if f.endswith("build.gradle.kts") or f.startswith("settings.gradle") \
       or f.startswith("gradle/") or f.endswith(".toml"): return "BUILD"
    return "OTHER"

commits=[]
cur=None
for line in out.splitlines():
    if line.startswith("__C__"):
        h,s=line[5:].split("|",1)
        cur={"h":h,"s":s,"files":[],"buckets":collections.Counter(),"shared_mods":set()}
        commits.append(cur)
    elif line.strip() and cur is not None:
        cur["files"].append(line)
        b=bucket(line)
        if isinstance(b,tuple):
            cur["buckets"]["SHARED_CORE"]+=1
            cur["shared_mods"].add(b[1])
        else:
            cur["buckets"][b]+=1

# commit 级分类
PURE_OK = {"IOS_UI","IOS_PLATFORM","IOS_NEW_MODULE","DOCS","BUILD","OTHER","SNAPSHOT"}
pure_ios=[]; touch_shared=[]; android_only=[]
for c in commits:
    bs=set(c["buckets"])
    if "SHARED_CORE" in bs or "ANDROID" in bs:
        touch_shared.append(c)
    else:
        pure_ios.append(c)

print(f"merge-base..HEAD 总提交: {len(commits)}")
print(f"  纯 iOS (仅 iosApp/iosMain/新模块/docs/build, 可整体迁移): {len(pure_ios)}")
print(f"  触及共享内核或 Android (需逐个 review):              {len(touch_shared)}")

# 被触及的共享模块排名
modcnt=collections.Counter()
for c in touch_shared:
    for m in c["shared_mods"]: modcnt[m]+=1
print("\n=== 被触及的共享模块 (按 commit 数, 这些改动会影响 Android) ===")
for m,n in modcnt.most_common(30):
    print(f"  {n:3d}  {m}")

# 混合提交: 同一 commit 既动 iOS UI 又动共享内核 —— 合并时最难拆
mixed=[c for c in touch_shared if c["buckets"].get("IOS_UI",0)>0]
print(f"\n=== 混合提交 (同一 commit 既改 iosApp 又改共享内核, 合并需手工拆分): {len(mixed)} ===")
for c in mixed[:40]:
    mods=",".join(sorted(m.split('/')[-1] for m in c["shared_mods"]))
    print(f"  {c['h']}  [{mods}]  {c['s'][:70]}")
