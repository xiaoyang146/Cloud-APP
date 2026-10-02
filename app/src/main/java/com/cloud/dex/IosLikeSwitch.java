package com.cloud.dex;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * 自定义开关按钮 - 完全复现Uiverse.io的CSS开关效果
 * 特性：圆形滑块、渐变背景、内阴影、滑动动画、ON/OFF文字
 */
public class IosLikeSwitch extends View {

    // 颜色常量 (与CSS完全一致)
    private static final int TRACK_COLOR_OFF = Color.parseColor("#CCCCCC");
    private static final int TRACK_COLOR_ON = Color.parseColor("#4CC9F0");
    private static final int THUMB_COLOR_OFF = Color.WHITE;
    private static final int THUMB_COLOR_ON = Color.parseColor("#4CC9F0");
    private static final int TEXT_COLOR = Color.WHITE;

    // 尺寸比例参考 (基于View高度的百分比)
    private static final float THUMB_SIZE_RATIO = 0.8f;
    private static final float THUMB_MARGIN_RATIO = 0.1f;
    private static final float TEXT_SIZE_RATIO = 0.35f;
    private static final float TEXT_MARGIN_RATIO = 0.15f;

    // 动画时长
    private static final int ANIMATION_DURATION = 400;

    private boolean isChecked = false;
    private ValueAnimator animator;
    private float animateProgress = 0f;

    // 绘制相关
    private Paint trackPaint;
    private Paint thumbPaint;
    private Paint textPaint;
    private Paint innerShadowPaint;
    private RectF trackRect;
    private float thumbSize;
    private float thumbMargin;
    private float textSize;

    // 动态颜色 (动画插值)
    private int currentTrackColor;
    private int currentThumbColor;

    // 阴影相关
    private boolean shadowEnabled = true;

    // 监听器
    private OnCheckedChangeListener onCheckedChangeListener;

    public IosLikeSwitch(Context context) {
        this(context, null);
    }

    public IosLikeSwitch(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IosLikeSwitch(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPaints();
        if (shadowEnabled) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        currentTrackColor = TRACK_COLOR_OFF;
        currentThumbColor = THUMB_COLOR_OFF;
    }

    private void initPaints() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.FILL);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);
        if (shadowEnabled) {
            thumbPaint.setShadowLayer(6f, 0f, 2f, Color.parseColor("#33000000"));
        }

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);

        innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerShadowPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int finalWidth, finalHeight;
        if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
            finalWidth = widthSize;
            finalHeight = heightSize;
            int expectedHeight = widthSize / 2;
            if (Math.abs(expectedHeight - heightSize) > 5) {
                finalHeight = expectedHeight;
            }
        } else if (widthMode == MeasureSpec.EXACTLY) {
            finalWidth = widthSize;
            finalHeight = widthSize / 2;
        } else if (heightMode == MeasureSpec.EXACTLY) {
            finalHeight = heightSize;
            finalWidth = heightSize * 2;
        } else {
            float density = getResources().getDisplayMetrics().density;
            finalWidth = (int) (50 * density);
            finalHeight = (int) (25 * density);
        }
        setMeasuredDimension(finalWidth, finalHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateDimensions();
    }

    private void calculateDimensions() {
        float height = getHeight();
        thumbSize = height * THUMB_SIZE_RATIO;
        thumbMargin = height * THUMB_MARGIN_RATIO;
        textSize = height * TEXT_SIZE_RATIO;
        textPaint.setTextSize(textSize);

        float left = thumbMargin * 0.5f;
        float top = thumbMargin * 0.5f;
        float right = getWidth() - thumbMargin * 0.5f;
        float bottom = getHeight() - thumbMargin * 0.5f;
        trackRect = new RectF(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawTrack(canvas);
        drawThumb(canvas);
        drawText(canvas);
    }

    private void drawTrack(Canvas canvas) {
        float radius = trackRect.height() / 2f;
        trackPaint.setColor(currentTrackColor);
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        if (innerShadowPaint != null) {
            LinearGradient gradient = new LinearGradient(
                    trackRect.left, trackRect.top, trackRect.right, trackRect.bottom,
                    new int[]{Color.parseColor("#20000000"), Color.TRANSPARENT},
                    new float[]{0f, 0.6f},
                    Shader.TileMode.CLAMP
            );
            innerShadowPaint.setShader(gradient);
            canvas.drawRoundRect(trackRect, radius, radius, innerShadowPaint);
            innerShadowPaint.setShader(null);
        }
    }

    private void drawThumb(Canvas canvas) {
        float trackLeft = trackRect.left;
        float trackRight = trackRect.right;
        float thumbLeft = trackLeft + thumbMargin;
        float thumbRight = trackRight - thumbMargin - thumbSize;
        float currentX = thumbLeft + (thumbRight - thumbLeft) * animateProgress;

        float thumbY = trackRect.centerY() - thumbSize / 2f;
        thumbPaint.setColor(currentThumbColor);
        canvas.drawRoundRect(currentX, thumbY, currentX + thumbSize, thumbY + thumbSize,
                thumbSize / 2f, thumbSize / 2f, thumbPaint);
    }

    private void drawText(Canvas canvas) {
        float textBaseY = trackRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
        float textMargin = getHeight() * TEXT_MARGIN_RATIO;

        if (isChecked) {
            float textX = trackRect.left + textMargin + thumbSize * 0.3f;
            canvas.drawText("ON", textX, textBaseY, textPaint);
        } else {
            float textX = trackRect.right - textMargin - thumbSize * 0.3f;
            canvas.drawText("OFF", textX, textBaseY, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                toggle();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void toggle() {
        setChecked(!isChecked);
    }

    public void setChecked(boolean checked) {
        if (isChecked == checked) return;
        isChecked = checked;

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        float targetProgress = isChecked ? 1f : 0f;
        float startProgress = animateProgress;

        animator = ValueAnimator.ofFloat(startProgress, targetProgress);
        animator.setDuration(ANIMATION_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            animateProgress = progress;

            ArgbEvaluator evaluator = new ArgbEvaluator();
            currentTrackColor = (int) evaluator.evaluate(progress, TRACK_COLOR_OFF, TRACK_COLOR_ON);
            currentThumbColor = (int) evaluator.evaluate(progress, THUMB_COLOR_OFF, THUMB_COLOR_ON);

            invalidate();
        });
        animator.start();

        if (onCheckedChangeListener != null) {
            onCheckedChangeListener.onCheckedChanged(this, isChecked);
        }
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.onCheckedChangeListener = listener;
    }

    public void setShadowEnabled(boolean enabled) {
        this.shadowEnabled = enabled;
        if (enabled) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            thumbPaint.setShadowLayer(6f, 0f, 2f, Color.parseColor("#33000000"));
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null);
            thumbPaint.clearShadowLayer();
        }
        invalidate();
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(IosLikeSwitch buttonView, boolean isChecked);
    }
}
