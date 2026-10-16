#!/bin/bash
# 在 103 上下载 wrk 源码，供 102 本地编译（102 无法访问 GitHub）
set -u
cd /root || exit 1
rm -f wrk-4.2.0.tar.gz

echo "=== 下载 wrk 4.2.0 源码 ==="
for URL in \
  "https://github.com/wg/wrk/archive/refs/tags/4.2.0.tar.gz" \
  "https://codeload.github.com/wg/wrk/tar.gz/refs/tags/4.2.0" \
  "https://gitee.com/mirrors/wrk/repository/archive/4.2.0.tar.gz" ; do
  echo "  尝试: $URL"
  if curl -sL -m 90 -o wrk-4.2.0.tar.gz "$URL"; then
    SIZE=$(stat -c%s wrk-4.2.0.tar.gz)
    if [ "$SIZE" -gt 20000 ]; then
      echo "  成功，大小 $SIZE 字节"
      break
    fi
    echo "  文件过小（$SIZE），继续尝试"
    rm -f wrk-4.2.0.tar.gz
  fi
done

if [ ! -f wrk-4.2.0.tar.gz ]; then
  echo "!! 全部来源失败"
  exit 1
fi

echo
echo "=== 校验内容 ==="
tar -tzf wrk-4.2.0.tar.gz | head -8
ls -lh wrk-4.2.0.tar.gz
