#!/usr/bin/env python3
"""输出火焰图中"包含式"热点（自顶向下看哪些调用链占比最高）。

叶子节点自耗时可能分散在很多 JDK 内部方法上，看不出业务含义；
按「根 → ... → 叶子」的完整栈聚合更有诊断价值。
"""
import collections
import re
import sys

PATH = sys.argv[1]
FILTER = sys.argv[2] if len(sys.argv) > 2 else ""
TOPN = int(sys.argv[3]) if len(sys.argv) > 3 else 25

html = open(PATH, encoding="utf-8", errors="replace").read()


def load_cpool(text):
    m = re.search(r"const cpool = \[(.*?)\n\];", text, re.S)
    if not m:
        return []
    items = re.findall(r"'((?:[^'\\]|\\.)*)'", m.group(1))
    pool = []
    for i, item in enumerate(items):
        if i == 0 or not item:
            pool.append(item)
            continue
        p = ord(item[0]) - 32
        if p < 0 or p > len(pool[i - 1]):
            p = 0
        pool.append(pool[i - 1][:p] + item[1:])
    return [re.sub(r"_[jk]$", "", x) for x in pool]


pool = load_cpool(html)
start = html.index("];", html.index("const cpool = [")) + 2
end = html.find("</script>", start)
body = html[start : end if end > 0 else len(html)]

stack = []
stack_agg = collections.Counter()
level0 = 0
width0 = 0
total = 0

for raw in body.splitlines():
    line = raw.strip()
    m = re.match(r"^([nuf])\(([^)]*)\)$", line)
    if not m:
        continue
    kind = m.group(1)
    nums = []
    for tok in m.group(2).split(","):
        tok = tok.strip()
        nums.append(int(tok) if re.fullmatch(r"-?\d+", tok) else None)
    if not nums or nums[0] is None:
        continue
    name = pool[nums[0] >> 3] if (nums[0] >> 3) < len(pool) else "?"

    if kind == "u":
        level = level0 + 1
        width = nums[1] if len(nums) > 1 and nums[1] is not None else width0
    elif kind == "n":
        level = level0
        width = nums[1] if len(nums) > 1 and nums[1] is not None else width0
    else:
        level = nums[1] if len(nums) > 1 and nums[1] is not None else level0
        width = nums[3] if len(nums) > 3 and nums[3] is not None else width0

    level0 = level
    width0 = width if width else width0

    del stack[level:]
    stack.append(name)
    if level == 0:
        total += width or 0
    # 聚合整条栈（用于包含式统计）
    if level > 0 and width:
        stack_agg[" <- ".join(reversed(stack))] += width

print("总样本 ≈", total)
print()
print("=== 包含式热点（完整调用链）===")
shown = 0
for chain, cnt in stack_agg.most_common(400):
    if FILTER and FILTER not in chain:
        continue
    pct = cnt / total * 100 if total else 0
    if pct < 0.5:
        break
    # 只显示业务相关的中段，避免超长
    parts = chain.split(" <- ")
    short = parts if len(parts) <= 14 else (parts[:6] + ["..."] + parts[-5:])
    print("%7.2f%%  %s" % (pct, " <- ".join(short)))
    shown += 1
    if shown >= TOPN:
        break
if shown == 0:
    print("（无匹配 %r 的调用链）" % FILTER)
