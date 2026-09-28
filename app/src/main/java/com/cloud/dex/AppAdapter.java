package com.cloud.dex;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {

    private List<AppItem> appList;
    private OnAppActionListener actionListener;

    public interface OnAppActionListener {
        void onDeleteClick(AppItem app);
    }

    public AppAdapter(List<AppItem> appList, OnAppActionListener actionListener) {
        this.appList = appList;
        this.actionListener = actionListener;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_list, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppItem app = appList.get(position);
        holder.bind(app, actionListener);
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    public void updateData(List<AppItem> newAppList) {
        this.appList = newAppList;
        notifyDataSetChanged();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        private TextView tvAppName;
        private TextView tvAppId;
        private TextView tvAppDesc;
        private TextView tvCreateTime;
        private ImageButton btnDelete;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvAppId = itemView.findViewById(R.id.tvAppId);
            tvAppDesc = itemView.findViewById(R.id.tvAppDesc);
            tvCreateTime = itemView.findViewById(R.id.tvCreateTime);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        public void bind(final AppItem app, final OnAppActionListener listener) {
            tvAppName.setText(app.getAppName());
            tvAppId.setText("APPID: " + app.getAppId());
            tvAppDesc.setText(app.getAppDesc());
            tvCreateTime.setText(app.getCreateTime());

            // 删除按钮点击事件
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onDeleteClick(app);
                    }
                }
            });

            // 整个item点击事件
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 可以添加查看详情功能
                    Toast.makeText(itemView.getContext(), "点击了应用: " + app.getAppName(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
