package com.cloud.dex;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class NoticeAdapter extends RecyclerView.Adapter<NoticeAdapter.NoticeViewHolder> {

    private List<NoticeItem> noticeList;
    private OnNoticeActionListener actionListener;

    public interface OnNoticeActionListener {
        void onEditClick(NoticeItem notice);
        void onDeleteClick(NoticeItem notice);
    }

    public NoticeAdapter(List<NoticeItem> noticeList, OnNoticeActionListener actionListener) {
        this.noticeList = noticeList;
        this.actionListener = actionListener;
    }

    @NonNull
    @Override
    public NoticeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notice_simple, parent, false);
        return new NoticeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NoticeViewHolder holder, int position) {
        NoticeItem notice = noticeList.get(position);
        holder.bind(notice, actionListener);
    }

    @Override
    public int getItemCount() {
        return noticeList.size();
    }

    static class NoticeViewHolder extends RecyclerView.ViewHolder {
        private TextView tvNoticeTitle;
        private TextView tvAppName;
        private TextView tvNoticeContent;
        private TextView tvCreateTime;
        private MaterialButton btnEdit;    // 修改为 MaterialButton
        private MaterialButton btnDelete;  // 修改为 MaterialButton

        public NoticeViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNoticeTitle = itemView.findViewById(R.id.tvNoticeTitle);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvNoticeContent = itemView.findViewById(R.id.tvNoticeContent);
            tvCreateTime = itemView.findViewById(R.id.tvCreateTime);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        public void bind(final NoticeItem notice, final OnNoticeActionListener listener) {
            // 应用名称
            tvAppName.setText("应用名称：" + notice.getAppName());
            // 公告标题
            tvNoticeTitle.setText("公告标题：" + notice.getTitle());

            // 设置公告内容，添加前缀并限制长度
            String content = notice.getContent();
            if (content.length() > 80) {
                content = content.substring(0, 80) + "...";
            }
            tvNoticeContent.setText("公告内容：" + content);

            // 设置创建时间，添加前缀
            tvCreateTime.setText("创建时间：" + notice.getCreateTime());

            // 编辑按钮点击事件
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onEditClick(notice);
                    }
                }
            });

            // 删除按钮点击事件
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onDeleteClick(notice);
                    }
                }
            });

            // 整个item点击事件
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 可以添加查看详情功能
                }
            });
        }
    }
}