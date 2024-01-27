package com.ljx.layoutmanager;

import static androidx.recyclerview.widget.RecyclerView.OnFlingListener;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridPageLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * User: ljx
 * Date: 2024/1/27
 * Time: 16:54
 */
public class SimpleSnapHelper extends OnFlingListener {

    private final DecelerateInterpolator mDecelerateInterpolator = new DecelerateInterpolator();

    private RecyclerView mRecyclerView;
    private GridPageLayoutManager mLayoutManager;
    // Handles the snap on scroll case.
    private final RecyclerView.OnScrollListener mScrollListener =
        new RecyclerView.OnScrollListener() {
            boolean mScrolled = false;

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE && mScrolled) {
                    mScrolled = false;
                    View snapView = mLayoutManager.findSnapViewFromEnd();
                    if (snapView == null) return;
                    int totalSpace = mLayoutManager.getTotalSpace();
                    int offset = mLayoutManager.getPagerStartOffset(snapView);
                    int diff = offset > totalSpace / 2 ? offset - totalSpace : offset;
                    smoothScrollBy(diff, RecyclerView.UNDEFINED_DURATION, null);
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dx != 0 || dy != 0) {
                    mScrolled = true;
                }
            }
        };

    public void attachToRecyclerView(RecyclerView recyclerView) {
        if (mRecyclerView == recyclerView) return;
        if (mRecyclerView != null) {
            mRecyclerView.removeOnScrollListener(mScrollListener);
            mRecyclerView.setOnFlingListener(null);
        }
        this.mRecyclerView = recyclerView;
        mLayoutManager = (GridPageLayoutManager) recyclerView.getLayoutManager();
        if (mRecyclerView != null) {
            mRecyclerView.addOnScrollListener(mScrollListener);
            mRecyclerView.setOnFlingListener(null);
        }
    }

    private void smoothScrollBy(int step, int time, @Nullable Interpolator interpolator) {
        if (step == 0 || mRecyclerView == null) return;
        if (mLayoutManager.canScrollVertically()) {
            mRecyclerView.smoothScrollBy(0, step, interpolator, time);
        } else {
            mRecyclerView.smoothScrollBy(step, 0, interpolator, time);
        }
    }

    @Override
    public boolean onFling(int velocityX, int velocityY) {
        View snapView = mLayoutManager.findSnapViewFromEnd();
        if (snapView == null) return true;
        boolean isVertical = mLayoutManager.canScrollVertically();
        int velocity = isVertical ? velocityY : velocityX;
        int totalSpace = mLayoutManager.getTotalSpace();
        int offset = mLayoutManager.getPagerStartOffset(snapView);
        int diff = velocity > 0 ? offset : offset - totalSpace;
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        float millisPerPixel = 100f / displayMetrics.densityDpi;
        float time = Math.min(100, Math.abs(diff) * millisPerPixel);
        smoothScrollBy(diff, (int) Math.ceil(time / .3356), mDecelerateInterpolator);
        return true;
    }
}
