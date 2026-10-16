#!/usr/bin/env python3
"""解析 async-profiler 4.x 的 HTML 火焰图，输出自耗时最高的方法。

格式（由 HTML 内的 JS 逆推，已核对）：
  * `const cpool = [...]`：帧名池，采用「前缀长度 + 后缀」增量编码：
      cpool[i] 首字符的 charCode-32 = 复用 cpool[i-1] 的前缀长度。
    尾部 `_j`/`_k` 是 Java/内核标记。
  * 其后是紧凑指令流，逐条对应一个帧：
      n(key, width, ...)  level = 当前栈深（同级兄弟）
      u(key, width, ...)  level = 当前栈深 + 1（子帧）
      f(key, level, left, width, ...)  显式层级
    **key 的低 3 位是颜色**，真实池索引 = key >>> 3。
    width 为 0/缺省时表示"沿用父帧宽度"（即唯一子节点）。
  * 帧的自耗时 = 自身宽度 − 直接子帧宽度之和。
"""
import collections
import re
import sys

PATH = sys.argv[1]
TOPN = int(sys.argv[2]) if len(sys.argv) > 2 else 30
KIND = sys.argv[3] if len(sys.argv) > 3 else ""

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
        prefix_len = ord(item[0]) - 32
        suffix = item[1:]
        if prefix_len < 0 or prefix_len > len(pool[i - 1]):
            prefix_len = 0
        pool.append(pool[i - 1][:prefix_len] + suffix)
    return [re.sub(r"_[jk]$", "", p) for p in pool]


def frame_name(pool, key):
    idx = key >> 3
    if 0 <= idx < len(pool):
        return pool[idx]
    return "?idx=%d" % idx


pool = load_cpool(html)
if not pool:
    print("未找到 cpool")
    sys.exit(1)
print("帧名池大小 =", len(pool))

start = html.index("];", html.index("const cpool = [")) + 2
end = html.find("</script>", start)
body = html[start : end if end > 0 else len(html)]


def parse_instructions(text):
    """把指令流解析为 (name, width, level) 序列。"""
    out = []
    level0 = 0
    width0 = 0
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r"^([nuf])\(([^)]*)\)$", line)
        if not m:
            continue
        kind = m.group(1)
        nums = []
        for tok in m.group(2).split(","):
            tok = tok.strip()
            if re.fullmatch(r"-?\d+", tok):
                nums.append(int(tok))
            else:
                nums.append(None)
        if not nums or nums[0] is None:
            continue
        key = nums[0]
        name = frame_name(pool, key)

        if kind == "u":
            level = level0 + 1
            left = 0
            width = nums[1] if len(nums) > 1 and nums[1] is not None else width0
        elif kind == "n":
            level = level0
            width = nums[1] if len(nums) > 1 and nums[1] is not None else width0
            left = width0
        else:  # f
            level = nums[1] if len(nums) > 1 and nums[1] is not None else level0
            left = nums[2] if len(nums) > 2 and nums[2] is not None else 0
            w = nums[3] if len(nums) > 3 and nums[3] is not None else None
            width = w if w else width0

        level0 = level
        width0 = width if width else width0
        out.append((name, width or 0, level))
    return out


frames = parse_instructions(body)
print("帧条目数 =", len(frames))

total = 0
for name, width, level in frames:
    if level == 0:
        total += width
print("总样本数 ≈", total)

# 计算自耗时：需要在"栈"语义下累加子帧宽度
stack = []
children_sum = collections.Counter()
for name, width, level in frames:
    if level > 0 and level <= len(stack):
        children_sum[stack[level - 1]] += width
    del stack[level:]
    stack.append(name)

self_time = collections.Counter()
for name, width, level in frames:
    own = width - children_sum.get(name, 0)
    if own > 0:
        self_time[name] += own

print()
print("=== %s 自耗时 Top %d ===" % (KIND or "样本", TOPN))
for name, cnt in self_time.most_common(TOPN):
    pct = (cnt / total * 100) if total else 0
    print("%9d  %6.2f%%  %s" % (cnt, pct, name))
