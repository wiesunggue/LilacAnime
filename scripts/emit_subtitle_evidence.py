from pathlib import Path
import base64
for path in sorted(Path("subtitle-screen-evidence").rglob("*.png")):
    data=path.read_bytes()
    if len(data)>250000:raise SystemExit("Evidence image unexpectedly large")
    print("SUBTITLE_EVIDENCE_IMAGE "+path.name+" "+base64.b64encode(data).decode())
