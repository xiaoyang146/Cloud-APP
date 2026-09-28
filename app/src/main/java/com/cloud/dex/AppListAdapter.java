package com.cloud.dex;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

    private Context context;
    private List<AppItem> appList;
    private OnAppItemClickListener listener;
    private int lastPosition = -1;

    public interface OnAppItemClickListener {
        void onAppClick(AppItem appItem);
        void onAppLongClick(AppItem appItem);
        void onEditClick(AppItem appItem);
        void onDeleteClick(AppItem appItem);
    }

    public AppListAdapter(Context context, List<AppItem> appList, OnAppItemClickListener listener) {
        this.context = context;
        this.appList = appList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_list, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppItem appItem = appList.get(position);

        // 添加前缀到各个字段
        holder.tvAppName.setText("应用名称：" + appItem.getAppName());
        holder.tvAppId.setText("APPID：" + appItem.getAppId());

        // 处理应用描述，如果为空则显示"暂无描述"
        String appDesc = appItem.getAppDesc();
        if (appDesc == null || appDesc.isEmpty()) {
            holder.tvAppDesc.setText("应用描述：暂无描述");
        } else {
            holder.tvAppDesc.setText("应用描述：" + appDesc);
        }

        // 处理创建时间，如果为空则显示"未知时间"
        String createTime = appItem.getCreateTime();
        if (createTime == null || createTime.isEmpty()) {
            holder.tvCreateTime.setText("创建时间：未知时间");
        } else {
            holder.tvCreateTime.setText("创建时间：" + createTime);
        }

        // 添加项进入动画
        setAnimation(holder.itemView, position);

        // 设置点击监听器
        holder.cardView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onAppClick(appItem);
                }
            }
        });

        holder.cardView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onAppLongClick(appItem);
                }
                return true;
            }
        });

        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDeleteClick(appItem);
                }
            }
        });

        holder.btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onEditClick(appItem);
                }
            }
        });
    }

    /**
     * 为列表项添加动画效果
     */
    private void setAnimation(View viewToAnimate, int position) {
        // 如果位置大于最后显示的位置，则添加动画
        if (position > lastPosition) {
            // 淡入和轻微缩放动画
            viewToAnimate.setAlpha(0f);
            viewToAnimate.setScaleX(0.9f);
            viewToAnimate.setScaleY(0.9f);

            viewToAnimate.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            lastPosition = position;
        }
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    /**
     * 更新数据
     */
    public void updateData(List<AppItem> newAppList) {
        this.appList.clear();
        this.appList.addAll(newAppList);
        notifyDataSetChanged();
        lastPosition = -1; // 重置动画位置
    }

    /**
     * 过滤数据
     */
    public void filter(String query) {
        List<AppItem> filteredList = new ArrayList<>();
        if (query.isEmpty()) {
            filteredList.addAll(appList);
        } else {
            for (AppItem item : appList) {
                if (item.getAppName().toLowerCase().contains(query.toLowerCase())) {
                    filteredList.add(item);
                }
            }
        }
        updateData(filteredList);
    }

    /**
     * 获取当前位置的应用项
     */
    public AppItem getItemAtPosition(int position) {
        if (position >= 0 && position < appList.size()) {
            return appList.get(position);
        }
        return null;
    }

    /**
     * 查找应用项在列表中的位置
     */
    public int findAppItemPosition(AppItem appItem) {
        for (int i = 0; i < appList.size(); i++) {
            if (appList.get(i).getAppId() == appItem.getAppId()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 清空数据
     */
    public void clearData() {
        this.appList.clear();
        notifyDataSetChanged();
        lastPosition = -1;
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        CardView cardView;
        TextView tvAppName;
        TextView tvAppId;
        TextView tvAppDesc;
        TextView tvCreateTime;
        MaterialButton btnDelete;
        MaterialButton btnEdit;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);

            // 初始化所有视图
            cardView = itemView.findViewById(R.id.cardView);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvAppId = itemView.findViewById(R.id.tvAppId);
            tvAppDesc = itemView.findViewById(R.id.tvAppDesc);
            tvCreateTime = itemView.findViewById(R.id.tvCreateTime);
            btnDelete = itemView.findViewById(R.id.btnDelete);
            btnEdit = itemView.findViewById(R.id.btnEdit);
        }
    }
}