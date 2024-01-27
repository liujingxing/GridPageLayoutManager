package com.ljx.layoutmanager;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridPageLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.SnapHelper;


/**
 * User: ljx
 * Date: 2023/11/20
 * Time: 21:25
 */
public class GridPageSnapHelper extends SnapHelper {

    private RecyclerView mRecyclerView;

    @Override
    public void attachToRecyclerView(@Nullable RecyclerView recyclerView) throws IllegalStateException {
        this.mRecyclerView = recyclerView;
        super.attachToRecyclerView(recyclerView);
    }

    @Override
    public int findTargetSnapPosition(LayoutManager layoutManager, int velocityX, int velocityY) {
        if (layoutManager instanceof GridPageLayoutManager) {
            GridPageLayoutManager gridPagerLayoutManager = (GridPageLayoutManager) layoutManager;
            View snapView = gridPagerLayoutManager.findSnapViewFromEnd();
            if (snapView != null) {
                int targetPosition = layoutManager.getPosition(snapView);
                int velocity = layoutManager.canScrollHorizontally() ? velocityX : velocityY;
                boolean layoutToEnd = velocity > 0;
                boolean reverseLayout = gridPagerLayoutManager.shouldReverseLayout();
                int itemDirection = layoutToEnd ^ reverseLayout ? 1 : -1;
                return itemDirection > 0 ? targetPosition : targetPosition + itemDirection;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public int[] calculateDistanceToFinalSnap(@NonNull LayoutManager layoutManager, @NonNull View targetView) {
        int[] out = new int[2];
        if (layoutManager instanceof GridPageLayoutManager) {
            GridPageLayoutManager pagerLayoutManager = (GridPageLayoutManager) layoutManager;
            int totalSpace = pagerLayoutManager.getTotalSpace();
            int offset = pagerLayoutManager.getPagerStartOffset(targetView);
            int diff = offset > totalSpace / 2 ? offset - totalSpace : offset;
            if (layoutManager.canScrollHorizontally()) {
                out[0] = diff;
            }
            if (layoutManager.canScrollVertically()) {
                out[1] = diff;
            }
        }
        return out;
    }


    @Override
    public View findSnapView(LayoutManager layoutManager) {
        if (layoutManager instanceof GridPageLayoutManager) {
            return ((GridPageLayoutManager) layoutManager).findSnapViewFromEnd();
        }
        return null;
    }

    protected RecyclerView.SmoothScroller createScroller(
        @NonNull LayoutManager layoutManager) {
        if (!(layoutManager instanceof RecyclerView.SmoothScroller.ScrollVectorProvider)) {
            return null;
        }

        if (!(layoutManager instanceof GridPageLayoutManager)) {
            return null;
        }
        return new GridPageLinearSmoothScroller(mRecyclerView.getContext(), (GridPageLayoutManager) layoutManager);
    }
}



