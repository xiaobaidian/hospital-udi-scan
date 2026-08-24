p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/java/com/hospital/udiscan/ScanFragment.kt"
s = open(p, encoding="utf-8").read()

# 删除 updateBufferUi 上半段（tvBuffer 原始 dump）
old_head = '''    private fun updateBufferUi() {
        val b = StringBuilder()
        // 来源标记：自动分辨条码/二维码
        val src = if (vm.bufSource == "qr") "📷 二维码（两行合并）" else "▌ 一维条码"
        b.append("来源：$src\\n")
        appendField(b, "UDI(01)", vm.bufUdi, true)
        appendField(b, "批号(10)", vm.bufBatch)
        appendField(b, "效期(17)", Gs1Parser.formatDateYYMMDD(vm.bufExpiry) ?: vm.bufExpiry)
        appendField(b, "生产(11)", Gs1Parser.formatDateYYMMDD(vm.bufProduction) ?: vm.bufProduction)
        val serialAi = vm.bufSerialAi ?: "21"
        appendField(b, "序列($serialAi)", vm.bufSerial)
        if (vm.bufPendingUnknown.isNotEmpty()) {
            b.append("\\n⚠ 待确认：").append(vm.bufPendingUnknown.joinToString(" / "))
        }
        tvBuffer.text = b.toString()
        // 顶部醒目横条：查询到的名称 + 型号（型号另起一行，更直观）'''
new_head = '''    private fun updateBufferUi() {
        // 顶部醒目横条：查询到的名称 + 型号（型号另起一行，更直观）'''
assert old_head in s, "UPDATEBUFFER HEAD NOT FOUND"
s = s.replace(old_head, new_head, 1)

# 删除 appendField 函数（不再使用）
old_append = '''    /** 对待确认段手动指定字段类型（点 chip 触发）。 */
    private fun assignUnknown(value: String) {
        val options = arrayOf(
            getString(R.string.assign_udi),
            getString(R.string.assign_batch),
            getString(R.string.assign_expiry),
            getString(R.string.assign_prod),
            getString(R.string.assign_serial)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_assign_title))
            .setMessage("原始内容：$value")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (vm.bufUdi == null) vm.bufUdi = value
                    1 -> if (vm.bufBatch == null) vm.bufBatch = value
                    2 -> if (vm.bufExpiry == null) vm.bufExpiry = value
                    3 -> if (vm.bufProduction == null) vm.bufProduction = value
                    4 -> if (vm.bufSerial == null) { vm.bufSerial = value; vm.bufSerialAi = "21" }
                }
                vm.bufPendingUnknown = vm.bufPendingUnknown.filter { it != value }
                updateBufferUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appendField(b: StringBuilder, label: String, value: String?, strong: Boolean = false) {
        val v = value ?: "—"
        if (strong) b.append("► $label: $v\\n") else b.append("  $label: $v\\n")
    }

    /** 扫码成功/失败时的视觉反馈：顶部闪光条 + 文字提示。 */'''
new_append = '''    /** 扫码成功/失败时的视觉反馈：顶部闪光条 + 文字提示。 */'''
assert old_append in s, "ASSIGN/APPEND NOT FOUND"
s = s.replace(old_append, new_append, 1)

open(p, "w", encoding="utf-8").write(s)
print("updateBufferUi + removed dead code")
