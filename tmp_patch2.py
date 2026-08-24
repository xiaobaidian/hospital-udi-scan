p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/java/com/hospital/udiscan/ScanFragment.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("    (no space) private lateinit var tvLineUdi: TextView", "    private lateinit var tvLineUdi: TextView")
open(p, "w", encoding="utf-8").write(s)
print("fixed declaration line")
