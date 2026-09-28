package com.cloud.dex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import java.util.List;

public class LineChartView extends View {
    private Paint axisPaint, linePaint, pointPaint, fillPaint, textPaint;
    private List<DailyStats> dataList;
    private int maxValue = 0;
    private int topPadding = dp2px(40);
    private int bottomPadding = dp2px(60);
    private int leftPadding = dp2px(50);
    private int rightPadding = dp2px(30);

    // 折线颜色
    private int lineColor = Color.parseColor("#2196F3");
    private int fillColor = Color.argb(30, 33, 150, 243); // 半透明蓝

    public LineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        axisPaint = new Paint();
        axisPaint.setColor(Color.parseColor("#DDDDDD"));
        axisPaint.setStrokeWidth(dp2px(1));
        axisPaint.setStyle(Paint.Style.STROKE);

        linePaint = new Paint();
        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(dp2px(2.5f));
        linePaint.setAntiAlias(true);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        pointPaint = new Paint();
        pointPaint.setColor(lineColor);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        fillPaint = new Paint();
        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(sp2px(12));
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<DailyStats> dataList) {
        this.dataList = dataList;
        maxValue = 0;
        for (DailyStats stats : dataList) {
            if (stats.userCount > maxValue) maxValue = stats.userCount;
        }
        // Y 轴最大值留白：取整加1，至少为5
        maxValue = Math.max(maxValue + 1, 5);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dataList == null || dataList.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        int chartBottom = height - bottomPadding;
        int chartTop = topPadding;
        int chartLeft = leftPadding;
        int chartRight = width - rightPadding;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        // 绘制 Y 轴网格线和标签（从0到maxValue）
        int ySteps = Math.min(maxValue, 6); // 最多6个刻度
        if (ySteps < 1) ySteps = 1;
        float stepValue = maxValue / (float) ySteps;

        Paint gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#EFEFEF"));
        gridPaint.setStrokeWidth(dp2px(1));

        Paint yLabelPaint = new Paint(textPaint);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        for (int i = 0; i <= ySteps; i++) {
            float y = chartBottom - (i * stepValue / maxValue) * chartHeight;
            // 网格线
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
            // Y轴标签
            int value = Math.round(i * stepValue);
            canvas.drawText(String.valueOf(value), chartLeft - dp2px(8), y + dp2px(4), yLabelPaint);
        }

        // 绘制 X 轴
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint);

        // 绘制 X 轴日期标签（固定全部显示）
        if (dataList.size() == 1) {
            // 只有一个点，居中显示
            String date = dataList.get(0).date.substring(5);
            float x = chartLeft + chartWidth / 2f;
            canvas.drawText(date, x, chartBottom + dp2px(20), textPaint);
        } else {
            float xStep = chartWidth / (float) (dataList.size() - 1);
            for (int i = 0; i < dataList.size(); i++) {
                String date = dataList.get(i).date.substring(5); // MM-DD
                float x = chartLeft + i * xStep;
                canvas.drawText(date, x, chartBottom + dp2px(20), textPaint);
            }
        }

        // 计算数据点并构建折线路径
        Path linePath = new Path();
        Path fillPath = new Path();
        boolean first = true;

        for (int i = 0; i < dataList.size(); i++) {
            float x = dataList.size() == 1 ?
                    chartLeft + chartWidth / 2f :
                    chartLeft + i * (chartWidth / (float) (dataList.size() - 1));
            float y = chartBottom - (dataList.get(i).userCount / (float) maxValue) * chartHeight;

            if (first) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, chartBottom); // 填充起点在底部
                fillPath.lineTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }

            // 画数据点圆圈
            canvas.drawCircle(x, y, dp2px(4), pointPaint);
            canvas.drawCircle(x, y, dp2px(2.5f), new Paint() {{ setColor(Color.WHITE); setStyle(Style.FILL); }});
        }

        // 封闭填充路径
        if (!dataList.isEmpty()) {
            float lastX = dataList.size() == 1 ?
                    chartLeft + chartWidth / 2f :
                    chartLeft + (dataList.size() - 1) * (chartWidth / (float) (dataList.size() - 1));
            fillPath.lineTo(lastX, chartBottom);
            fillPath.close();
        }

        // 绘制填充和折线（先填充后折线，避免遮盖）
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }

    private int dp2px(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private int sp2px(float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    public static class DailyStats {
        public String date;
        public int userCount;
        // 已移除 launchCount
    }
}