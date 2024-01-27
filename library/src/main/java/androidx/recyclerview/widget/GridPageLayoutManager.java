package androidx.recyclerview.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.RecyclerView.OnFlingListener;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import androidx.recyclerview.widget.RecyclerView.Recycler;
import androidx.recyclerview.widget.RecyclerView.SmoothScroller.ScrollVectorProvider;
import androidx.recyclerview.widget.RecyclerView.State;
import androidx.viewpager2.widget.ViewPager2;
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback;
import androidx.viewpager2.widget.ViewPager2.ScrollState;

import com.google.android.material.tabs.IndicatorObserver;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutObserver;
import com.google.android.material.tabs.TabLayoutObserver.TabConfigurationStrategy;
import com.ljx.layoutmanager.CompositeOnPageChangeCallback;
import com.ljx.layoutmanager.GridPageSnapHelper;
import com.ljx.layoutmanager.GridPageLinearSmoothScroller;
import com.ljx.layoutmanager.R;
import com.ljx.layoutmanager.ScrollEventAdapter;
import com.ljx.layoutmanager.ScrollEventAdapter.ScrollEventValues;
import com.ljx.view.IndicatorView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;


/**
 * User: ljx
 * Date: 2023/12/30
 * Time: 18:43
 */
public class GridPageLayoutManager extends LayoutManager implements ScrollVectorProvider {
    private final AnchorInfo mAnchorInfo = new AnchorInfo();
    private final SparseBooleanArray fullSpanArray = new SparseBooleanArray();
    private final LayoutChunkResult mLayoutChunkResult = new LayoutChunkResult();
    private LayoutState mLayoutState;
    private OrientationHelper mOrientationHelper;
    private int mPendingScrollPosition = RecyclerView.NO_POSITION;
    private SavedState mPendingSavedState = null;
    private int[] hBorders;
    private int[] vBorders;
    private int[] pageBorders;
    private int[] queueBorders;
    private int[] queueTailIndexes;
    private View[] mSet;
    private GridPageSnapHelper mPagerSnapHelper;
    private ScrollEventAdapter mScrollEventAdapter;
    private CompositeOnPageChangeCallback mPageChangeEventDispatcher;
    private CompositeOnPageChangeCallback mQueueChangeEventListener;
    private int rowCount;
    private int columnCount;
    private int mOrientation;
    private int mCurrentPage = 0;
    private boolean mReverseLayout;
    private boolean mShouldReverseLayout = false;
    private Adapter<?> mAdapter;
    private TabLayoutObserver tabLayoutMediator;
    private IndicatorObserver indicatorObserver;
    private DataSetChangeObserver mDataSetChangeObserver;

    public GridPageLayoutManager(int rowCount, int columnCount) {
        this(rowCount, columnCount, RecyclerView.HORIZONTAL, false);
    }

    public GridPageLayoutManager(int rowCount, int columnCount, int orientation, boolean reverseLayout) {
        setRowCount(rowCount);
        setColumnCount(columnCount);
        setOrientation(orientation);
        setReverseLayout(reverseLayout);
        initialize();
    }

    public GridPageLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.GridPagerLayoutManager,
            defStyleAttr, defStyleRes);
        int rowCount = ta.getInt(R.styleable.GridPagerLayoutManager_rowCount, 1);
        int columnCount = ta.getInt(R.styleable.GridPagerLayoutManager_columnCount, 1);
        ta.recycle();
        Properties properties = getProperties(context, attrs, defStyleAttr, defStyleRes);
        setRowCount(rowCount);
        setColumnCount(columnCount);
        setOrientation(properties.orientation);
        setReverseLayout(properties.reverseLayout);
        initialize();
    }

    @Override
    void setRecyclerView(RecyclerView recyclerView) {
        super.setRecyclerView(recyclerView);
        if (recyclerView == null) {
            if (mAdapter != null && mDataSetChangeObserver != null) {
                mAdapter.unregisterAdapterDataObserver(mDataSetChangeObserver);
            }
            queueTailIndexes = null;
            mAdapter = null;
            return;
        }
        setAdapter(recyclerView.getAdapter());
    }

    @Override
    public void onAdapterChanged(@Nullable Adapter oldAdapter, @Nullable Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        setAdapter(newAdapter);
    }

    private void setAdapter(Adapter<?> newAdapter) {
        Adapter<?> oldAdapter = mAdapter;
        if (oldAdapter != null && mDataSetChangeObserver != null) {
            oldAdapter.unregisterAdapterDataObserver(mDataSetChangeObserver);
        }
        mAdapter = newAdapter;
        if (tabLayoutMediator != null) {
            tabLayoutMediator.onAdapterChanged(oldAdapter, newAdapter);
        }
        if (mAdapter != null) {
            if (mDataSetChangeObserver == null) {
                mDataSetChangeObserver = new DataSetChangeObserver();
            }
            mAdapter.registerAdapterDataObserver(mDataSetChangeObserver);
            if (oldAdapter != null || queueTailIndexes == null) {
                setQueueTails(new int[]{mAdapter.getItemCount() - 1}, null);
            }
        }
    }

    public Adapter<?> getAdapter() {
        return mAdapter;
    }

    public int getCurrentQueueItem() {
        return getQueueIndex(mCurrentPage);
    }

    private int getQueueIndex(int pageIndex) {
        if (queueBorders == null) return -1;
        for (int i = queueBorders.length - 1; i >= 0; i--) {
            if (pageIndex >= queueBorders[i]) {
                return i;
            }
        }
        return -1;
    }

    private void initialize() {
        mPageChangeEventDispatcher = new CompositeOnPageChangeCallback(3);
        final OnPageChangeCallback currentItemUpdater = new OnPageChangeCallback() {


            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (mQueueChangeEventListener != null) {
                    int queueIndex = getQueueIndex(position);
                    if (queueIndex == -1) return;
                    int nextQueueIndex = queueIndex + 1;
                    if (position + 1 == queueBorders[nextQueueIndex] && positionOffset > 0) {
                        mQueueChangeEventListener.onPageScrolled(queueIndex, positionOffset, positionOffsetPixels);
                    }
                }
            }

            @Override
            public void onPageSelected(int position) {
                if (mCurrentPage != position) {
                    mCurrentPage = position;
                }
                if (queueBorders == null) return;
                if (mQueueChangeEventListener != null) {
                    int queueIndex = getQueueIndex(position);
                    if (queueIndex == -1) return;
                    mQueueChangeEventListener.onPageSelected(queueIndex);
                }
            }

            @Override
            public void onPageScrollStateChanged(int newState) {
                if (mQueueChangeEventListener != null) {
                    mQueueChangeEventListener.onPageScrollStateChanged(newState);
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrentItem();
                }
            }
        };
        mPageChangeEventDispatcher.addOnPageChangeCallback(currentItemUpdater);
        mScrollEventAdapter = new ScrollEventAdapter(this);
        mScrollEventAdapter.setOnPageChangeCallback(mPageChangeEventDispatcher);
    }

    private void updateCurrentItem() {
        View snapView = findSnapViewFromEnd();
        if (snapView == null) {
            return; // nothing we can do
        }
        LayoutParams layoutParams = (LayoutParams) snapView.getLayoutParams();
        int pageIndex = layoutParams.pageIndex;
        if (pageIndex != mCurrentPage && getScrollState() == ViewPager2.SCROLL_STATE_IDLE) {
            mPageChangeEventDispatcher.onPageSelected(pageIndex);
        }
    }

    private int getHorizontalSpanRange(int spanIndex, int spanSize) {
        if (mOrientation == RecyclerView.VERTICAL && isLayoutRTL()) {
            int startIndex = columnCount - spanIndex;
            return hBorders[startIndex] - hBorders[startIndex - spanSize];
        }
        return hBorders[spanIndex + spanSize] - hBorders[spanIndex];
    }

    private void resolveShouldLayoutReverse() {
        // A == B is the same result, but we rather keep it readable
        if (mOrientation == RecyclerView.VERTICAL || !isLayoutRTL()) {
            mShouldReverseLayout = mReverseLayout;
        } else {
            mShouldReverseLayout = !mReverseLayout;
        }
    }

    public void attach(IndicatorView indicatorView) {
        if (indicatorObserver != null) {
            indicatorObserver.detach();
            indicatorObserver = null;
        }
        if (indicatorView != null) {
            indicatorObserver = new IndicatorObserver(indicatorView, this);
            indicatorObserver.attach();
        }
    }

    public void attach(TabLayout tabLayout, TabConfigurationStrategy tabConfigurationStrategy) {
        attach(tabLayout, true, true, tabConfigurationStrategy);
    }

    public void attach(TabLayout tabLayout, boolean autoRefresh, TabConfigurationStrategy tabConfigurationStrategy) {
        attach(tabLayout, autoRefresh, true, tabConfigurationStrategy);
    }

    public void attach(TabLayout tabLayout, boolean autoRefresh, boolean smoothScroll, TabConfigurationStrategy tabConfigurationStrategy) {
        if (tabLayoutMediator != null) {
            tabLayoutMediator.detach();
            tabLayoutMediator = null;
        }
        if (tabLayout != null) {
            tabLayoutMediator = new TabLayoutObserver(tabLayout, this, autoRefresh, smoothScroll, tabConfigurationStrategy);
            tabLayoutMediator.attach();
        }
    }

    public boolean getReverseLayout() {
        return mReverseLayout;
    }

    public void setReverseLayout(boolean reverseLayout) {
        if (reverseLayout == mReverseLayout) {
            return;
        }
        mReverseLayout = reverseLayout;
        requestLayout();
    }

    public boolean shouldReverseLayout() {
        return mShouldReverseLayout;
    }

    public boolean isLayoutRTL() {
        return getLayoutDirection() == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    void ensureLayoutState() {
        if (mLayoutState == null) {
            mLayoutState = new LayoutState();
        }
    }

    private void ensureViewSet(int spanCount) {
        if (mSet == null || mSet.length != spanCount) {
            mSet = new View[spanCount];
        }
    }

    @Override
    public void onLayoutChildren(Recycler recycler, State state) {
        if (getItemCount() <= 0 || state.isPreLayout()) {
            return;
        }
        if (mPendingSavedState != null || mPendingScrollPosition != RecyclerView.NO_POSITION) {
            if (state.getItemCount() == 0) {
                removeAndRecycleAllViews(recycler);
                return;
            }
        }
        if (mPendingSavedState != null && mPendingSavedState.hasValidAnchor()) {
            mPendingScrollPosition = mPendingSavedState.mAnchorPosition;
        }

        ensureLayoutState();
        mLayoutState.mRecycle = false;
        resolveShouldLayoutReverse();

        int widthSpace = getWidth() - getPaddingLeft() - getPaddingRight();
        int heightSpace = getHeight() - getPaddingTop() - getPaddingBottom();
        hBorders = calculateItemBorders(hBorders, columnCount, widthSpace);
        vBorders = calculateItemBorders(vBorders, rowCount, heightSpace);

        if (!mAnchorInfo.mValid || mPendingScrollPosition != RecyclerView.NO_POSITION
            || mPendingSavedState != null) {
            mAnchorInfo.reset();
            mAnchorInfo.mLayoutFromEnd = mShouldReverseLayout;
            // calculate anchor position and coordinate
            updateAnchorInfoForLayout(state, mAnchorInfo);
            mAnchorInfo.mValid = true;
        }
        int spanCount = mOrientation == RecyclerView.VERTICAL ? columnCount : rowCount;
        ensureViewSet(spanCount);
        detachAndScrapAttachedViews(recycler);
        if (mShouldReverseLayout) {
            updateLayoutStateToFillStart(mAnchorInfo);
        } else {
            updateLayoutStateToFillEnd(mAnchorInfo);
        }
        fill(recycler, mLayoutState, state, false);
    }

    @Override
    public void onLayoutCompleted(State state) {
        super.onLayoutCompleted(state);
        mPendingSavedState = null; // we don't need this anymore
        mPendingScrollPosition = RecyclerView.NO_POSITION;
        mAnchorInfo.reset();
    }

    private int getVerticalSpanRange(int spanIndex, int spanSize) {
        return vBorders[spanIndex + spanSize] - vBorders[spanIndex];
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        view.addOnScrollListener(mScrollEventAdapter);
        //处理滑动
//        view.setOnFlingListener(mOnFlingListener);
//        //设置滚动监听，记录滚动的状态，和总的偏移量
//        view.addOnScrollListener(mOnScrollListener);
        if (mPagerSnapHelper == null) {
            mPagerSnapHelper = new GridPageSnapHelper();
        }
        mPagerSnapHelper.attachToRecyclerView(view);
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        if (mPagerSnapHelper != null) {
            mPagerSnapHelper.attachToRecyclerView(null);
        }
        view.removeOnScrollListener(mScrollEventAdapter);
    }

    @Nullable
    @Override
    public Parcelable onSaveInstanceState() {
        if (mPendingSavedState != null) {
            return new SavedState(mPendingSavedState);
        }
        SavedState state = new SavedState();
        final View snapView = findSnapViewFromEnd();
        if (snapView != null) {
            state.mAnchorPosition = getPosition(snapView);
        } else {
            state.invalidateAnchor();
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof SavedState) {
            mPendingSavedState = (SavedState) state;
            if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
                mPendingSavedState.invalidateAnchor();
            }
            requestLayout();
        }
    }


    private void updateAnchorInfoForLayout(State state, AnchorInfo anchorInfo) {
        if (updateAnchorFromPendingData(state, anchorInfo)) {
            return;
        }
        View child = getChildAt(0);
        if (child != null) {
            final View focused = getFocusedChild();
            if (focused != null && anchorInfo.isViewValidAsAnchor(focused, state)) {
                anchorInfo.assignFromViewAndKeepVisibleRect(focused, getPosition(focused));
                return;
            }
            LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
            int pageIndex = Math.min(pageBorders.length - 2, layoutParams.pageIndex);
            anchorInfo.mPosition = pageBorders[pageIndex];
        } else {
            anchorInfo.mPosition = 0;
        }
        anchorInfo.assignCoordinateFromPadding();
    }

    private boolean updateAnchorFromPendingData(State state, AnchorInfo anchorInfo) {
        if (state.isPreLayout() || mPendingScrollPosition == RecyclerView.NO_POSITION) {
            return false;
        }
        // validate scroll position
        if (mPendingScrollPosition < 0 || mPendingScrollPosition >= state.getItemCount()) {
            mPendingScrollPosition = RecyclerView.NO_POSITION;
            return false;
        }

        // if child is visible, try to make it a reference child and ensure it is fully visible.
        // if child is not visible, align it depending on its virtual position.
        anchorInfo.mPosition = mPendingScrollPosition;
        // override layout from end values for consistency
        anchorInfo.mLayoutFromEnd = mShouldReverseLayout;
        // if this changes, we should update prepareForDrop as well
        if (mShouldReverseLayout) {
            anchorInfo.mCoordinate = mOrientationHelper.getEndAfterPadding();
        } else {
            anchorInfo.mCoordinate = mOrientationHelper.getStartAfterPadding();
        }
        return true;
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return mPendingSavedState == null;
    }

    @Override
    public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
        if (mOrientation == RecyclerView.HORIZONTAL) {
            return 0;
        }
        ensureViewSet(columnCount);
        return scrollBy(dy, recycler, state);
    }

    @Override
    public int scrollHorizontallyBy(int dx, Recycler recycler, State state) {
        if (mOrientation == RecyclerView.VERTICAL) {
            return 0;
        }
        ensureViewSet(rowCount);
        return scrollBy(dx, recycler, state);
    }

    int scrollBy(int delta, Recycler recycler, State state) {
        if (getChildCount() == 0 || delta == 0) {
            return 0;
        }
        ensureLayoutState();
        mLayoutState.mRecycle = true;
        final int layoutDirection = delta > 0 ? LayoutState.LAYOUT_END : LayoutState.LAYOUT_START;
        final int absDelta = Math.abs(delta);
        updateLayoutState(layoutDirection, absDelta, true, state);
        int consumed = mLayoutState.mScrollingOffset + fill(recycler, mLayoutState, state, false);

        if (consumed < 0) {
            return 0;
        }
        int scrolled = absDelta > consumed ? layoutDirection * consumed : delta;
        if (scrolled != 0) {
            mOrientationHelper.offsetChildren(-scrolled);
        }
        mLayoutState.mLastScrollDelta = scrolled;
        return scrolled;
    }

    int fill(Recycler recycler, LayoutState layoutState, State state, boolean stopOnFocusable) {
        // max offset we should set is mFastScroll + available
        final int start = layoutState.mAvailable;
        if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
            // TODO ugly bug fix. should not happen
            if (layoutState.mAvailable < 0) {
                layoutState.mScrollingOffset += layoutState.mAvailable;
            }
            recycleByLayoutState(recycler, layoutState);
        }
        int remainingSpace = layoutState.mAvailable + layoutState.mExtraFillSpace;
        LayoutChunkResult layoutChunkResult = mLayoutChunkResult;
        boolean isVertical = mOrientation == RecyclerView.VERTICAL;
        int curPage = layoutState.page;
        while (remainingSpace > 0 && curPage >= 0 && curPage < pageBorders.length - 1) {
            layoutChunkResult.resetInternal();

            int fromPosition = pageBorders[curPage];
            int toPosition = pageBorders[curPage + 1];
            if (fullSpanArray.get(fromPosition) && toPosition - fromPosition == 1) {
                layoutState.row = 0;
                layoutState.column = 0;
            }

            layoutChunk(recycler, state, layoutState, layoutChunkResult);

            int consumedSpanSize = layoutChunkResult.consumedSpanSize;
            int curRow = layoutState.row;
            int curColumn = layoutState.column;
            int consumed = isVertical ? getVerticalSpanRange(curRow, consumedSpanSize) :
                getHorizontalSpanRange(curColumn, consumedSpanSize);
            if (remainingSpace > consumed) {
                updateSpanInfo(curPage, curRow, curColumn, consumedSpanSize);
                int nextPage = layoutState.page;
                if (nextPage != curPage) {
                    if (!getClipToPadding()) {
                        int padding = mOrientationHelper.getStartAfterPadding() + mOrientationHelper.getEndPadding();
                        consumed += padding;
                    }
                    if (nextPage > curPage) {
                        //需要处理某一页不足rows行或不足columns列的情况，避免数据位置不正确问题
                        int remainingRange;
                        if (isVertical) {
                            remainingRange = vBorders[rowCount] - vBorders[curRow + consumedSpanSize];
                        } else {
                            remainingRange = hBorders[columnCount] - hBorders[curColumn + consumedSpanSize];
                        }
                        //如果数据不足以填充columns列，则显示空的
                        consumed += remainingRange;
                    }
                }
                curPage = nextPage;
            }
            layoutChunkResult.mConsumed = consumed;
            if (layoutChunkResult.mFinished) {
                break;
            }
            layoutState.mOffset += layoutChunkResult.mConsumed * layoutState.mLayoutDirection;
            /**
             * Consume the available space if:
             * * layoutChunk did not request to be ignored
             * * OR we are laying out scrap children
             * * OR we are not doing pre-layout
             */
            if (!layoutChunkResult.mIgnoreConsumed || !state.isPreLayout()) {
                layoutState.mAvailable -= layoutChunkResult.mConsumed;
                // we keep a separate remaining space because mAvailable is important for recycling
                remainingSpace -= layoutChunkResult.mConsumed;
            }

            if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
                layoutState.mScrollingOffset += layoutChunkResult.mConsumed;
                if (layoutState.mAvailable < 0) {
                    layoutState.mScrollingOffset += layoutState.mAvailable;
                }
                recycleByLayoutState(recycler, layoutState);
            }
            if (stopOnFocusable && layoutChunkResult.mFocusable) {
                break;
            }
        }
        return start - layoutState.mAvailable;
    }

    private void layoutChunk(Recycler recycler, State state,
                             LayoutState layoutState, LayoutChunkResult layoutChunkResult) {

        int itemDirection = layoutState.mItemDirection;
        boolean layingOutInPrimaryDirection = itemDirection == LayoutState.ITEM_DIRECTION_TAIL;
        boolean isVertical = mOrientation == RecyclerView.VERTICAL;

        int page = layoutState.getPage();
        int row = layoutState.getRow();
        int column = layoutState.getColumn();
        int spanCount = mSet.length;
        int spanIndex = layingOutInPrimaryDirection ? 0 : spanCount - 1;
        int fromPosition = pageBorders[page];
        int toPosition = pageBorders[page + 1];
        int count = 0;
        int consumedSpanSize = 1;
        while (spanIndex >= 0 && spanIndex < spanCount) {
            boolean fullSpan = fullSpanArray.get(fromPosition) && toPosition - fromPosition == 1;
            if (fullSpan) spanIndex = 0;
            int offsetPosition = isVertical ? row * columnCount + spanIndex : spanIndex * columnCount + column;
            int curPosition = fromPosition + offsetPosition;
            int spanSize = 1;
            if (curPosition < toPosition) {
                layoutState.mCurrentPosition = curPosition;
                View view = recycler.getViewForPosition(curPosition);
                LayoutParams layoutParams = (LayoutParams) view.getLayoutParams();
                layoutParams.pageIndex = page;
                int rowSpanSize = fullSpan ? rowCount : 1;
                int columnSpanSize = fullSpan ? columnCount : 1;
                layoutParams.rowSpanSize = rowSpanSize;
                layoutParams.columnSpanSize = columnSpanSize;
                if (isVertical) {
                    consumedSpanSize = rowSpanSize;
                    spanSize = columnSpanSize;
                    layoutParams.rowIndex = row;
                    layoutParams.columnIndex = spanIndex;
                    layoutState.column = spanIndex;
                } else {
                    consumedSpanSize = columnSpanSize;
                    spanSize = rowSpanSize;
                    layoutParams.rowIndex = spanIndex;
                    layoutParams.columnIndex = column;
                    layoutState.row = spanIndex;
                }
                mSet[count++] = view;
            }
            spanIndex += itemDirection * spanSize;
        }
        mLayoutChunkResult.consumedSpanSize = consumedSpanSize;

        if (count == 0) {
            return;
        }

        int widthSpace = getWidth() - getPaddingLeft() - getPaddingRight();
        int heightSpace = getHeight() - getPaddingTop() - getPaddingBottom();

        int offset = layoutState.mOffset;
        for (int i = 0; i < count; i++) {
            View view = mSet[i];
            if (layingOutInPrimaryDirection) {
                addView(view);
            } else {
                addView(view, 0);
            }
            LayoutParams params = (LayoutParams) view.getLayoutParams();
            int rowIndex = params.rowIndex;
            int rowSpanSize = params.rowSpanSize;
            int columnIndex = params.columnIndex;
            int columnSpanSize = params.columnSpanSize;
            int rowHeight = getVerticalSpanRange(rowIndex, rowSpanSize);
            int columnWidth = getHorizontalSpanRange(columnIndex, columnSpanSize);
            int widthUsed = widthSpace - columnWidth;
            int heightUses = heightSpace - rowHeight;
            measureChildWithMargins(view, widthUsed, heightUses);

            int itemWidth = getDecoratedMeasuredWidth(view) + params.leftMargin + params.rightMargin;
            int itemHeight = getDecoratedMeasuredHeight(view) + params.topMargin + params.bottomMargin;

            int left, right, top, bottom;
            if (isVertical) {
                if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
                    if (mShouldReverseLayout) {
                        bottom = offset;
                        top = bottom - itemHeight;
                    } else {
                        top = offset - rowHeight;
                        bottom = top + itemHeight;
                    }
                } else {
                    if (mShouldReverseLayout) {
                        bottom = offset + rowHeight;
                        top = bottom - itemHeight;
                    } else {
                        top = offset;
                        bottom = top + itemHeight;
                    }
                }
                if (isLayoutRTL()) {
                    right = getPaddingLeft() + hBorders[spanCount - columnIndex];
                    left = right - itemWidth;
                } else {
                    left = getPaddingLeft() + hBorders[columnIndex];
                    right = left + itemWidth;
                }
            } else {
                if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
                    if (mShouldReverseLayout) {
                        right = offset;
                        left = right - itemWidth;
                    } else {
                        left = offset - columnWidth;
                        right = left + itemWidth;
                    }
                } else {
                    if (mShouldReverseLayout) {
                        right = offset + columnWidth;
                        left = right - itemWidth;
                    } else {
                        left = offset;
                        right = left + itemWidth;
                    }
                }
                top = getPaddingTop() + vBorders[rowIndex];
                bottom = top + itemHeight;
            }
            layoutDecoratedWithMargins(view, left, top, right, bottom);
            if (params.isItemRemoved() || params.isItemChanged()) {
                layoutChunkResult.mIgnoreConsumed = true;
            }
            layoutChunkResult.mFocusable |= view.hasFocusable();
        }
        Arrays.fill(mSet, null);
    }

    private void recycleByLayoutState(Recycler recycler, LayoutState layoutState) {
        if (!layoutState.mRecycle) {
            return;
        }
        int scrollingOffset = layoutState.mScrollingOffset;
        int noRecycleSpace = layoutState.mNoRecycleSpace;
        if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
            recycleViewsFromEnd(recycler, scrollingOffset, noRecycleSpace);
        } else {
            recycleViewsFromStart(recycler, scrollingOffset, noRecycleSpace);
        }
    }

    private void recycleViewsFromStart(Recycler recycler, int scrollingOffset,
                                       int noRecycleSpace) {
        if (scrollingOffset < 0) {
            return;
        }
        // ignore padding, ViewGroup may not clip children.
        final int limit = scrollingOffset - noRecycleSpace;
        final int childCount = getChildCount();
        if (mShouldReverseLayout) {
            for (int i = childCount - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (mOrientationHelper.getDecoratedEnd(child) > limit
                    || mOrientationHelper.getTransformedEndWithDecoration(child) > limit) {
                    // stop here
                    recycleChildren(recycler, childCount - 1, i);
                    return;
                }
            }
        } else {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (mOrientationHelper.getDecoratedEnd(child) > limit
                    || mOrientationHelper.getTransformedEndWithDecoration(child) > limit) {
                    // stop here
                    recycleChildren(recycler, 0, i);
                    return;
                }
            }
        }
    }

    private void recycleViewsFromEnd(Recycler recycler, int scrollingOffset,
                                     int noRecycleSpace) {
        final int childCount = getChildCount();
        if (scrollingOffset < 0) {
            return;
        }
        final int limit = mOrientationHelper.getEnd() - scrollingOffset + noRecycleSpace;
        if (mShouldReverseLayout) {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (mOrientationHelper.getDecoratedStart(child) < limit
                    || mOrientationHelper.getTransformedStartWithDecoration(child) < limit) {
                    // stop here
                    recycleChildren(recycler, 0, i);
                    return;
                }
            }
        } else {
            for (int i = childCount - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (mOrientationHelper.getDecoratedStart(child) < limit
                    || mOrientationHelper.getTransformedStartWithDecoration(child) < limit) {
                    // stop here
                    recycleChildren(recycler, childCount - 1, i);
                    return;
                }
            }
        }
    }

    /**
     * Recycles children between given indices.
     *
     * @param startIndex inclusive
     * @param endIndex   exclusive
     */
    private void recycleChildren(Recycler recycler, int startIndex, int endIndex) {
        if (startIndex == endIndex) {
            return;
        }
        if (endIndex > startIndex) {
            for (int i = endIndex - 1; i >= startIndex; i--) {
                removeAndRecycleViewAt(i, recycler);
            }
        } else {
            for (int i = startIndex; i > endIndex; i--) {
                removeAndRecycleViewAt(i, recycler);
            }
        }
    }

    private void updateLayoutStateToFillEnd(int position, int offset) {
        mLayoutState.mAvailable = mOrientationHelper.getEndAfterPadding() - offset;
        mLayoutState.mItemDirection = mShouldReverseLayout ? LayoutState.ITEM_DIRECTION_HEAD
            : LayoutState.ITEM_DIRECTION_TAIL;
        mLayoutState.mCurrentPosition = position;
        mLayoutState.mLayoutDirection = LayoutState.LAYOUT_END;
        mLayoutState.mNoRecycleSpace = 0;
        mLayoutState.mExtraFillSpace = 0;
        mLayoutState.mOffset = offset;
        mLayoutState.page = findPageByPosition(position);
        mLayoutState.column = 0;
        mLayoutState.row = 0;
        mLayoutState.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN;
    }

    private void updateLayoutStateToFillStart(int position, int offset) {
        mLayoutState.mAvailable = offset - mOrientationHelper.getStartAfterPadding();
        mLayoutState.mItemDirection = mShouldReverseLayout ? LayoutState.ITEM_DIRECTION_TAIL :
            LayoutState.ITEM_DIRECTION_HEAD;
        mLayoutState.mCurrentPosition = position;
        mLayoutState.mLayoutDirection = LayoutState.LAYOUT_START;
        mLayoutState.mNoRecycleSpace = 0;
        mLayoutState.mExtraFillSpace = 0;
        mLayoutState.mOffset = offset;
        mLayoutState.page = findPageByPosition(position);
        mLayoutState.column = 0;
        mLayoutState.row = 0;
        mLayoutState.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN;
    }

    private void updateLayoutStateToFillEnd(AnchorInfo anchorInfo) {
        updateLayoutStateToFillEnd(anchorInfo.mPosition, anchorInfo.mCoordinate);
    }

    private void updateLayoutStateToFillStart(AnchorInfo anchorInfo) {
        updateLayoutStateToFillStart(anchorInfo.mPosition, anchorInfo.mCoordinate);
    }

    private int nextRow(int curRow, int spanSize) {
        return (curRow + spanSize + rowCount) % rowCount;
    }

    private int nextColumn(int curColumn, int spanSize) {
        return (curColumn + spanSize + columnCount) % columnCount;
    }

    private boolean isLastRow(int row) {
        return row + 1 == rowCount;
    }

    public int[] setQueueInfo(Collection<? extends Collection<?>> lists) {
        return setQueueInfo(lists, null);
    }

    public int[] setQueueInfo(Collection<? extends Collection<?>> lists, int[] fullSpanIndexes) {
        int queueSize = lists.size();
        //记录每个队列尾部下标
        int[] queueTails = new int[queueSize];
        Iterator<? extends Collection<?>> iterator = lists.iterator();
        int i = 0;
        while (iterator.hasNext()) {
            Collection<?> next = iterator.next();
            int size = next.size();
            queueTails[i] = i == 0 ? size - 1 : queueTails[i - 1] + size;
            i++;
        }
        return setQueueTails(queueTails, fullSpanIndexes);
    }

    public int[] setQueueTails(int[] queueTailIndexes, @Nullable int[] fullSpanIndexes) {
        fullSpanArray.clear();
        if (fullSpanIndexes != null) {
            for (int index : fullSpanIndexes) {
                fullSpanArray.put(index, true);
            }
        }
        this.queueTailIndexes = queueTailIndexes;
        return updatePageBorders();
    }

    private int[] updatePageBorders() {
        if (queueTailIndexes == null) return null;
        int maxSizePerPage = rowCount * columnCount;
        List<Integer> pageSizes = new ArrayList<>(); //记录每一页的item数量
        int length = queueTailIndexes.length;
        int[] queueBorders = new int[length + 1]; //记录每个队列的起始页码下标
        for (int i = 0; i < length; i++) {
            int tailIndex = queueTailIndexes[i];   //队尾下标
            int headIndex = i == 0 ? 0 : queueTailIndexes[i - 1] + 1;  //队头下标
            int queueSize = i == 0 ? tailIndex + 1 : tailIndex - queueTailIndexes[i - 1];
            if (fullSpanArray.get(headIndex)) {
                pageSizes.add(1);
                queueSize -= 1;
            }
            while (queueSize > 0) {
                int pageSize = Math.min(queueSize, maxSizePerPage);
                pageSizes.add(pageSize);
                queueSize -= maxSizePerPage;
            }
            queueBorders[i + 1] = pageSizes.size();
        }
        this.queueBorders = queueBorders;
        mPageChangeEventDispatcher.onPageCountChange(queueBorders[length]);
        int pageCount = pageSizes.size();
        this.pageBorders = new int[pageCount + 1];
        for (int i = 1; i < pageCount + 1; i++) {
            int pageSize = pageSizes.get(i - 1);
            pageBorders[i] = pageBorders[i - 1] + pageSize;
        }
        return queueBorders;
    }

    private boolean isPageTail(int position) {
        for (int i = pageBorders.length - 1; i >= 0; i--) {
            int border = pageBorders[i];
            if (position + 1 == border)
                return true;
        }
        return false;
    }

    private boolean isFirstRow(int row) {
        return row == 0;
    }

    private boolean isLastColumn(int column) {
        return column + 1 == columnCount;
    }

    private boolean isFirstColumn(int column) {
        return column == 0;
    }

    private void updateLayoutState(int layoutDirection, int requiredSpace,
                                   boolean canUseExistingSpace, State state) {
        // If parent provides a hint, don't measure unlimited.
        boolean isVertical = mOrientation == RecyclerView.VERTICAL;
        mLayoutState.mNoRecycleSpace = 0;
        mLayoutState.mExtraFillSpace = 0;
        mLayoutState.mLayoutDirection = layoutDirection;
        boolean layoutToEnd = layoutDirection == LayoutState.LAYOUT_END;
        int itemDirection = layoutToEnd ^ mShouldReverseLayout ? LayoutState.ITEM_DIRECTION_TAIL
            : LayoutState.ITEM_DIRECTION_HEAD;
        boolean itemToTail = itemDirection == LayoutState.ITEM_DIRECTION_TAIL;
        mLayoutState.mItemDirection = itemDirection;
        View anchorView = getChildAt(itemToTail ? getChildCount() - 1 : 0);
        if (anchorView == null) return;

        mLayoutState.mCurrentPosition = getPosition(anchorView);
        LayoutParams layoutParams = (LayoutParams) anchorView.getLayoutParams();
        int curPage = layoutParams.getPageIndex(); //第几页
        int curRow = layoutParams.getRowIndex(); //某页中第几行
        int curColumn = layoutParams.getColumnIndex(); //某页中第几列
        int curRowSpanSize = layoutParams.getRowSpanSize();
        int curColumnSpanSize = layoutParams.getColumnSpanSize();
        int spanSize = isVertical ? curRowSpanSize : curColumnSpanSize;
        updateSpanInfo(curPage, curRow, curColumn, spanSize);
        int nextPage = mLayoutState.getPage();

        int offset = mShouldReverseLayout ? mOrientationHelper.getDecoratedEnd(anchorView) :
            mOrientationHelper.getDecoratedStart(anchorView);
        if (itemToTail) {
            int rowHeight = getVerticalSpanRange(curRow, curRowSpanSize);
            int columnWidth = getHorizontalSpanRange(curColumn, curColumnSpanSize);
            offset += (isVertical ? rowHeight : columnWidth) * layoutDirection;
        }

        int scrollingOffset;
        int startPadding = mOrientationHelper.getStartAfterPadding();
        int endPadding = mOrientationHelper.getEndPadding();
        int padding = startPadding + endPadding;
        boolean clipToPadding = getClipToPadding();
        if (layoutToEnd) {
            scrollingOffset = offset - mOrientationHelper.getEndAfterPadding();
            if (clipToPadding) {//mNoRecycleSpace如果不带上padding，如果设置了padding参数，将会出现不及时回收的情况
                mLayoutState.mNoRecycleSpace = -startPadding;
            } else {
                if (nextPage != curPage) {
                    mLayoutState.mNoRecycleSpace = endPadding;
                } else {
                    mLayoutState.mExtraFillSpace = endPadding;
                }
            }
        } else {
            scrollingOffset = -offset + startPadding;
            if (clipToPadding) { //mNoRecycleSpace如果不带上padding，如果设置了padding参数，将会出现不及时回收的情况
                mLayoutState.mNoRecycleSpace = -endPadding;
            } else {
                if (nextPage != curPage) {
                    mLayoutState.mNoRecycleSpace = startPadding;
                } else {
                    mLayoutState.mExtraFillSpace = startPadding;
                }
            }
        }

        if (nextPage != curPage) {
            if (!clipToPadding) {
                offset += padding * layoutDirection;
            }
            if (itemToTail) {
                //需要处理某一页不足rows行或不足columns列的情况，避免数据位置不正确问题
                int remainingRange;
                if (isVertical) {
                    remainingRange = vBorders[rowCount] - vBorders[curRow + curRowSpanSize];
                } else {
                    remainingRange = hBorders[columnCount] - hBorders[curColumn + curColumnSpanSize];
                }
                //如果数据不足以填充columns列，则显示空的
                scrollingOffset += remainingRange;
                offset += remainingRange * layoutDirection;
            }
        }

        mLayoutState.mOffset = offset;
        mLayoutState.mAvailable = requiredSpace;
        if (canUseExistingSpace) {
            mLayoutState.mAvailable -= scrollingOffset;
        }
        mLayoutState.mScrollingOffset = scrollingOffset;
        // rows = 3  columns = 4
        //0 1 2  3     12 13 14 15   24 25 26 27
        //4 5 6  7     16 17 18 19   28 29
        //8 9 10 11    20 21 22 23
//        int nextPosition;
//        if (layoutToEnd) {
//            if (isLastColumn(curColumn) || isPageTail(position)) { //某页的最后一列
//                //取下一页第一行第一个position
//                nextPosition = pageBorders[curPage];
////                nextPosition = position + (rows - row - 1) * columns + 1;
//            } else {  //取本页下一列第一个position
//                nextPosition = position - curRow * columns + 1;
//            }
//        } else {
//            if (isFirstColumn(curColumn)) { //某页的第一列
//                //取上一页最后一行最后一个position
//                nextPosition = position - 1;
//            } else {  //取本页上一列最后一个position
//                nextPosition = position + (rows - curRow - 1) * columns - 1;
//            }
//        }
//        mLayoutState.mCurrentPosition = nextPosition;
    }

    public int findPositionByPage(int page) {
        if (page < 0 || page >= pageBorders.length - 1) {
            return RecyclerView.NO_POSITION;
        }
        return pageBorders[page];
    }

    public int findPageByPosition(int position) {
        int size = pageBorders.length;
        for (int i = size - 1; i >= 0; i--) {
            if (position >= pageBorders[i])
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public boolean canScrollHorizontally() {
        return mOrientation == RecyclerView.HORIZONTAL;
    }

    @Override
    public boolean canScrollVertically() {
        return mOrientation == RecyclerView.VERTICAL;
    }

    @Override
    public final RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public RecyclerView.LayoutParams generateLayoutParams(Context c, AttributeSet attrs) {
        return new LayoutParams(c, attrs);
    }

    @Override
    public RecyclerView.LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new LayoutParams((ViewGroup.MarginLayoutParams) lp);
        } else {
            return new LayoutParams(lp);
        }
    }

    @Override
    public boolean checkLayoutParams(RecyclerView.LayoutParams lp) {
        return lp instanceof LayoutParams;
    }

    @Nullable
    @Override
    public PointF computeScrollVectorForPosition(int targetPosition) {
        return null;
    }

    @Override
    public void scrollToPosition(int position) {
        int pageIndex = findPageByPosition(position);
        setCurrentItem(pageIndex, false);
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, State state, int position) {
        int totalPage = getPageSize();
        int pageIndex = findPageByPosition(position);
        pageIndex = Math.max(pageIndex, 0);
        pageIndex = Math.min(pageIndex, totalPage - 1);
        if (pageIndex == mCurrentPage) {
            // Already scrolling to the correct page, but not yet there. Only handle instant scrolls
            // because then we need to interrupt the current smooth scroll.
            return;
        }
        setCurrentItem(pageIndex, true);
    }


    private void updateSpanInfo(int curPage, int curRow, int curColumn, int spanSize) {
        int position = mLayoutState.mCurrentPosition;
        int itemDirection = mLayoutState.mItemDirection;
        boolean itemToTail = itemDirection == LayoutState.ITEM_DIRECTION_TAIL;
        int nextPage = curPage;
        if (mOrientation == RecyclerView.VERTICAL) {
            if ((itemToTail && (isLastRow(curRow) || isPageTail(position)))
                || (!itemToTail && isFirstRow(curRow))) {
                nextPage += itemDirection;
            }
            mLayoutState.row = nextRow(curRow, itemDirection * spanSize);
            mLayoutState.column = 0;
        } else {
            if ((itemToTail && (isLastColumn(curColumn) || (isPageTail(position) && isFirstRow(curRow))))
                || (!itemToTail && isFirstColumn(curColumn))) {
                nextPage += itemDirection;
            }
            mLayoutState.row = 0;
            mLayoutState.column = nextColumn(curColumn, itemDirection * spanSize);
        }
        if (nextPage != curPage) {
            mLayoutState.row = itemToTail ? 0 : rowCount - 1;
            mLayoutState.column = itemToTail ? 0 : columnCount - 1;
        }
        mLayoutState.page = nextPage;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        if (this.rowCount == rowCount) return;
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount count should be at least 1. Provided "
                + rowCount);
        }
        this.rowCount = rowCount;
        updatePageBorders();
        requestLayout();
    }

    public int getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(int columnCount) {
        if (this.columnCount == columnCount) return;
        if (columnCount < 1) {
            throw new IllegalArgumentException("columnCount count should be at least 1. Provided "
                + columnCount);
        }
        this.columnCount = columnCount;
        updatePageBorders();
        requestLayout();
    }

    public void scrollFirstPage(boolean smoothScroll) {
        setCurrentItem(0, smoothScroll);
    }

    public void scrollNextPage(boolean smoothScroll) {
        setCurrentItem(mCurrentPage + 1, smoothScroll);
    }

    public void scrollPreviousPage(boolean smoothScroll) {
        setCurrentItem(mCurrentPage - 1, smoothScroll);
    }

    public void scrollLastPage(boolean smoothScroll) {
        setCurrentItem(pageBorders.length - 2, smoothScroll);
    }

    public int getQueueSize() {
        return queueBorders == null ? 0 : queueBorders.length - 1;
    }

    public int getPageSize() {
        return pageBorders == null ? 0 : pageBorders.length - 1;
    }

    public void setCurrentQueueItem(int queueIndex, boolean smoothScroll) {
        if (getQueueIndex(mCurrentPage) == queueIndex) return;
        setCurrentItem(queueBorders[queueIndex], smoothScroll);
    }

    public void setCurrentItem(int pageIndex, boolean smoothScroll) {
        if (pageIndex < 0 || pageIndex > pageBorders.length - 2) {
            return;
        }
        if (pageIndex == mCurrentPage && mScrollEventAdapter.isIdle()) {
            return;
        }

        double previousItem = mCurrentPage;
        mCurrentPage = pageIndex;
        if (!mScrollEventAdapter.isIdle()) {
            // Scroll in progress, overwrite previousItem with actual current position
            previousItem = mScrollEventAdapter.getRelativeScrollPosition();
        }

        mScrollEventAdapter.notifyProgrammaticScroll(pageIndex, smoothScroll);
        if (!smoothScroll || mRecyclerView == null) {
            scrollToPage(pageIndex);
            return;
        }
        // For smooth scroll, pre-jump to nearby item for long jumps.
        if (Math.abs(pageIndex - previousItem) > 3) {
            scrollToPage(pageIndex > previousItem ? pageIndex - 3 : pageIndex + 3);
            mRecyclerView.post(() -> smoothScrollToPage(pageIndex));
        } else {
            smoothScrollToPage(pageIndex);
        }
    }

    public void scrollToPage(int targetPage) {
        if (mPendingSavedState != null) {
            mPendingSavedState.invalidateAnchor();
        }
        mPendingScrollPosition = findPositionByPage(targetPage);
        requestLayout();
    }

    public void smoothScrollToPage(int targetPage) {
        int position = findPositionByPage(targetPage);
        if (position == RecyclerView.NO_POSITION) return;
        GridPageLinearSmoothScroller smoothScroller = new GridPageLinearSmoothScroller(mRecyclerView.getContext(), this);
        smoothScroller.setTargetPosition(position);
        startSmoothScroll(smoothScroller);
    }

    public int[] distanceToPosition(int targetPosition) {
        int destPageIndex = findPageByPosition(targetPosition);
        return distanceToPage(destPageIndex);
    }

    public int[] distanceToPage(int targetPage) {
        int curPage = 0;
        int offsetPx = 0;
        if (mScrollEventAdapter != null) {
            ScrollEventValues scrollValues = mScrollEventAdapter.getScrollValues();
            curPage = scrollValues.mPosition;
            offsetPx = scrollValues.mOffsetPx;
        }
        resolveShouldLayoutReverse();
        boolean shouldReverseLayout = shouldReverseLayout();
        int itemDirection = shouldReverseLayout ? LayoutState.ITEM_DIRECTION_HEAD : LayoutState.ITEM_DIRECTION_TAIL;
        int totalSpace = getTotalSpace();
        int diff = targetPage - curPage;
        double distance = (diff * totalSpace - offsetPx) * itemDirection;
        int[] out = new int[2];
        if (canScrollHorizontally()) {
            out[0] = (int) distance;
        } else if (canScrollVertically()) {
            out[1] = (int) distance;
        }
        return out;
    }

    public void registerOnQueueChangeListener(OnPageChangeListener onQueueChangeListener) {
        if (mQueueChangeEventListener == null) {
            mQueueChangeEventListener = new CompositeOnPageChangeCallback(2);
        }
        this.mQueueChangeEventListener.addOnPageChangeCallback(onQueueChangeListener);
    }

    public void unregisterOnQueueChangeListener(OnPageChangeListener onQueueChangeListener) {
        if (mQueueChangeEventListener == null) return;
        this.mQueueChangeEventListener.removeOnPageChangeCallback(onQueueChangeListener);
    }

    public void registerOnPageChangeCallback(@NonNull OnPageChangeListener callback) {
        mPageChangeEventDispatcher.addOnPageChangeCallback(callback);
    }

    public void unregisterOnPageChangeCallback(@NonNull OnPageChangeListener callback) {
        mPageChangeEventDispatcher.removeOnPageChangeCallback(callback);
    }

    @ScrollState
    public int getScrollState() {
        if (mScrollEventAdapter == null) return ViewPager2.SCROLL_STATE_IDLE;
        return mScrollEventAdapter.getScrollState();
    }

    public int getOrientation() {
        return mOrientation;
    }

    public void setOrientation(int orientation) {
        if (orientation != RecyclerView.HORIZONTAL && orientation != RecyclerView.VERTICAL) {
            throw new IllegalArgumentException("invalid orientation:" + orientation);
        }
        assertNotInLayoutOrScroll(null);
        if (orientation != mOrientation || mOrientationHelper == null) {
            mOrientationHelper =
                OrientationHelper.createOrientationHelper(this, orientation);
            mAnchorInfo.mOrientationHelper = mOrientationHelper;
            mOrientation = orientation;
            requestLayout();
        }
    }

    //从尾部开始查找锚点(页头view)
    public View findSnapViewFromEnd() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View view = getChildAt(i);
            if (view == null) continue;
            LayoutParams layoutParams = (LayoutParams) view.getLayoutParams();
            if (layoutParams.isPageHead()) {
                return view;
            }
        }
        return null;
    }

    public int findFirstVisibleItemPosition() {
        View child = getChildAt(0);
        return child == null ? RecyclerView.NO_POSITION : getPosition(child);
    }

    public int getTotalSpace() {
        int totalSpace = mOrientationHelper.getEnd();
        if (getClipToPadding()) {
            totalSpace -= mOrientationHelper.getStartAfterPadding() + mOrientationHelper.getEndPadding();
        }
        return totalSpace;
    }

    public int getPagerStartOffset(View pagerHeadView) {
        /*
         startPadding = 100, endPadding = 200   width = 1000

         reverseLayout = false
         clipToPadding = true   decoratedWidth = 700  leftRange = [100, 800]
         clipToPadding = false  decoratedWidth = 1000 leftRange = [100, 1100]

         reverseLayout = true
         clipToPadding = true   decoratedWidth = 700  rightRange = [100, 800]
         clipToPadding = false  decoratedWidth = 1000 rightRange = [-200, 800]

         竖向滑动同理，最终目的，使得range范围在[0, decoratedWidth]内
         */
        int offset;
        if (mShouldReverseLayout) {
            if (getClipToPadding()) {
                offset = mOrientationHelper.getDecoratedEnd(pagerHeadView) - mOrientationHelper.getStartAfterPadding();
            } else {
                offset = mOrientationHelper.getDecoratedEnd(pagerHeadView) + mOrientationHelper.getEndPadding();
            }
        } else {
            offset = mOrientationHelper.getDecoratedStart(pagerHeadView) - mOrientationHelper.getStartAfterPadding();
        }
        return offset;
    }

    private int[] calculateItemBorders(int[] cachedBorders, int spanCount, int totalSpace) {
        if (cachedBorders == null || cachedBorders.length != spanCount + 1
            || cachedBorders[cachedBorders.length - 1] != totalSpace) {
            cachedBorders = new int[spanCount + 1];
        }
        cachedBorders[0] = 0;
        int sizePerSpan = totalSpace / spanCount;
        int sizePerSpanRemainder = totalSpace % spanCount;
        int consumedPixels = 0;
        int additionalSize = 0;
        for (int i = 1; i <= spanCount; i++) {
            int itemSize = sizePerSpan;
            additionalSize += sizePerSpanRemainder;
            if (additionalSize > 0 && (spanCount - additionalSize) < sizePerSpanRemainder) {
                itemSize += 1;
                additionalSize -= spanCount;
            }
            consumedPixels += itemSize;
            cachedBorders[i] = consumedPixels;
        }
        return cachedBorders;
    }

    static class LayoutState {

        static final int LAYOUT_START = -1;

        static final int LAYOUT_END = 1;

        static final int INVALID_LAYOUT = Integer.MIN_VALUE;

        static final int ITEM_DIRECTION_HEAD = -1;

        static final int ITEM_DIRECTION_TAIL = 1;

        static final int SCROLLING_OFFSET_NaN = Integer.MIN_VALUE;

        int page;
        int row;
        int column;

        /**
         * We may not want to recycle children in some cases (e.g. layout)
         */
        boolean mRecycle = true;
        //下一个item布局的偏移量
        int mOffset;
        //减去scrollingOffset值后，需要滚动的像素数
        int mAvailable;
        /**
         * Current position on the adapter to get the next item.
         */
        int mCurrentPosition;
        //item方向
        int mItemDirection;
        //布局方向
        int mLayoutDirection;
        //在不创建新视图的情况下进行的滚动量
        int mScrollingOffset;
        int mExtraFillSpace = 0;
        int mNoRecycleSpace = 0;
        /**
         * Equal to {@link androidx.recyclerview.widget.RecyclerView.State#isPreLayout()}. When consuming scrap, if this value
         * is set to true, we skip removed views since they should not be laid out in post layout
         * step.
         */
        boolean mIsPreLayout = false;
        /**
         * The most recent {@link #scrollBy(int, androidx.recyclerview.widget.RecyclerView.Recycler, androidx.recyclerview.widget.RecyclerView.State)}
         * amount.
         */
        int mLastScrollDelta;

        boolean hasMore(State state) {
            return mCurrentPosition >= 0 && mCurrentPosition < state.getItemCount();
        }

        View next(Recycler recycler) {
            final View view = recycler.getViewForPosition(mCurrentPosition);
//            mCurrentPosition = calculateNextPosition();
            return view;
        }

        public int getPage() {
            return page;
        }

        int getRow() {
            return row;
        }

        int getColumn() {
            return column;
        }

//        int calculateNextPosition() {
//            int position = mCurrentPosition;
//            boolean layoutEnd = mLayoutDirection == LAYOUT_END;
//            int row = getRow();
//            int column = getColumn();
//            if (layoutEnd) {
//                if (row == rows - 1) {
//                    if (column == columns - 1) {
//                        return position + 1;
//                    } else {
//                        return position - row * columns + 1;
//                    }
//                }
//            } else {
//                if (row == 0) {
//                    if (column == 0) {
//                        return position - 1;
//                    } else {
//                        return position + (rows - 1) * columns - 1;
//                    }
//                }
//            }
//            return position + mItemDirection * columns;
//        }

    }

    static class AnchorInfo {
        OrientationHelper mOrientationHelper;
        int mPosition;
        int mCoordinate;
        boolean mLayoutFromEnd;
        boolean mValid;

        AnchorInfo() {
            reset();
        }

        void reset() {
            mPosition = RecyclerView.NO_POSITION;
            mCoordinate = Integer.MIN_VALUE;
            mLayoutFromEnd = false;
            mValid = false;
        }

        /**
         * assigns anchor coordinate from the RecyclerView's padding depending on current
         * layoutFromEnd value
         */
        void assignCoordinateFromPadding() {
            mCoordinate = mLayoutFromEnd
                ? mOrientationHelper.getEndAfterPadding()
                : mOrientationHelper.getStartAfterPadding();
        }

        @Override
        public String toString() {
            return "AnchorInfo{"
                + "mPosition=" + mPosition
                + ", mCoordinate=" + mCoordinate
                + ", mLayoutFromEnd=" + mLayoutFromEnd
                + ", mValid=" + mValid
                + '}';
        }

        boolean isViewValidAsAnchor(View child, State state) {
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
            return !lp.isItemRemoved() && lp.getViewLayoutPosition() >= 0
                && lp.getViewLayoutPosition() < state.getItemCount();
        }

        public void assignFromViewAndKeepVisibleRect(View child, int position) {
            final int spaceChange = mOrientationHelper.getTotalSpaceChange();
            if (spaceChange >= 0) {
                assignFromView(child, position);
                return;
            }
            mPosition = position;
            if (mLayoutFromEnd) {
                final int prevLayoutEnd = mOrientationHelper.getEndAfterPadding() - spaceChange;
                final int childEnd = mOrientationHelper.getDecoratedEnd(child);
                final int previousEndMargin = prevLayoutEnd - childEnd;
                mCoordinate = mOrientationHelper.getEndAfterPadding() - previousEndMargin;
                // ensure we did not push child's top out of bounds because of this
                if (previousEndMargin > 0) { // we have room to shift bottom if necessary
                    final int childSize = mOrientationHelper.getDecoratedMeasurement(child);
                    final int estimatedChildStart = mCoordinate - childSize;
                    final int layoutStart = mOrientationHelper.getStartAfterPadding();
                    final int previousStartMargin = mOrientationHelper.getDecoratedStart(child)
                        - layoutStart;
                    final int startReference = layoutStart + Math.min(previousStartMargin, 0);
                    final int startMargin = estimatedChildStart - startReference;
                    if (startMargin < 0) {
                        // offset to make top visible but not too much
                        mCoordinate += Math.min(previousEndMargin, -startMargin);
                    }
                }
            } else {
                final int childStart = mOrientationHelper.getDecoratedStart(child);
                final int startMargin = childStart - mOrientationHelper.getStartAfterPadding();
                mCoordinate = childStart;
                if (startMargin > 0) { // we have room to fix end as well
                    final int estimatedEnd = childStart
                        + mOrientationHelper.getDecoratedMeasurement(child);
                    final int previousLayoutEnd = mOrientationHelper.getEndAfterPadding()
                        - spaceChange;
                    final int previousEndMargin = previousLayoutEnd
                        - mOrientationHelper.getDecoratedEnd(child);
                    final int endReference = mOrientationHelper.getEndAfterPadding()
                        - Math.min(0, previousEndMargin);
                    final int endMargin = endReference - estimatedEnd;
                    if (endMargin < 0) {
                        mCoordinate -= Math.min(startMargin, -endMargin);
                    }
                }
            }
        }

        public void assignFromView(View child, int position) {
            if (mLayoutFromEnd) {
                mCoordinate = mOrientationHelper.getDecoratedEnd(child)
                    + mOrientationHelper.getTotalSpaceChange();
            } else {
                mCoordinate = mOrientationHelper.getDecoratedStart(child);
            }

            mPosition = position;
        }
    }

    protected static class LayoutChunkResult {
        public int mConsumed;
        public int consumedSpanSize;
        public boolean mFinished;
        public boolean mIgnoreConsumed;
        public boolean mFocusable;

        void resetInternal() {
            mConsumed = 0;
            consumedSpanSize = 0;
            mFinished = false;
            mIgnoreConsumed = false;
            mFocusable = false;
        }
    }

    public static class LayoutParams extends RecyclerView.LayoutParams {

        /**
         * Span Id for Views that are not laid out yet.
         */
        public static final int INVALID_SPAN_ID = -1;

        int columnIndex = INVALID_SPAN_ID;

        int rowIndex = INVALID_SPAN_ID;

        int pageIndex = INVALID_SPAN_ID;

        int columnSpanSize = 1;
        int rowSpanSize = 1;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(RecyclerView.LayoutParams source) {
            super(source);
        }

        public int getColumnIndex() {
            return columnIndex;
        }

        public int getColumnSpanSize() {
            return columnSpanSize;
        }

        public int getRowIndex() {
            return rowIndex;
        }

        public int getRowSpanSize() {
            return rowSpanSize;
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public boolean isPageHead() {
            return columnIndex == 0 && rowIndex == 0;
        }
    }

    public static class SavedState implements Parcelable {

        public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        int mAnchorPosition;

        public SavedState() {

        }

        SavedState(Parcel in) {
            mAnchorPosition = in.readInt();
        }

        @SuppressLint("UnknownNullness") // b/240775049: Cannot annotate properly
        public SavedState(SavedState other) {
            mAnchorPosition = other.mAnchorPosition;
        }

        boolean hasValidAnchor() {
            return mAnchorPosition >= 0;
        }

        void invalidateAnchor() {
            mAnchorPosition = RecyclerView.NO_POSITION;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mAnchorPosition);
        }
    }

    public static class OnPageChangeListener extends OnPageChangeCallback {


        public void onPageCountChange(int pageCount) {
        }
    }

    private class DataSetChangeObserver extends RecyclerView.AdapterDataObserver {
        @Override
        public void onChanged() {
            int itemCount = mAdapter.getItemCount();
            if (itemCount > 0) {
                setQueueTails(new int[]{itemCount - 1}, null);
            }
        }

        @Override
        public final void onItemRangeInserted(int positionStart, int itemCount) {
            if (queueTailIndexes == null) {
                queueTailIndexes = new int[]{-1};
            }
            int length = queueTailIndexes.length;
            boolean changed = false;
            for (int i = 0; i < length; i++) {
                int position = queueTailIndexes[i];
                if (positionStart <= position || position == -1) {
                    queueTailIndexes[i] += itemCount;
                    changed = true;
                }
            }
            if (changed) {
                updatePageBorders();
            }
        }

        @Override
        public final void onItemRangeRemoved(int positionStart, int itemCount) {
            if (queueTailIndexes == null) return;
            int positionEnd = positionStart + itemCount;
            int length = queueTailIndexes.length;
            boolean changed = false;
            for (int i = 0; i < length; i++) {
                int position = queueTailIndexes[i];
                if (positionStart <= position) {
                    if (positionEnd <= position) {
                        queueTailIndexes[i] -= itemCount;
                    } else {
                        queueTailIndexes[i] -= positionEnd - position + 1;
                    }
                    changed = true;
                }
            }
            if (changed) {
                updatePageBorders();
            }
        }
    }
}
