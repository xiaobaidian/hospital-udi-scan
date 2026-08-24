p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/java/com/hospital/udiscan/ScanFragment.kt"
s = open(p, encoding="utf-8").read()

old_bind = '''        scanner = view.findViewById(R.id.barcode_scanner)
        tvBuffer = view.findViewById(R.id.tv_buffer)
        tvProductTop = view.findViewById(R.id.tv_product_top)
        tvQty = view.findViewById(R.id.tv_qty)
        btnPlus = view.findViewById(R.id.btn_plus)
        btnMinus = view.findViewById(R.id.btn_minus)
        btnQuery = view.findViewById(R.id.btn_query)
        btnAdd = view.findViewById(R.id.btn_add)
        btnDiscard = view.findViewById(R.id.btn_discard)
        btnEditName = view.findViewById(R.id.btn_edit_name)
        scanFlash = view.findViewById(R.id.scan_flash)
        tvLineUdi = view.findViewById(R.id.tv_line_udi)
        tvLineBatch = view.findViewById(R.id.tv_line_batch)
        tvLineExpiry = view.findViewById(R.id.tv_line_expiry)
        tvLineProd = view.findViewById(R.id.tv_line_prod)
        tvLineSerial = view.findViewById(R.id.tv_line_serial)
        tvPreviewQty = view.findViewById(R.id.tv_preview_qty)
        tvPreviewHint = view.findViewById(R.id.tv_preview_hint)'''
new_bind = '''        scanner = view.findViewById(R.id.barcode_scanner)
        tvProductTop = view.findViewById(R.id.tv_product_top)
        etQty = view.findViewById(R.id.et_qty)
        btnQuery = view.findViewById(R.id.btn_query)
        btnAdd = view.findViewById(R.id.btn_add)
        btnDiscard = view.findViewById(R.id.btn_discard)
        btnEditName = view.findViewById(R.id.btn_edit_name)
        scanFlash = view.findViewById(R.id.scan_flash)
        tvLineUdi = view.findViewById(R.id.tv_line_udi)
        tvLineBatch = view.findViewById(R.id.tv_line_batch)
        tvLineExpiry = view.findViewById(R.id.tv_line_expiry)
        tvLineProd = view.findViewById(R.id.tv_line_prod)
        tvLineSerial = view.findViewById(R.id.tv_line_serial)
        tvPreviewHint = view.findViewById(R.id.tv_preview_hint)'''
assert old_bind in s, "BIND NOT FOUND"
s = s.replace(old_bind, new_bind, 1)

# 更新 +/- 按钮监听器（删除，改为 etQty 双向绑定）
old_listeners = '''        btnPlus.setOnClickListener { vm.bufQty++; updateBufferUi() }
        btnMinus.setOnClickListener { if (vm.bufQty > 1) vm.bufQty--; updateBufferUi() }
        btnQuery.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) queryNmpa(udi) else toast(R.string.toast_no_udi)
        }
        btnAdd.setOnClickListener { commitBuffer(); updateBufferUi() }
        btnDiscard.setOnClickListener {
            vm.clearBuffer()
            updateBufferUi()
            toast(R.string.toast_discarded)
        }'''
new_listeners = '''        // 数量可直接填写，实时写回 vm.bufQty
        etQty.setText(vm.bufQty.toString())
        etQty.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: android.text.Editable?) {
                val v = editable?.toString()?.toIntOrNull()
                if (v != null && v >= 1) vm.bufQty = v
            }
        })
        btnQuery.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) queryNmpa(udi) else toast(R.string.toast_no_udi)
        }
        btnAdd.setOnClickListener { commitBuffer(); updateBufferUi() }
        btnDiscard.setOnClickListener {
            vm.clearBuffer()
            updateBufferUi()
            toast(R.string.toast_discarded)
        }'''
assert old_listeners in s, "LISTENERS NOT FOUND"
s = s.replace(old_listeners, new_listeners, 1)

open(p, "w", encoding="utf-8").write(s)
print("bindings + listeners updated")
