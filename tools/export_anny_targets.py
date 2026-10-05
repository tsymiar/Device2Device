#!/usr/bin/env python3
"""export_anny_targets.py — Offline exporter for the Anny high-res avatar engine.

Anny (https://github.com/naver/anny, Apache-2.0; geometry from the MakeHuman
community, CC0) drives a parametric body from *phenotype* parameters
(gender, age, height, weight, muscle …). Instead of the procedural mesh used by
the ``Native`` engine, the high-res engine replaces the body with a set of
*prototype blendshapes* (MakeHuman targets) and solves the rest of the pipeline
unchanged.

Those blendshapes are baked offline by this script into
``app/src/main/assets/anny/anny.mhb`` (or loaded at runtime via
``AnnyModel.install``). On device the mesh is reconstructed as::

    mesh = base + Σ_j  ( Π_d  c_{d,k} ) · B_j        #  w = Π c

where ``c_{d,k}`` is the piecewise-linear interpolation coefficient of phenotype
``d`` at its ``k``-th anchor, and ``B_j`` is the j-th blendshape. See
``AnnyTargets.java`` / ``AnnyModel.java`` for the matching reader.

The .mhb VERSION_2 layout (all integers/floats little-endian) is::

    header : MAGIC(u32)=0x594E4E41  "ANNY"
             version(i32)=2
             vc(i32)                vertex count
             tc(i32)                triangle count
             count(i32)             number of blendshapes
             flags(i32)             bit0 = FLAG_Q16 (int16-quantized deltas)
                                    bit1 = FLAG_TRI32 (int32 indices)
             dimCount(i32)          number of adjustable phenotype dims
             reserved(i32) = 0
    base   : vc*3 float32           template vertex positions
    tris   : tc*3  int16 (or int32 if FLAG_TRI32)  triangle vertex indices
    dims   : for each dim:
               u16 namelen
               utf8 name
               i32 n
               n * float32 anchors (interpolation knots, ascending)
    shapes : for each shape:
               i32 depCount
               depCount * (i32 code)      code = (dimIndex << 16) | variationIndex
               float32 scale              quantization scale for this shape
               quant ? (vc*3 int16) : (vc*3 float32)   vertex deltas

This script exports the 4 dims that remain adjustable after baking the adult /
other fixed phenotypes into the shapes: ``gender``, ``muscle``, ``weight``,
``height``. The blendshapes are the exact multilinear (tensor) decomposition of
the 2×3×3×2 lattice into a gender main-effect (2), a gender×muscle×weight slice
(18) and the full gender×muscle×weight×height term (36) — 56 shapes total. The
choice of reference vertex only redistributes the deltas; the reconstructed mesh
is identical for any reference.

Usage
-----
    # Regenerate the shipped asset from a local anny checkout + MakeHuman data:
    python3 tools/export_anny_targets.py export \
        --anny-dir /path/to/anny \
        --out app/src/main/assets/anny/anny.mhb

    # Prove the binary writer is byte-exact against the shipped asset:
    python3 tools/export_anny_targets.py selftest \
        --in app/src/main/assets/anny/anny.mhb

    # Run the full pipeline on a synthetic mesh (no anny needed, for testing):
    python3 tools/export_anny_targets.py export --synthetic --out /tmp/anny.mhb
"""

import argparse
import math
import os
import struct
import sys

# --- .mhb constants (must match AnnyModel.java) -----------------------------
MAGIC = 0x594E4E41          # "ANNY" little-endian
VERSION_2 = 2
FLAG_Q16 = 1                # blendshape deltas stored as int16
FLAG_TRI32 = 2              # indices stored as int32

# --- Phenotype model (the dims the device can still adjust) -----------------
# anchors are the interpolation knots; the reference vertex (REF) is the neutral
# pose the deltas are measured against.
DIMS = ["gender", "muscle", "weight", "height"]
ANCHORS = {
    "gender": [0.0, 1.0],
    "muscle": [0.0, 0.5, 1.0],
    "weight": [0.0, 0.5, 1.0],
    "height": [0.0, 1.0],
}
REF = {"gender": 0, "muscle": 1, "weight": 1, "height": 0}


def structure():
    """Return (dims, anchors_per_dim, deps) for the 56 blendshapes.

    deps[j] is a list of (dimIndex, variationIndex) tuples; the runtime weight is
    the product of the corresponding interpolation coefficients.
    """
    dims = DIMS
    anchors = [ANCHORS[d] for d in dims]
    gmw = [(g, m, w) for g in (0, 1) for m in (0, 1, 2) for w in (0, 1, 2)]
    deps = []
    # gender main effect — depends only on gender
    for g in (0, 1):
        deps.append([(0, g)])
    # gender x muscle x weight slice (height held at the reference)
    for g, m, w in gmw:
        deps.append([(0, g), (1, m), (2, w)])
    # full gender x muscle x weight x height term
    for g, m, w in gmw:
        for h in (0, 1):
            deps.append([(0, g), (1, m), (2, w), (3, h)])
    assert len(deps) == 2 + 18 + 36 == 56, len(deps)
    return dims, anchors, deps


# --- binary writer ----------------------------------------------------------
def write_mhb_v2(path, vc, tc, base, tris, dims, anchors, deps, scales, qdeltas,
                 tri32=False):
    flags = FLAG_Q16 | (FLAG_TRI32 if tri32 else 0)
    with open(path, "wb") as f:
        f.write(struct.pack("<7i", MAGIC, VERSION_2, vc, tc, len(deps), flags,
                            len(dims)))
        f.write(struct.pack("<i", 0))                       # reserved
        f.write(struct.pack("<%df" % (vc * 3), *base))      # template
        fmt_i = "<%di" % (tc * 3) if tri32 else "<%dh" % (tc * 3)
        f.write(struct.pack(fmt_i, *tris))                  # indices
        for d, a in zip(dims, anchors):                     # dims + anchors
            nb = d.encode("utf-8")
            f.write(struct.pack("<H", len(nb)))
            f.write(nb)
            f.write(struct.pack("<i", len(a)))
            f.write(struct.pack("<%df" % len(a), *a))
        for dep, sc, qd in zip(deps, scales, qdeltas):      # shapes
            f.write(struct.pack("<i", len(dep)))
            for (di, ki) in dep:
                f.write(struct.pack("<i", (di << 16) | ki))
            f.write(struct.pack("<f", sc))
            f.write(struct.pack("<%dh" % len(qd), *qd))


# --- quantization -----------------------------------------------------------
def quantize(deltas):
    qdeltas, scales = [], []
    for d in deltas:
        mx = max((abs(x) for x in d), default=0.0) or 1.0
        scales.append(mx)
        qd = [max(-32767, min(32767, int(math.floor(x / mx * 32767 + 0.5))))
              for x in d]
        qdeltas.append(qd)
    return qdeltas, scales


def _sub(a, b):
    return [x - y for x, y in zip(a, b)]


# --- multilinear decomposition into the 56 blendshapes ----------------------
def build_bases(mesh_at):
    """mesh_at(g, m, w, h) -> flat vertex list (vc*3) for the given anchor
    indices. Returns (base, deps, qdeltas, scales)."""
    dims, anchors, deps = structure()
    rg, rm, rw, rh = REF["gender"], REF["muscle"], REF["weight"], REF["height"]

    base = mesh_at(rg, rm, rw, rh)
    vc = len(base) // 3

    # gender main effect: f(g, ref) - base
    g_only = {g: mesh_at(g, rm, rw, rh) for g in (0, 1)}

    # gender x muscle x weight slice (height at reference): f(g,m,w,rh) - f(g,ref)
    slice3 = {}
    for g in (0, 1):
        for m in (0, 1, 2):
            for w in (0, 1, 2):
                slice3[(g, m, w)] = _sub(mesh_at(g, m, w, rh), g_only[g])

    # full term: f(g,m,w,h) - f(g,m,w,rh)
    slice4 = {}
    for g in (0, 1):
        for m in (0, 1, 2):
            for w in (0, 1, 2):
                base3 = mesh_at(g, m, w, rh)
                for h in (0, 1):
                    slice4[(g, m, w, h)] = _sub(mesh_at(g, m, w, h), base3)

    deltas = []
    for dep in deps:
        if len(dep) == 1:
            deltas.append(g_only[dep[0][1]])
        elif len(dep) == 3:
            (_, g), (_, m), (_, w) = dep
            deltas.append(slice3[(g, m, w)])
        else:
            (_, g), (_, m), (_, w), (_, h) = dep
            deltas.append(slice4[(g, m, w, h)])

    qdeltas, scales = quantize(deltas)
    return base, deps, qdeltas, scales


# --- mesh providers ---------------------------------------------------------
def synthetic_mesh_at():
    """Deterministic placeholder mesh so the export path runs without anny.

    A small NxN height-field grid; each phenotype combination applies a tiny,
    index-dependent displacement. Topology (faces) is constant across calls.
    """
    N = 48
    verts = []
    for j in range(N):
        for i in range(N):
            x = (i / (N - 1) - 0.5) * 1.7
            y = (j / (N - 1) - 0.5) * 1.7
            z = -0.9
            verts.extend([x, y, z])
    base_verts = verts

    def mesh_at(g, m, w, h):
        out = list(base_verts)
        amp = (g * 0.6 + m * 0.2 + w * 0.2 + h * 0.4)
        for idx in range(0, len(out), 3):
            k = idx // 3
            out[idx + 2] += amp * math.sin(k * 0.3) * 0.02
        return out

    faces = []
    for j in range(N - 1):
        for i in range(N - 1):
            a = j * N + i
            b = j * N + i + 1
            c = (j + 1) * N + i
            d = (j + 1) * N + i + 1
            faces.append((a, c, b))
            faces.append((b, c, d))
    return mesh_at, faces


def anny_mesh_at(anny_dir):
    """Load the official anny package and evaluate the mesh per phenotype combo.

    Requires a local checkout of https://github.com/naver/anny whose
    ``data/targets`` (MakeHuman targets) is present. Install with
    ``pip install -e .`` inside that checkout, or pass ``--anny-dir`` so we can
    import it directly.
    """
    sys.path.insert(0, anny_dir)
    import anny  # noqa: E402  (import after path tweak)

    def mesh_at(g, m, w, h):
        values = {
            "gender": ANCHORS["gender"][g],
            "muscle": ANCHORS["muscle"][m],
            "weight": ANCHORS["weight"][w],
            "height": ANCHORS["height"][h],
        }
        mesh = anny.human(values)            # -> object with .vertices / .faces
        verts = mesh.vertices
        return [float(v) for v in verts]

    # topology comes from the neutral mesh
    ref = anny.human({d: ANCHORS[d][REF[d]] for d in DIMS})
    faces = [(int(a), int(b), int(c)) for a, b, c in ref.faces]
    return mesh_at, faces


# --- commands ---------------------------------------------------------------
def cmd_export(args):
    if args.synthetic:
        mesh_at, faces = synthetic_mesh_at()
    else:
        if not args.anny_dir:
            sys.exit("error: --anny-dir is required (or use --synthetic)")
        mesh_at, faces = anny_mesh_at(args.anny_dir)

    base, deps, qdeltas, scales = build_bases(mesh_at)
    vc = len(base) // 3
    tris = [i for tri in faces for i in tri]
    tc = len(tris) // 3
    tri32 = any(i >= 32768 for i in tris)
    dims, anchors, _ = structure()

    out = args.out
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    write_mhb_v2(out, vc, tc, base, tris, dims, anchors, deps, scales, qdeltas,
                 tri32=tri32)
    print("wrote %s  (%d verts, %d tris, %d shapes, %s)"
          % (out, vc, tc, len(deps), "tri32" if tri32 else "tri16"))


def cmd_selftest(args):
    """Re-emit the shipped asset through the writer and assert byte equality."""
    with open(args.in_file, "rb") as f:
        data = f.read()
    p = parse_mhb(data)
    write_mhb_v2(args.out or "/tmp/anny_roundtrip.mhb",
                 p["vc"], p["tc"], p["base"], p["tris"], p["dims"],
                 p["anchors"], p["deps"], p["scales"], p["qdeltas"],
                 tri32=bool(p["flags"] & FLAG_TRI32))
    with open(args.out or "/tmp/anny_roundtrip.mhb", "rb") as f:
        out = f.read()
    if out == data:
        print("selftest OK: writer is byte-exact (%d bytes)" % len(data))
        return 0
    # find first difference
    n = min(len(out), len(data))
    diff = next((i for i in range(n) if out[i] != data[i]), n)
    sys.exit("selftest FAILED: bytes differ at offset %d (out=%d data=%d)"
             % (diff, out[diff], data[diff]))


def parse_mhb(data):
    off = 0

    def u32(o): return struct.unpack_from("<I", data, o)[0]
    def i32(o): return struct.unpack_from("<i", data, o)[0]
    def f32(o): return struct.unpack_from("<f", data, o)[0]

    assert u32(0) == MAGIC, "not an Anny model file"
    version = i32(4)
    vc, tc, count, flags, dimCount = (i32(8), i32(12), i32(16), i32(20), i32(24))
    off = 32
    base = list(struct.unpack_from("<%df" % (vc * 3), data, off))
    off += vc * 3 * 4
    if flags & FLAG_TRI32:
        tris = list(struct.unpack_from("<%di" % (tc * 3), data, off))
        off += tc * 3 * 4
    else:
        tris = list(struct.unpack_from("<%dh" % (tc * 3), data, off))
        off += tc * 3 * 2
    dims, anchors = [], []
    for _ in range(dimCount):
        ln = struct.unpack_from("<H", data, off)[0]; off += 2
        dims.append(data[off:off + ln].decode("utf-8")); off += ln
        n = i32(off); off += 4
        anchors.append(list(struct.unpack_from("<%df" % n, data, off))); off += n * 4
    deps, scales, qdeltas = [], [], []
    n3 = vc * 3
    for _ in range(count):
        dc = i32(off); off += 4
        code = []
        for _ in range(dc):
            c = i32(off); off += 4
            code.append((c >> 16, c & 0xFFFF))
        deps.append(code)
        scales.append(f32(off)); off += 4
        q = list(struct.unpack_from("<%dh" % n3, data, off)); off += n3 * 2
        qdeltas.append(q)
    assert off == len(data), (off, len(data))
    return {"vc": vc, "tc": tc, "count": count, "flags": flags,
            "dimCount": dimCount, "base": base, "tris": tris, "dims": dims,
            "anchors": anchors, "deps": deps, "scales": scales,
            "qdeltas": qdeltas}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    ex = sub.add_parser("export", help="bake anny.mhb")
    ex.add_argument("--anny-dir", help="path to a local anny checkout")
    ex.add_argument("--out", required=True, help="output .mhb path")
    ex.add_argument("--synthetic", action="store_true",
                    help="use a placeholder mesh (no anny needed)")
    ex.set_defaults(func=cmd_export)

    st = sub.add_parser("selftest", help="verify the writer is byte-exact")
    st.add_argument("--in-file", required=True, help="existing .mhb to round-trip")
    st.add_argument("--out", help="round-trip output (default /tmp)")
    st.set_defaults(func=cmd_selftest)

    args = ap.parse_args(argv)
    return args.func(args) or 0


if __name__ == "__main__":
    sys.exit(main())
