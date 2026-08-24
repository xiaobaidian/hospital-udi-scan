p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/java/com/hospital/udiscan/ScanFragment.kt"
s = open(p, encoding="utf-8").read()

# 1) 删掉 updateBufferUi 里的 tvQty 行
old_qty = "        tvQty.text = vm.bufQty.toString()\n        updatePreviewCard()"
new_qty = "        updatePreviewCard()"
assert old_qty in s, "TVQTY LINE NOT FOUND"
s = s.replace(old_qty, new_qty, 1)

# 2) 删除 updatePreviewCard 中的 tvPreviewQty 行
old_preview = '''        tvPreviewQty.text = "$src · " + getString(R.string.preview_qty, vm.bufQty)

        if (vm.bufPendingUnknown.isNotEmpty()) {'''
new_preview = '''        if (vm.bufPendingUnknown.isNotEmpty()) {'''
assert old_preview in s, "PREVIEW QTY NOT FOUND"
s = s.replace(old_preview, new_preview, 1)

# 3) 在 updatePreviewCard 开头同步 etQty（避免扫码重置后不刷新）
old_head = '''    /** 更新「本次将录入」预览卡（紧凑分行展示）。 */
    private fun updatePreviewCard() {
        // 来源标记：自动分辨条码/二维码
        val src = if (vm.bufSource == "qr") "📷 二维码" else "▌ 条码"'''
new_head = '''    /** 更新「本次将录入」预览卡（紧凑分行展示）。 */
    private fun updatePreviewCard() {
        // 来源标记：自动分辨条码/二维码
        val src = if (vm.bufSource == "qr") "📷 二维码" else "▌ 条码"
        // 数量回填（仅在用户未在编辑时同步，避免打断输入）
        val cur = etQty.text.toString().toIntOrNull()
        if (cur == null || cur < 1 || cur != vm.bufQty) {
            etQty.setText(vm.bufQty.toString())
        }'''
assert old_head in s, "HEAD NOT FOUND"
s = s.replace(old_head, new_head, 1)

open(p, "w", encoding="utf-8").write(s)
print("updateBufferUi + updatePreviewCard updated")
