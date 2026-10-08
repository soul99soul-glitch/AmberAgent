"""Summarize app-thread synchronous slices from an hdc hitrace text capture."""
import collections
import json
import re
import sys

trace, pid = sys.argv[1], int(sys.argv[2])
pattern = re.compile(r"^\s*.*?-(\d+)\s+\(\s*(\d+)\)\s+\[\d+\].*?\s(\d+\.\d+): tracing_mark_write: (.*)$")
stacks = collections.defaultdict(list)
samples = collections.defaultdict(list)
starts = []
for line in open(trace):
    if "tracing_mark_write:" not in line:
        continue
    match = pattern.match(line)
    if not match or int(match[2]) != pid:
        continue
    tid, timestamp, event = int(match[1]), float(match[3]), match[4]
    if event.startswith("B|"):
        name = event.split("|", 3)[2]
        stacks[tid].append((name, timestamp))
        starts.append(timestamp)
    elif event.startswith("E|") and stacks[tid]:
        name, begin = stacks[tid].pop()
        elapsed = (timestamp - begin) * 1000
        if elapsed < 0:
            continue
        if "CreateTaskMeasure[Text]" in name:
            name = "Text.Measure"
        elif "CreateTaskMeasure[List]" in name:
            name = "List.Measure"
        elif "CreateTaskLayout[List]" in name:
            name = "List.Layout"
        elif "TextLayoutAlgorithm" in name:
            name = name.split("[")[0]
        elif "::MeasureListItem" in name:
            name = "List.MeasureItem"
        elif name.startswith("H:CustomNodeUpdate name:"):
            name = name.split(",id:")[0]
        samples[name].append(elapsed)

def summary(values):
    ordered = sorted(values)
    return {"count": len(values), "total_ms": round(sum(values), 3),
            "p95_ms": round(ordered[int((len(values)-1)*.95)], 3),
            "max_ms": round(max(values), 3)}

selected = {}
for name, values in samples.items():
    if name in ["Text.Measure", "List.Measure", "List.Layout", "List.MeasureItem",
                "H:FlushLayoutTask", "H:FlushAnimation", "H:ExecuteJS", "H:DispatchDisplaySync"] or "CustomNodeUpdate name:" in name or "TextLayoutAlgorithm" in name:
        selected[name] = summary(values)
print(json.dumps({"pid": pid, "span_seconds": round(max(starts)-min(starts), 3) if starts else 0,
                  "slices": selected}, ensure_ascii=False, indent=2))
