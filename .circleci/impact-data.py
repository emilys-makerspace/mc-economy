"""Map class atoms to source files and convert per-atom JaCoCo XML to LCOV."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    if sys.argv[1] == "map":
        for atom in sys.argv[2:]:
            source = Path("src/test/java", atom.replace(".", "/") + ".java")
            if not source.is_file():
                raise ValueError(f"Missing test source for {atom}")
            print(f"TA:{atom}\n{source.as_posix()}")
    elif sys.argv[1] == "lcov":
        report = ET.parse(sys.argv[2]).getroot()
        output = Path(sys.argv[3])
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w") as stream:
            for package in report.findall("package"):
                for source in package.findall("sourcefile"):
                    path = Path("src/main/java", package.attrib["name"], source.attrib["name"])
                    if not path.is_file():
                        raise ValueError(f"Missing covered source: {path}")
                    stream.write(f"SF:{path.as_posix()}\n")
                    for line in source.findall("line"):
                        # LCOV needs hit presence; JaCoCo supplies covered instructions.
                        hits = int(int(line.attrib["ci"]) > 0)
                        stream.write(f"DA:{line.attrib['nr']},{hits}\n")
                    stream.write("end_of_record\n")
    else:
        raise ValueError("Expected map or lcov")


if __name__ == "__main__":
    main()
