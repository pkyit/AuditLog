#!/bin/bash
# 汇总真 A/B 的逐轮结果
set -u
echo "=== 逐轮原始值 ==="
for m in base opt; do
  echo "[$m]"
  for f in /root/bench/realab-$m/run*.txt; do
    [ -f "$f" ] || continue
    rps=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
    p99=$(awk '/^[[:space:]]+99%/{print $2}' "$f")
    printf '  %-10s rps=%-12s p99=%s\n' "$(basename "$f")" "${rps:-n/a}" "${p99:-n/a}"
  done
done

echo
echo "=== 统计 ==="
python3 - <<'PY'
import glob, re, statistics
def collect(d):
    rps, p99 = [], []
    for f in sorted(glob.glob(d + "/run*.txt")):        t = open(f, errors="replace").read()
        m = re.search(r"Requests/sec:\s+([\d.]+)", t)
        if m: rps.append(float(m.group(1)))
        m2 = re.search(r"^\s+99%\s+([\d.]+)(ms|s)", t, re.M)
        if m2: p99.append(float(m2.group(1)) * (1000 if m2.group(2) == "s" else 1))
    return rps, p99

res = {}
for mode, label in (("base", "优化前 (b32ba0a)"), ("opt", "优化后 (当前)")):
    rps, p99 = collect("/root/bench/realab-%s" % mode)
    res[mode] = statistics.median(rps) if rps else 0
    if rps:
        print("%-18s n=%d  中位数=%8.0f rps  min=%8.0f max=%8.0f  波动=%4.1f%%  P99中位=%5.0fms"
              % (label, len(rps), statistics.median(rps), min(rps), max(rps),
                 (max(rps) - min(rps)) / statistics.median(rps) * 100,
                 statistics.median(p99) if p99 else -1))
    else:
        print("%-18s 无有效样本" % label)

if res.get("base") and res.get("opt"):
    print()
    print("优化后相对优化前: %+.1f%%" % ((res["opt"] - res["base"]) / res["base"] * 100))
PY
