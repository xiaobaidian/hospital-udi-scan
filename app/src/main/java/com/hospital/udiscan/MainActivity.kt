package com.hospital.udiscan

import android.os.Bundle
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

/**
 * 容器 Activity：左右滑动在「扫码页」与「清单页」之间切换。
 * - ViewPager2 承载 ScanFragment(0) / ListFragment(1)；
 * - 底部两个标签(Tab)与页面联动，点按也可切换；
 * - 共享状态放在 ScanViewModel（activity 作用域），两页数据互通、滑回不丢。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var tabScan: TextView
    private lateinit var tabList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NmpaCache.init(this)

        pager = findViewById(R.id.pager)
        tabScan = findViewById(R.id.tab_scan)
        tabList = findViewById(R.id.tab_list)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) ScanFragment() else ListFragment()
        }
        // 禁止用户通过滑动越过边界后还有回弹；默认即可左右滑
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTab(position)
            }
        })

        tabScan.setOnClickListener { pager.currentItem = 0 }
        tabList.setOnClickListener { pager.currentItem = 1 }

        updateTab(0)
        refreshTopStat()
    }

    /** 顶部统计条：展示 NMPA 字典与自定义字典条目数（状态栏下方，不显示 APP 名称）。 */
    private fun refreshTopStat() {
        val tv = findViewById<TextView>(R.id.top_stat)
        tv.text = "NMPA 字典 ${NmpaCache.count()} 条 · 自定义字典 ${NmpaCache.overrideCount()} 条"
    }

    override fun onResume() {
        super.onResume()
        refreshTopStat()
    }

    private fun updateTab(pos: Int) {
        val on = ContextCompat.getColor(this, android.R.color.white)
        // off 用半透明白（153/255 ≈ 60%）
        val offColor = android.graphics.Color.argb(153, 255, 255, 255)
        tabScan.setTextColor(if (pos == 0) on else offColor)
        tabList.setTextColor(if (pos == 1) on else offColor)
        tabScan.textSize = if (pos == 0) 16f else 15f
        tabList.textSize = if (pos == 1) 16f else 15f
    }
}
