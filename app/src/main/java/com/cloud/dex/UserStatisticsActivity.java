package com.cloud.dex;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UserStatisticsActivity extends AppCompatActivity {

    private static final String TAG = "UserStatisticsActivity";
    private LineChart lineChart;
    private TextView tvTodayUsers;
    private RequestQueue requestQueue;
    private SharedPreferencesManager spManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_statistics);

        setupActionBar();

        lineChart = findViewById(R.id.lineChart);
        tvTodayUsers = findViewById(R.id.tvTodayUsers);

        spManager = new SharedPreferencesManager(this);
        requestQueue = Volley.newRequestQueue(this);

        fetchDailyStats();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("用户统计");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "ActionBar error", e);
        }
    }

    private void fetchDailyStats() {
        int userId = spManager.getUserId();
        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_DAILY_STATS_URL + "?user_id=" + userId, UserStatisticsActivity.this);

        StringRequest request = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.getInt("code") == 200) {
                            JSONArray arr = json.getJSONArray("data");
                            List<String> dates = new ArrayList<>();
                            List<Entry> userEntries = new ArrayList<>();
                            int todayUsers = 0;
                            String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                                    .format(new Date());

                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject obj = arr.getJSONObject(i);
                                String date = obj.getString("date");
                                int userCount = obj.getInt("userCount");

                                dates.add(date.substring(5)); // MM-dd
                                userEntries.add(new Entry(i, userCount));

                                if (date.equals(todayDate)) {
                                    todayUsers = userCount;
                                }
                            }
                            tvTodayUsers.setText(String.valueOf(todayUsers));
                            setupChart(dates, userEntries);
                        } else {
                            Toast.makeText(this, "数据加载失败", Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON error", e);
                        Toast.makeText(this, "数据解析错误", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    Log.e(TAG, "Network error", error);
                    Toast.makeText(this, "网络连接失败", Toast.LENGTH_SHORT).show();
                });
        requestQueue.add(request);
    }

    private void setupChart(List<String> xLabels, List<Entry> userEntries) {
        LineDataSet dataSet = new LineDataSet(userEntries, "使用人数");

        // ========== 美化样式（全部按你的要求） ==========
        dataSet.setColor(Color.parseColor("#2196F3"));        // 蓝色折线
        dataSet.setLineWidth(2f);
        dataSet.setCircleColor(Color.parseColor("#2196F3"));
        dataSet.setCircleHoleColor(Color.WHITE);
        dataSet.setCircleRadius(4f);
        dataSet.setCircleHoleRadius(2.5f);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);      // 平滑曲线
        dataSet.setDrawValues(false);                         // 不显示数值（解决0.00问题）

        LineData lineData = new LineData(dataSet);


        // ========== X 轴 ==========
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(xLabels));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);
        xAxis.setDrawGridLines(false);
        xAxis.setLabelCount(xLabels.size(), true);

        // ========== Y 轴（从0开始，自动叠加） ==========
        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.argb(13, 0, 0, 0));
        leftAxis.setGranularity(1f);
        leftAxis.setAxisMinimum(0f);                          // 固定从0开始

        // 动态最大值，自动留白
        float maxVal = 0f;
        for (Entry e : userEntries) {
            if (e.getY() > maxVal) maxVal = e.getY();
        }
        leftAxis.setAxisMaximum(Math.max(maxVal + 1, 5));    // 至少显示到5

        YAxis rightAxis = lineChart.getAxisRight();
        rightAxis.setEnabled(false);

        // ========== 禁用所有滑动/缩放，固定视图 ==========
        lineChart.setScaleEnabled(false);
        lineChart.setDragEnabled(false);
        lineChart.setPinchZoom(false);

        // ========== 隐藏图例和描述 ==========
        lineChart.getLegend().setEnabled(false);
        lineChart.getDescription().setEnabled(false);
        lineChart.setDrawGridBackground(false);
        lineChart.setExtraOffsets(10f, 10f, 10f, 10f);

        lineChart.setData(lineData);
        lineChart.animateXY(800, 800);
        lineChart.invalidate();
    }
}