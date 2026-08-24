p = "D:/WorkBuddyData/Workspace/hospital-udi-scan/app/src/main/res/layout/fragment_scan.xml"
s = open(p, encoding="utf-8").read()
old = '''            <!-- 标题行：左标题 + 右侧「改名」按钮 -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/preview_title"
                    android:textStyle="bold"
                    android:textSize="13sp"
                    android:textColor="@color/primary_dark" />

                <Button
                    android:id="@+id/btn_edit_name"
                    style="@style/Widget.Material3.Button.TonalButton"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:minWidth="0dp"
                    android:minHeight="32dp"
                    android:paddingHorizontal="12dp"
                    android:paddingVertical="0dp"
                    android:gravity="center"
                    android:includeFontPadding="false"
                    android:text="改名/改型号" />
            </LinearLayout>

            <!-- UDI 一行 -->
            <TextView
                android:id="@+id/tv_line_udi"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 批号 一行 -->
            <TextView
                android:id="@+id/tv_line_batch"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 效期 + 生产 并列一行 -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginTop="2dp">

                <TextView
                    android:id="@+id/tv_line_expiry"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:textSize="14sp"
                    android:textColor="@color/text_main" />

                <TextView
                    android:id="@+id/tv_line_prod"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:gravity="end"
                    android:textSize="14sp"
                    android:textColor="@color/text_main" />
            </LinearLayout>

            <!-- 序列号 一行（空则隐藏） -->
            <TextView
                android:id="@+id/tv_line_serial"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main"
                android:visibility="gone" />

            <!-- 数量 -->
            <TextView
                android:id="@+id/tv_preview_qty"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/text_main"
                android:textSize="14sp"
                android:textStyle="bold" />

            <!-- 未识别/待确认提示 -->
            <TextView
                android:id="@+id/tv_preview_hint"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/text_sub"
                android:textSize="13sp" />
        </LinearLayout>'''
new = '''            <!-- 标题行：左标题（改名按钮已移至下方操作区） -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/preview_title"
                    android:textStyle="bold"
                    android:textSize="13sp"
                    android:textColor="@color/primary_dark" />
            </LinearLayout>

            <!-- UDI 一行 -->
            <TextView
                android:id="@+id/tv_line_udi"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 批号 一行 -->
            <TextView
                android:id="@+id/tv_line_batch"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 效期 一行 -->
            <TextView
                android:id="@+id/tv_line_expiry"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 生产 一行 -->
            <TextView
                android:id="@+id/tv_line_prod"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main" />

            <!-- 序列号 一行（空则隐藏） -->
            <TextView
                android:id="@+id/tv_line_serial"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textSize="14sp"
                android:textColor="@color/text_main"
                android:visibility="gone" />

            <!-- 数量（可直接填写） -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginTop="6dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="数量："
                    android:textSize="14sp"
                    android:textStyle="bold"
                    android:textColor="@color/text_main" />

                <EditText
                    android:id="@+id/et_qty"
                    android:layout_width="72dp"
                    android:layout_height="wrap_content"
                    android:inputType="number"
                    android:gravity="center"
                    android:background="@android:color/transparent"
                    android:text="1"
                    android:textSize="16sp"
                    android:textStyle="bold"
                    android:textColor="@color/text_main" />
            </LinearLayout>

            <!-- 未识别/待确认提示 -->
            <TextView
                android:id="@+id/tv_preview_hint"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="@color/text_sub"
                android:textSize="13sp" />
        </LinearLayout>'''
assert old in s, "OLD NOT FOUND"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
print("preview card updated")
