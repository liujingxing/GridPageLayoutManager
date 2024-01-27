package com.ljx.layoutmanager;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridPageLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.State;

/**
 * User: ljx
 * Date: 2024/1/22
 * Time: 13:55
 */
public class GridPageLinearSmoothScroller extends LinearSmoothScroller {

    private static final float MILLISECONDS_PER_INCH = 100f;
    private static final int MAX_SCROLL_ON_FLING_DURATION = 100; // ms
    private final GridPageLayoutManager layoutManager;

    public GridPageLinearSmoothScroller(Context context, GridPageLayoutManager layoutManager) {
        super(context);
        this.layoutManager = layoutManager;
    }

    @Override
    protected void onTargetFound(@NonNull View targetView, @NonNull RecyclerView.State state, @NonNull Action action) {
        updateAction(layoutManager.getPosition(targetView), action);
    }

    @Override
    protected void onSeekTargetStep(int dx, int dy, State state, Action action) {
        if (dx == 0 && dy == 0) {
            if (updateAction(getTargetPosition(), action)) {
                stop();
            }
        }
    }

    private boolean updateAction(int targetPosition, Action action) {
        int[] distances = layoutManager.distanceToPosition(targetPosition);
        int time = calculateTimeForDeceleration(Math.max(Math.abs(distances[0]), Math.abs(distances[1])));
        if (time > 0) {
            action.update(distances[0], distances[1], time, mDecelerateInterpolator);
            return true;
        }
        return false;
    }

    @Override
    protected void onChildAttachedToWindow(View child) {
        //一定要重写该方法，并且空实现，不然onSeekTargetStep方法实现的动画，将会被打断，并执行onTargetFound动画，导致动画不连贯
    }

    @Override
    protected float calculateSpeedPerPixel(@NonNull DisplayMetrics displayMetrics) {
        return MILLISECONDS_PER_INCH / displayMetrics.densityDpi;
    }

    @Override
    protected int calculateTimeForScrolling(int dx) {
        return Math.min(MAX_SCROLL_ON_FLING_DURATION, super.calculateTimeForScrolling(dx));
    }
}
