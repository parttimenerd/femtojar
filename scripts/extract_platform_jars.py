"""Extract specific platform jars from platform-jars.zip."""
from zipfile import ZipFile

targets = {
    "condensed-data-linux-amd64.jar",
    "condensed-data-linux-amd64-inflaterless.jar",
}
with ZipFile("platform-jars.zip", "r") as zf:
    names = zf.namelist()
    for target in targets:
        matched = [n for n in names if n.endswith("/" + target) or n == target]
        if not matched:
            raise SystemExit(f"Missing {target} in platform-jars.zip")
        with zf.open(matched[0], "r") as src, open(target, "wb") as out:
            out.write(src.read())
        print(f"Extracted {target}")
