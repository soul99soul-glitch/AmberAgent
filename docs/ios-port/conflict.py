import subprocess
REPO="/Users/arquiel/Downloads/AI/amberagent-ios"
MB="8ea4bdebd93b40bedc586e41a8e6f9f333780fd9"
AND="b51648c93e5621f0f3a02321bd34d3fba8908cc7"
IOS="476f8f1ecee8c8901b38dc54b3c17f510b83c2ee"
def files(a,b):
    o=subprocess.check_output(["git","-C",REPO,"diff","--name-only",f"{a}..{b}"],text=True)
    return set(x for x in o.splitlines() if x.strip())
ios=files(MB,IOS); andr=files(MB,AND)
both=ios & andr
print(f"iOS 分支改动文件数: {len(ios)}   Android 分支改动文件数: {len(andr)}")
print(f"两边都改过的文件 (合并真冲突热点): {len(both)}\n")
# 按顶层模块归并
import collections
mod=collections.Counter("/".join(f.split("/")[:2]) for f in both)
for m,n in mod.most_common(25): print(f"  {n:3d}  {m}/")
print("\n=== 重叠文件里属于「iOS 网络大迁移」碰过的 (search/tts/ai/settings/common/http) ===")
hot=[f for f in sorted(both) if f.startswith(("search/","tts/","ai/src","common/src/main/java/app/amber/common/http","core/settings/"))]
for f in hot[:60]: print("  ",f)
print(f"\n  小计热点重叠: {len(hot)}")
