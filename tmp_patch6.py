p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/res/values/strings.xml"
s = open(p, encoding="utf-8").readlines()
drop = {
    "hint_scan", "preview_qty", "buffer_title", "chip_udi", "chip_batch",
    "chip_expiry", "chip_prod", "chip_serial", "chip_unknown",
    "dialog_assign_title", "assign_udi", "assign_batch", "assign_expiry",
    "assign_prod", "assign_serial",
}
out = []
for line in s:
    name = None
    import re
    m = re.search(r'<string name="([^"]+)"', line)
    if m:
        name = m.group(1)
    if name in drop:
        continue
    out.append(line)
open(p, "w", encoding="utf-8").write("".join(out))
print("cleaned dead strings", len(out))
