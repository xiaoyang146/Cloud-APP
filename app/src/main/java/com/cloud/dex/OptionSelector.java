package com.cloud.dex;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘下拉选择框：外观是一个带背景和向下箭头的 TextView，
 * 点击后从底部弹出 BottomSheet 列表（样式对齐 WatermarkRemover 的清晰度选择层）。
 * API 尽量对齐 android.widget.Spinner，便于替换。
 */
public class OptionSelector extends AppCompatTextView {

    public interface OnItemSelectedListener {
        void onItemSelected(int position);
    }

    private final List<String> labels = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private int selectedIndex = -1;
    private OnItemSelectedListener listener;
    private String sheetTitle = "请选择";
    private int normalTextColor;
    private int placeholderColor;
    private boolean selectionMarkEnabled = true;

    public OptionSelector(Context context) { super(context); init(); }
    public OptionSelector(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public OptionSelector(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setGravity(Gravity.CENTER_VERTICAL);
        normalTextColor = getCurrentTextColor();
        placeholderColor = ContextCompat.getColor(getContext(), R.color.text_color_secondary);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isEnabled()) showSheet();
            }
        });
    }

    /** 弹层标题，例如“选择应用” */
    public void setSheetTitle(String title) { this.sheetTitle = (title == null ? "" : title); }

    /** 控件文本是否带“👉 …(当前选择)”标记（默认 true） */
    public void setSelectionMarkEnabled(boolean enabled) {
        this.selectionMarkEnabled = enabled;
        refreshText();
    }

    /** 设置占位提示（灰色），例如“请选择应用”。此时无有效选项。 */
    public void setPlaceholderText(String text) {
        labels.clear();
        values.clear();
        selectedIndex = -1;
        setText(text == null ? "" : text);
        setTextColor(placeholderColor);
    }

    /** 设置选项（仅显示标签，无关联值对象） */
    public void setOptionList(List<String> labelList) {
        setOptionList(labelList, (List<Object>) null);
    }

    /** 设置选项。labels 为显示文本，values 为对应对象（可空）。类似 Spinner.setAdapter，不触发监听。 */
    public <T> void setOptionList(List<String> labelList, @Nullable List<T> valueList) {
        labels.clear();
        values.clear();
        if (labelList != null) labels.addAll(labelList);
        if (valueList != null) {
            for (T v : valueList) values.add(v);
        }
        setTextColor(normalTextColor);
        if (!labels.isEmpty()) {
            selectedIndex = 0;
            refreshText();
        } else {
            selectedIndex = -1;
            setText("");
        }
    }

    /** 同 Spinner.setSelection，会触发 OnItemSelectedListener */
    public void setSelection(int index) {
        if (index >= 0 && index < labels.size()) {
            selectedIndex = index;
            refreshText();
            if (listener != null) listener.onItemSelected(index);
        }
    }

    public int getSelectedItemPosition() { return selectedIndex; }

    /** 返回当前选中项对应的值对象，无值或未选中时返回 null */
    public Object getSelectedItem() {
        if (values.isEmpty()) return null;
        if (selectedIndex >= 0 && selectedIndex < values.size()) return values.get(selectedIndex);
        return null;
    }

    public String getSelectedLabel() {
        if (labels.isEmpty() || selectedIndex < 0 || selectedIndex >= labels.size()) return null;
        return labels.get(selectedIndex);
    }

    public boolean hasOptions() { return !labels.isEmpty(); }

    public void setOnItemSelectedListener(OnItemSelectedListener l) { this.listener = l; }

    private void refreshText() {
        if (selectedIndex >= 0 && selectedIndex < labels.size()) {
            String label = labels.get(selectedIndex);
            setText(selectionMarkEnabled ? ("👉 " + label + " (当前选择)") : label);
        }
    }

    private void showSheet() {
        if (labels.isEmpty()) return;
        Context ctx = getContext();
        float density = getResources().getDisplayMetrics().density;
        int primaryColor = ContextCompat.getColor(ctx, R.color.colorPrimary);
        int normalColor = ContextCompat.getColor(ctx, R.color.text_color_primary);
        int dividerColor = ContextCompat.getColor(ctx, R.color.divider_color);
        int sheetBg = ContextCompat.getColor(ctx, R.color.card_bg);

        BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(sheetBg);

        // 标题
        TextView titleView = new TextView(ctx);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(normalColor);
        titleView.setText(sheetTitle);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * density)));
        root.addView(titleView);

        // 分割线
        root.addView(buildDivider(ctx, dividerColor));

        // 可滚动列表
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (320 * density)));
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int itemH = (int) (52 * density);
        for (int i = 0; i < labels.size(); i++) {
            final int pos = i;
            boolean selected = (i == selectedIndex);

            TextView item = new TextView(ctx);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            item.setGravity(Gravity.CENTER);
            item.setTextSize(15);
            item.setText(selected ? ("👉 " + labels.get(i) + " (当前选择)") : labels.get(i));
            item.setTextColor(selected ? primaryColor : normalColor);
            item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            item.setClickable(true);
            item.setFocusable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            item.setBackgroundResource(ripple.resourceId);
            item.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    selectedIndex = pos;
                    refreshText();
                    if (listener != null) listener.onItemSelected(pos);
                }
            });
            list.addView(item);

            if (i < labels.size() - 1) {
                list.addView(buildDivider(ctx, dividerColor));
            }
        }
        scroll.addView(list);
        root.addView(scroll);

        // 分割线 + 取消
        root.addView(buildDivider(ctx, dividerColor));
        TextView cancel = new TextView(ctx);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextSize(15);
        cancel.setTextColor(ContextCompat.getColor(ctx, R.color.text_color_secondary));
        cancel.setText("取消");
        cancel.setClickable(true);
        cancel.setFocusable(true);
        android.util.TypedValue ripple2 = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple2, true);
        cancel.setBackgroundResource(ripple2.resourceId);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * density)));
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(cancel);

        dialog.setContentView(root);
        dialog.show();
    }

    private View buildDivider(Context ctx, int color) {
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(color);
        return divider;
    }
}
