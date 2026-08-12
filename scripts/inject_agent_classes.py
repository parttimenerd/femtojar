"""
Re-inject Launcher-Agent-Class / Premain-Class / Agent-Class entries into a
femtojar-shrunk output JAR as real (unbundled) zip entries.

Femtojar bundles all .class files into a compressed blob that is only
accessible after main() initialises the custom class loader. The JVM
instrumentation system loads Launcher-Agent-Class *before* main(), so any
agent class bundled this way hits ClassNotFoundException.

Usage:
    python3 scripts/inject_agent_classes.py <source_jar> <output_jar>

The <source_jar> is the original (pre-femtojar) JAR that still has the agent
classes as real zip entries. The <output_jar> is the femtojar-processed JAR
that needs the classes re-injected.
"""
import sys
import os
import zipfile

def main() -> None:
    if len(sys.argv) != 3:
        print("Usage: inject_agent_classes.py <source_jar> <output_jar>", file=sys.stderr)
        sys.exit(1)

    src, dst = sys.argv[1], sys.argv[2]

    with zipfile.ZipFile(dst, 'r') as zf:
        manifest = zf.read('META-INF/MANIFEST.MF').decode()

    agent_classes = []
    for line in manifest.splitlines():
        for attr in ('Launcher-Agent-Class:', 'Premain-Class:', 'Agent-Class:'):
            if line.startswith(attr):
                cls = line.split(':', 1)[1].strip().replace('.', '/') + '.class'
                agent_classes.append(cls)

    if not agent_classes:
        sys.exit(0)

    with zipfile.ZipFile(src, 'r') as zf_src:
        missing = [c for c in agent_classes if c not in zf_src.namelist()]
        if missing:
            print(f"::warning::Agent classes not found in source JAR: {missing}", file=sys.stderr)
            sys.exit(0)
        class_data = {c: zf_src.read(c) for c in agent_classes}

    tmp = dst + '.tmp'
    with (zipfile.ZipFile(dst, 'r') as zf_in,
          zipfile.ZipFile(tmp, 'w', compression=zipfile.ZIP_DEFLATED) as zf_out):
        existing = set(zf_in.namelist())
        for item in zf_in.infolist():
            zf_out.writestr(item, zf_in.read(item.filename))
        for cls_path, data in class_data.items():
            if cls_path not in existing:
                zf_out.writestr(cls_path, data)

    os.replace(tmp, dst)
    print(f"  injected agent classes: {list(class_data)}")


if __name__ == '__main__':
    main()
