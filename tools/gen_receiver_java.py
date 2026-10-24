#!/usr/bin/env python3
"""Generate Receiver.java message-type constants from C++ Message.h.

C++ enum MASSAGER is the single source of truth: native pushes it as an int into
the JNI queue, so the Java constants must match its declaration order. Do not
edit Receiver.java by hand; build.sh reruns this before packaging.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HDR = ROOT / "app" / "src/main" / "cpp" / "message" / "Message.h"
OUT = ROOT / "app" / "src/main" / "java" / "com" / "tsymiar" / "device2device" / "entity" / "Receiver.java"


def parse_massager(src):
    """Return the MASSAGER enum identifiers, in declaration order."""
    m = re.search(r"enum\s+MASSAGER\s*\{(.*?)\};", src, re.S)
    if not m:
        raise SystemExit("error: enum MASSAGER not found in %s" % HDR)
    inner = m.group(1)
    inner = re.sub(r"/\*.*?\*/", "", inner, flags=re.S)  # block comments
    inner = re.sub(r"//[^\n]*", "", inner)               # line comments
    names = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", inner)
    if not names:
        raise SystemExit("error: enum MASSAGER is empty in %s" % HDR)
    # enum values are positional: MASSAGER name N == int value N
    return names


def render(names):
    consts = "\n".join(
        "    public static final int %s = %d;" % (name, i)
        for i, name in enumerate(names)
    )
    return '''package com.tsymiar.device2device.entity;

/**
 * 消息类型常量：由 tools/gen_receiver_java.py 依 C++ Message.h 的 enum MASSAGER 生成，请勿手改。
 * 取值必须与 C++ 声明顺序一致（native 直接传 int）；改 C++ 后重跑脚本即可（build.sh 打包前自动跑）。
 */
public class Receiver {
%s
    public int receiver;
    public String message;

    public Receiver() {
        message = null;
    }
}
''' % consts


def main():
    if not HDR.exists():
        raise SystemExit("error: %s not found" % HDR)
    names = parse_massager(HDR.read_text(encoding="utf-8"))
    OUT.write_text(render(names), encoding="utf-8")
    print("generated %s with %d constants: %s"
          % (OUT, len(names), ", ".join(names)))


if __name__ == "__main__":
    main()
