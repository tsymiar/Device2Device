package com.tsymiar.device2device.avatar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.opengl.GLSurfaceView;

/**
 * 人体模型预览控件：单指拖动旋转、双指捏合缩放（张开=放大，收缩=缩小）、双击复位视角。
 */
public class AvatarSurfaceView extends GLSurfaceView {

    private final AvatarRenderer mRenderer;
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;
    private float mLastX;
    private float mLastY;
    private boolean mRotating;

    public AvatarSurfaceView(Context context) {
        super(context);
        mRenderer = init();
        mScaleDetector = new ScaleGestureDetector(context, scaleListener());
        mGestureDetector = new GestureDetector(context, gestureListener());
    }

    public AvatarSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRenderer = init();
        mScaleDetector = new ScaleGestureDetector(context, scaleListener());
        mGestureDetector = new GestureDetector(context, gestureListener());
    }

    private AvatarRenderer init() {
        AvatarRenderer renderer = new AvatarRenderer();
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        return renderer;
    }

    private ScaleGestureDetector.OnScaleGestureListener scaleListener() {
        return new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                // getScaleFactor() = 当前间距 / 上次间距：张开 > 1 → 放大；收缩 < 1 → 缩小
                mRenderer.zoom(detector.getScaleFactor());
                requestRender();
                return true;
            }
        };
    }

    private GestureDetector.SimpleOnGestureListener gestureListener() {
        return new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetView();
                return true;
            }
        };
    }

    public AvatarRenderer getAvatarRenderer() {
        return mRenderer;
    }

    public void setMesh(final HumanMesh.Result r) {
        // 缓冲区的生产者/消费者分别位于 UI 线程与 GL 线程，统一丢到 GL 线程切换
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setMesh(r);
                requestRender();
            }
        });
    }

    public void resetView() {
        mRenderer.resetView();
        requestRender();
    }

    public void capture(AvatarRenderer.CaptureCallback cb) {
        mRenderer.requestCapture(cb);
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // 双击复位由 GestureDetector 处理，捏合缩放交给 ScaleGestureDetector（方向由系统统一）
        mGestureDetector.onTouchEvent(e);
        mScaleDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = e.getX();
                mLastY = e.getY();
                mRotating = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mRotating = false;      // 进入捏合，暂停旋转
                break;
            case MotionEvent.ACTION_MOVE:
                if (mRotating && !mScaleDetector.isInProgress() && e.getPointerCount() == 1) {
                    float dx = e.getX() - mLastX;
                    float dy = e.getY() - mLastY;
                    mRenderer.rotate(-dx * 0.45f, dy * 0.30f);
                    mLastX = e.getX();
                    mLastY = e.getY();
                    requestRender();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                // 松开一根手指后，以剩下那根为基准继续旋转，避免视角跳变
                int idx = e.getActionIndex() == 0 ? 1 : 0;
                if (idx < e.getPointerCount()) {
                    mLastX = e.getX(idx);
                    mLastY = e.getY(idx);
                    mRotating = true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mRotating = false;
                break;
            default:
                break;
        }
        return true;
    }
}
