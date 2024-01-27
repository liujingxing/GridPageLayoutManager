package androidx.recyclerview.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.RecyclerView.Recycler;
import androidx.recyclerview.widget.RecyclerView.SmoothScroller.ScrollVectorProvider;
import androidx.recyclerview.widget.RecyclerView.State;

import com.google.android.material.tabs.IndicatorObserver;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutObserver;
import com.google.android.material.tabs.TabLayoutObserver.TabConfigurationStrategy;
import com.ljx.layoutmanager.CompositeOnPageChangeCallback;
import com.ljx.layoutmanager.GridPageLinearSmoothScroller;
import com.ljx.layoutmanager.GridPageSnapHelper;
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
    private final LayoutChunkResult layoutChunkResult = new LayoutChunkResult();
    private LayoutState layoutState;
    private OrientationHelper orientationHelper;
    private int pendingScrollPosition = RecyclerView.NO_POSITION;
    private SavedState pendingSavedState = null;
    private int[] hBorders;
    private int[] vBorders;
    /**
     * 记录每页position边界,如[0, 8, 9, 17, 20], 则代表有4页
     * 第一页position范围[0,8), 不包含右边界，以下同理
     * 第二页position范围[8,9)
     * 第三页position范围[9,17)
     * 第四页position范围[17,20)
     */
    private int[] pageBorders;
    /**
     * 记录队列的页码边界， 如[0, 3, 6, 7]，则代表有3个队列
     * 第一个队列页码范围[0,3), 不包含右边界，以下同理
     * 第二个队列页码范围[3,6)页
     * 第三个队列页码范围[6,7)页
     */
    private int[] queueBorders;
    /**
     * 记录每个队列尾部item在整体队列中的下标
     * 如[16, 33, 34], 代表有3个队列，每个队列的尾部item position分别为16，33，34
     */
    private int[] queueTailIndexes;
    private View[] viewSet;
    private GridPageSnapHelper pageSnapHelper;
    private ScrollEventAdapter scrollEventAdapter;
    private CompositeOnPageChangeCallback pageChangeEventDispatcher;
    private CompositeOnPageChangeCallback queueChangeEventListener;
    private int rowCount;
    private int columnCount;
    private int orientation;
    private int currentPage = 0;
    private boolean reverseLayout;
    private boolean shouldReverseLayout = false;
    private Adapter<?> adapter;
    private TabLayoutObserver tabLayoutMediator;
    private IndicatorObserver indicatorObserver;
    private DataSetChangeObserver dataSetChangeObserver;

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
            if (adapter != null && dataSetChangeObserver != null) {
                adapter.unregisterAdapterDataObserver(dataSetChangeObserver);
            }
            queueTailIndexes = null;
            adapter = null;
            return;
        }
        setAdapter(recyclerView.getAdapter());
    }

    @Override
    public void onAdapterChanged(@Nullable Adapter oldAdapter, @Nullable Adapter newAdapter) {
        super.onAdapterChanged(oldAdapter, newAdapter);
        setAdapter(newAdapter);
    }

    public Adapter<?> getAdapter() {
        return adapter;
    }

    private void setAdapter(Adapter<?> newAdapter) {
        Adapter<?> oldAdapter = adapter;
        if (oldAdapter != null && dataSetChangeObserver != null) {
            oldAdapter.unregisterAdapterDataObserver(dataSetChangeObserver);
        }
        adapter = newAdapter;
        if (tabLayoutMediator != null) {
            tabLayoutMediator.onAdapterChanged(oldAdapter, newAdapter);
        }
        if (adapter != null) {
            if (dataSetChangeObserver == null) {
                dataSetChangeObserver = new DataSetChangeObserver();
            }
            adapter.registerAdapterDataObserver(dataSetChangeObserver);
            if (oldAdapter != null || queueTailIndexes == null) {
                setQueueTails(new int[]{adapter.getItemCount() - 1}, null);
            }
        }
    }

    private void initialize() {
        pageChangeEventDispatcher = new CompositeOnPageChangeCallback(3);
        final OnPageChangeListener currentItemUpdater = new OnPageChangeListener() {

            @Override
            public void onPageScrolled(int page, float pageOffset, int pageOffsetPixels) {
                if (queueBorders != null && queueChangeEventListener != null) {
                    int queueIndex = findQueueByPage(page);
                    if (queueIndex == -1) return;
                    int nextQueueIndex = queueIndex + 1;
                    if (page + 1 == findPageByQueue(nextQueueIndex) && pageOffset > 0) {
                        queueChangeEventListener.onPageScrolled(queueIndex, pageOffset, pageOffsetPixels);
                    }
                }
            }

            @Override
            public void onPageSelected(int page) {
                if (currentPage != page) {
                    currentPage = page;
                }
                if (queueBorders != null && queueChangeEventListener != null) {
                    int queueIndex = findQueueByPage(page);
                    if (queueIndex == -1) return;
                    queueChangeEventListener.onPageSelected(queueIndex);
                }
            }

            @Override
            public void onPageScrollStateChanged(int newState) {
                if (queueBorders != null && queueChangeEventListener != null) {
                    queueChangeEventListener.onPageScrollStateChanged(newState);
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrentItem();
                }
            }
        };
        pageChangeEventDispatcher.addOnPageChangeCallback(currentItemUpdater);
        scrollEventAdapter = new ScrollEventAdapter(this);
        scrollEventAdapter.setOnPageChangeListener(pageChangeEventDispatcher);
    }

    private void updateCurrentItem() {
        View snapView = findSnapViewFromEnd();
        if (snapView == null) {
            return; // nothing we can do
        }
        LayoutParams layoutParams = (LayoutParams) snapView.getLayoutParams();
        int pageIndex = layoutParams.pageIndex;
        if (pageIndex != currentPage && getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
            pageChangeEventDispatcher.onPageSelected(pageIndex);
        }
    }

    private int getHorizontalSpanRange(int spanIndex, int spanSize) {
        if (orientation == RecyclerView.VERTICAL && isLayoutRTL()) {
            int startIndex = columnCount - spanIndex;
            return hBorders[startIndex] - hBorders[startIndex - spanSize];
        }
        return hBorders[spanIndex + spanSize] - hBorders[spanIndex];
    }

    private void resolveShouldLayoutReverse() {
        // A == B is the same result, but we rather keep it readable
        if (orientation == RecyclerView.VERTICAL || !isLayoutRTL()) {
            shouldReverseLayout = reverseLayout;
        } else {
            shouldReverseLayout = !reverseLayout;
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
        return reverseLayout;
    }

    public void setReverseLayout(boolean reverseLayout) {
        if (reverseLayout == this.reverseLayout) {
            return;
        }
        this.reverseLayout = reverseLayout;
        requestLayout();
    }

    public boolean shouldReverseLayout() {
        return shouldReverseLayout;
    }

    public boolean isLayoutRTL() {
        return getLayoutDirection() == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    void ensureLayoutState() {
        if (layoutState == null) {
            layoutState = new LayoutState();
        }
    }

    private void ensureViewSet(int spanCount) {
        if (viewSet == null || viewSet.length != spanCount) {
            viewSet = new View[spanCount];
        }
    }

    @Override
    public void onLayoutChildren(Recycler recycler, State state) {
        if (getItemCount() <= 0 || state.isPreLayout()) {
            return;
        }
        if (pendingSavedState != null || pendingScrollPosition != RecyclerView.NO_POSITION) {
            if (state.getItemCount() == 0) {
                removeAndRecycleAllViews(recycler);
                return;
            }
        }
        if (pendingSavedState != null && pendingSavedState.hasValidAnchor()) {
            pendingScrollPosition = pendingSavedState.mAnchorPosition;
        }

        ensureLayoutState();
        layoutState.mRecycle = false;
        resolveShouldLayoutReverse();

        int widthSpace = getWidth() - getPaddingLeft() - getPaddingRight();
        int heightSpace = getHeight() - getPaddingTop() - getPaddingBottom();
        hBorders = calculateItemBorders(hBorders, columnCount, widthSpace);
        vBorders = calculateItemBorders(vBorders, rowCount, heightSpace);

        if (!mAnchorInfo.mValid || pendingScrollPosition != RecyclerView.NO_POSITION
            || pendingSavedState != null) {
            mAnchorInfo.reset();
            mAnchorInfo.mLayoutFromEnd = shouldReverseLayout;
            // calculate anchor position and coordinate
            updateAnchorInfoForLayout(state, mAnchorInfo);
            mAnchorInfo.mValid = true;
        }
        int spanCount = orientation == RecyclerView.VERTICAL ? columnCount : rowCount;
        ensureViewSet(spanCount);
        detachAndScrapAttachedViews(recycler);
        if (shouldReverseLayout) {
            updateLayoutStateToFillStart(mAnchorInfo);
        } else {
            updateLayoutStateToFillEnd(mAnchorInfo);
        }
        fill(recycler, layoutState, state, false);
    }

    @Override
    public void onLayoutCompleted(State state) {
        super.onLayoutCompleted(state);
        pendingSavedState = null; // we don't need this anymore
        pendingScrollPosition = RecyclerView.NO_POSITION;
        mAnchorInfo.reset();
    }

    private int getVerticalSpanRange(int spanIndex, int spanSize) {
        return vBorders[spanIndex + spanSize] - vBorders[spanIndex];
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        view.addOnScrollListener(scrollEventAdapter);
        if (pageSnapHelper == null) {
            pageSnapHelper = new GridPageSnapHelper();
        }
        pageSnapHelper.attachToRecyclerView(view);
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        if (pageSnapHelper != null) {
            pageSnapHelper.attachToRecyclerView(null);
        }
        view.removeOnScrollListener(scrollEventAdapter);
    }

    @Nullable
    @Override
    public Parcelable onSaveInstanceState() {
        if (pendingSavedState != null) {
            return new SavedState(pendingSavedState);
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
            pendingSavedState = (SavedState) state;
            if (pendingScrollPosition != RecyclerView.NO_POSITION) {
                pendingSavedState.invalidateAnchor();
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
            int pageIndex = Math.min(getPageSize() - 1, layoutParams.pageIndex);
            anchorInfo.mPosition = findPositionByPage(pageIndex);
        } else {
            anchorInfo.mPosition = 0;
        }
        anchorInfo.assignCoordinateFromPadding();
    }

    private boolean updateAnchorFromPendingData(State state, AnchorInfo anchorInfo) {
        if (state.isPreLayout() || pendingScrollPosition == RecyclerView.NO_POSITION) {
            return false;
        }
        // validate scroll position
        if (pendingScrollPosition < 0 || pendingScrollPosition >= state.getItemCount()) {
            pendingScrollPosition = RecyclerView.NO_POSITION;
            return false;
        }

        // if child is visible, try to make it a reference child and ensure it is fully visible.
        // if child is not visible, align it depending on its virtual position.
        anchorInfo.mPosition = pendingScrollPosition;
        // override layout from end values for consistency
        anchorInfo.mLayoutFromEnd = shouldReverseLayout;
        // if this changes, we should update prepareForDrop as well
        if (shouldReverseLayout) {
            anchorInfo.mCoordinate = orientationHelper.getEndAfterPadding();
        } else {
            anchorInfo.mCoordinate = orientationHelper.getStartAfterPadding();
        }
        return true;
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return pendingSavedState == null;
    }

    @Override
    public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
        if (orientation == RecyclerView.HORIZONTAL) {
            return 0;
        }
        ensureViewSet(columnCount);
        return scrollBy(dy, recycler, state);
    }

    @Override
    public int scrollHorizontallyBy(int dx, Recycler recycler, State state) {
        if (orientation == RecyclerView.VERTICAL) {
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
        layoutState.mRecycle = true;
        final int layoutDirection = delta > 0 ? LayoutState.LAYOUT_END : LayoutState.LAYOUT_START;
        final int absDelta = Math.abs(delta);
        updateLayoutState(layoutDirection, absDelta, true, state);
        int consumed = layoutState.mScrollingOffset + fill(recycler, layoutState, state, false);

        if (consumed < 0) {
            return 0;
        }
        int scrolled = absDelta > consumed ? layoutDirection * consumed : delta;
        if (scrolled != 0) {
            orientationHelper.offsetChildren(-scrolled);
        }
        layoutState.mLastScrollDelta = scrolled;
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
        LayoutChunkResult layoutChunkResult = this.layoutChunkResult;
        boolean isVertical = orientation == RecyclerView.VERTICAL;
        int curPage = layoutState.page;
        int pageSize = getPageSize();
        while (remainingSpace > 0 && curPage >= 0 && curPage < pageSize) {
            layoutChunkResult.resetInternal();

            int fromPosition = findPositionByPage(curPage);
            int toPosition = findPositionByPage(curPage + 1);
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
                        int padding = orientationHelper.getStartAfterPadding() + orientationHelper.getEndPadding();
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
        boolean isVertical = orientation == RecyclerView.VERTICAL;

        int page = layoutState.getPage();
        int row = layoutState.getRow();
        int column = layoutState.getColumn();
        int spanCount = viewSet.length;
        int spanIndex = layingOutInPrimaryDirection ? 0 : spanCount - 1;
        int fromPosition = findPositionByPage(page);
        int toPosition = findPositionByPage(page + 1);
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
                viewSet[count++] = view;
            }
            spanIndex += itemDirection * spanSize;
        }
        this.layoutChunkResult.consumedSpanSize = consumedSpanSize;

        if (count == 0) {
            return;
        }

        int widthSpace = getWidth() - getPaddingLeft() - getPaddingRight();
        int heightSpace = getHeight() - getPaddingTop() - getPaddingBottom();

        int offset = layoutState.mOffset;
        for (int i = 0; i < count; i++) {
            View view = viewSet[i];
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
                    if (shouldReverseLayout) {
                        bottom = offset;
                        top = bottom - itemHeight;
                    } else {
                        top = offset - rowHeight;
                        bottom = top + itemHeight;
                    }
                } else {
                    if (shouldReverseLayout) {
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
                    if (shouldReverseLayout) {
                        right = offset;
                        left = right - itemWidth;
                    } else {
                        left = offset - columnWidth;
                        right = left + itemWidth;
                    }
                } else {
                    if (shouldReverseLayout) {
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
        Arrays.fill(viewSet, null);
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
        if (shouldReverseLayout) {
            for (int i = childCount - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (orientationHelper.getDecoratedEnd(child) > limit
                    || orientationHelper.getTransformedEndWithDecoration(child) > limit) {
                    // stop here
                    recycleChildren(recycler, childCount - 1, i);
                    return;
                }
            }
        } else {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (orientationHelper.getDecoratedEnd(child) > limit
                    || orientationHelper.getTransformedEndWithDecoration(child) > limit) {
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
        final int limit = orientationHelper.getEnd() - scrollingOffset + noRecycleSpace;
        if (shouldReverseLayout) {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (orientationHelper.getDecoratedStart(child) < limit
                    || orientationHelper.getTransformedStartWithDecoration(child) < limit) {
                    // stop here
                    recycleChildren(recycler, 0, i);
                    return;
                }
            }
        } else {
            for (int i = childCount - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (orientationHelper.getDecoratedStart(child) < limit
                    || orientationHelper.getTransformedStartWithDecoration(child) < limit) {
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
        layoutState.mAvailable = orientationHelper.getEndAfterPadding() - offset;
        layoutState.mItemDirection = shouldReverseLayout ? LayoutState.ITEM_DIRECTION_HEAD
            : LayoutState.ITEM_DIRECTION_TAIL;
        layoutState.mCurrentPosition = position;
        layoutState.mLayoutDirection = LayoutState.LAYOUT_END;
        layoutState.mNoRecycleSpace = 0;
        layoutState.mExtraFillSpace = 0;
        layoutState.mOffset = offset;
        layoutState.page = findPageByPosition(position);
        layoutState.column = 0;
        layoutState.row = 0;
        layoutState.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN;
    }

    private void updateLayoutStateToFillStart(int position, int offset) {
        layoutState.mAvailable = offset - orientationHelper.getStartAfterPadding();
        layoutState.mItemDirection = shouldReverseLayout ? LayoutState.ITEM_DIRECTION_TAIL :
            LayoutState.ITEM_DIRECTION_HEAD;
        layoutState.mCurrentPosition = position;
        layoutState.mLayoutDirection = LayoutState.LAYOUT_START;
        layoutState.mNoRecycleSpace = 0;
        layoutState.mExtraFillSpace = 0;
        layoutState.mOffset = offset;
        layoutState.page = findPageByPosition(position);
        layoutState.column = 0;
        layoutState.row = 0;
        layoutState.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN;
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
        pageChangeEventDispatcher.onPageSize(queueBorders[length]);
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
        boolean isVertical = orientation == RecyclerView.VERTICAL;
        layoutState.mNoRecycleSpace = 0;
        layoutState.mExtraFillSpace = 0;
        layoutState.mLayoutDirection = layoutDirection;
        boolean layoutToEnd = layoutDirection == LayoutState.LAYOUT_END;
        int itemDirection = layoutToEnd ^ shouldReverseLayout ? LayoutState.ITEM_DIRECTION_TAIL
            : LayoutState.ITEM_DIRECTION_HEAD;
        boolean itemToTail = itemDirection == LayoutState.ITEM_DIRECTION_TAIL;
        layoutState.mItemDirection = itemDirection;
        View anchorView = getChildAt(itemToTail ? getChildCount() - 1 : 0);
        if (anchorView == null) return;

        layoutState.mCurrentPosition = getPosition(anchorView);
        LayoutParams layoutParams = (LayoutParams) anchorView.getLayoutParams();
        int curPage = layoutParams.getPageIndex(); //第几页
        int curRow = layoutParams.getRowIndex(); //某页中第几行
        int curColumn = layoutParams.getColumnIndex(); //某页中第几列
        int curRowSpanSize = layoutParams.getRowSpanSize();
        int curColumnSpanSize = layoutParams.getColumnSpanSize();
        int spanSize = isVertical ? curRowSpanSize : curColumnSpanSize;
        updateSpanInfo(curPage, curRow, curColumn, spanSize);
        int nextPage = layoutState.getPage();

        int offset = shouldReverseLayout ? orientationHelper.getDecoratedEnd(anchorView) :
            orientationHelper.getDecoratedStart(anchorView);
        if (itemToTail) {
            int rowHeight = getVerticalSpanRange(curRow, curRowSpanSize);
            int columnWidth = getHorizontalSpanRange(curColumn, curColumnSpanSize);
            offset += (isVertical ? rowHeight : columnWidth) * layoutDirection;
        }

        int scrollingOffset;
        int startPadding = orientationHelper.getStartAfterPadding();
        int endPadding = orientationHelper.getEndPadding();
        int padding = startPadding + endPadding;
        boolean clipToPadding = getClipToPadding();
        if (layoutToEnd) {
            scrollingOffset = offset - orientationHelper.getEndAfterPadding();
            if (clipToPadding) {//mNoRecycleSpace如果不带上padding，如果设置了padding参数，将会出现不及时回收的情况
                layoutState.mNoRecycleSpace = -startPadding;
            } else {
                if (nextPage != curPage) {
                    layoutState.mNoRecycleSpace = endPadding;
                } else {
                    layoutState.mExtraFillSpace = endPadding;
                }
            }
        } else {
            scrollingOffset = -offset + startPadding;
            if (clipToPadding) { //mNoRecycleSpace如果不带上padding，如果设置了padding参数，将会出现不及时回收的情况
                layoutState.mNoRecycleSpace = -endPadding;
            } else {
                if (nextPage != curPage) {
                    layoutState.mNoRecycleSpace = startPadding;
                } else {
                    layoutState.mExtraFillSpace = startPadding;
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

        layoutState.mOffset = offset;
        layoutState.mAvailable = requiredSpace;
        if (canUseExistingSpace) {
            layoutState.mAvailable -= scrollingOffset;
        }
        layoutState.mScrollingOffset = scrollingOffset;
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
        if (page < 0 || page > pageBorders.length - 1) {
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

    public int findPageByQueue(int queue) {
        if (queueBorders == null) return -1;
        if (queue < 0 || queue > queueBorders.length - 1) {
            return -1;
        }
        return queueBorders[queue];
    }

    public int findQueueByPage(int page) {
        if (queueBorders == null) return -1;
        for (int i = queueBorders.length - 1; i >= 0; i--) {
            if (page >= queueBorders[i]) {
                return i;
            }
        }
        return -1;
    }

    public int getCurrentQueueItem() {
        return findQueueByPage(currentPage);
    }

    public int getQueueSize() {
        return queueBorders == null ? 0 : queueBorders.length - 1;
    }

    public int getPageSize() {
        return pageBorders == null ? 0 : pageBorders.length - 1;
    }

    @Override
    public boolean canScrollHorizontally() {
        return orientation == RecyclerView.HORIZONTAL;
    }

    @Override
    public boolean canScrollVertically() {
        return orientation == RecyclerView.VERTICAL;
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
        if (pageIndex == currentPage) {
            // Already scrolling to the correct page, but not yet there. Only handle instant scrolls
            // because then we need to interrupt the current smooth scroll.
            return;
        }
        setCurrentItem(pageIndex, true);
    }


    private void updateSpanInfo(int curPage, int curRow, int curColumn, int spanSize) {
        int position = layoutState.mCurrentPosition;
        int itemDirection = layoutState.mItemDirection;
        boolean itemToTail = itemDirection == LayoutState.ITEM_DIRECTION_TAIL;
        int nextPage = curPage;
        if (orientation == RecyclerView.VERTICAL) {
            if ((itemToTail && (isLastRow(curRow) || isPageTail(position)))
                || (!itemToTail && isFirstRow(curRow))) {
                nextPage += itemDirection;
            }
            layoutState.row = nextRow(curRow, itemDirection * spanSize);
            layoutState.column = 0;
        } else {
            if ((itemToTail && (isLastColumn(curColumn) || (isPageTail(position) && isFirstRow(curRow))))
                || (!itemToTail && isFirstColumn(curColumn))) {
                nextPage += itemDirection;
            }
            layoutState.row = 0;
            layoutState.column = nextColumn(curColumn, itemDirection * spanSize);
        }
        if (nextPage != curPage) {
            layoutState.row = itemToTail ? 0 : rowCount - 1;
            layoutState.column = itemToTail ? 0 : columnCount - 1;
        }
        layoutState.page = nextPage;
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
        setCurrentItem(currentPage + 1, smoothScroll);
    }

    public void scrollPreviousPage(boolean smoothScroll) {
        setCurrentItem(currentPage - 1, smoothScroll);
    }

    public void scrollLastPage(boolean smoothScroll) {
        setCurrentItem(getPageSize() - 1, smoothScroll);
    }

    public void setCurrentQueueItem(int queueIndex, boolean smoothScroll) {
        if (findQueueByPage(currentPage) == queueIndex) return;
        setCurrentItem(findPageByQueue(queueIndex), smoothScroll);
    }

    public void setCurrentItem(int pageIndex, boolean smoothScroll) {
        if (pageIndex < 0 || pageIndex > getPageSize() - 1) {
            return;
        }
        if (pageIndex == currentPage && scrollEventAdapter.isIdle()) {
            return;
        }

        double previousItem = currentPage;
        currentPage = pageIndex;
        if (!scrollEventAdapter.isIdle()) {
            // Scroll in progress, overwrite previousItem with actual current position
            previousItem = scrollEventAdapter.getRelativeScrollPosition();
        }

        scrollEventAdapter.notifyProgrammaticScroll(pageIndex, smoothScroll);
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
        if (pendingSavedState != null) {
            pendingSavedState.invalidateAnchor();
        }
        pendingScrollPosition = findPositionByPage(targetPage);
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
        int targetPage = findPageByPosition(targetPosition);
        return distanceToPage(targetPage);
    }

    public int[] distanceToPage(int targetPage) {
        int curPage = 0;
        int offsetPx = 0;
        if (scrollEventAdapter != null) {
            ScrollEventValues scrollValues = scrollEventAdapter.getScrollValues();
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
        if (queueChangeEventListener == null) {
            queueChangeEventListener = new CompositeOnPageChangeCallback(2);
        }
        this.queueChangeEventListener.addOnPageChangeCallback(onQueueChangeListener);
    }

    public void unregisterOnQueueChangeListener(OnPageChangeListener onQueueChangeListener) {
        if (queueChangeEventListener == null) return;
        this.queueChangeEventListener.removeOnPageChangeCallback(onQueueChangeListener);
    }

    public void registerOnPageChangeCallback(@NonNull OnPageChangeListener callback) {
        pageChangeEventDispatcher.addOnPageChangeCallback(callback);
    }

    public void unregisterOnPageChangeCallback(@NonNull OnPageChangeListener callback) {
        pageChangeEventDispatcher.removeOnPageChangeCallback(callback);
    }

    public int getScrollState() {
        if (scrollEventAdapter == null) return RecyclerView.SCROLL_STATE_IDLE;
        return scrollEventAdapter.getScrollState();
    }

    public int getOrientation() {
        return orientation;
    }

    public void setOrientation(int orientation) {
        if (orientation != RecyclerView.HORIZONTAL && orientation != RecyclerView.VERTICAL) {
            throw new IllegalArgumentException("invalid orientation:" + orientation);
        }
        assertNotInLayoutOrScroll(null);
        if (orientation != this.orientation || orientationHelper == null) {
            orientationHelper =
                OrientationHelper.createOrientationHelper(this, orientation);
            mAnchorInfo.mOrientationHelper = orientationHelper;
            this.orientation = orientation;
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
        int totalSpace = orientationHelper.getEnd();
        if (getClipToPadding()) {
            totalSpace -= orientationHelper.getStartAfterPadding() + orientationHelper.getEndPadding();
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
        if (shouldReverseLayout) {
            if (getClipToPadding()) {
                offset = orientationHelper.getDecoratedEnd(pagerHeadView) - orientationHelper.getStartAfterPadding();
            } else {
                offset = orientationHelper.getDecoratedEnd(pagerHeadView) + orientationHelper.getEndPadding();
            }
        } else {
            offset = orientationHelper.getDecoratedStart(pagerHeadView) - orientationHelper.getStartAfterPadding();
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

    public static class OnPageChangeListener {

        public void onPageSize(int pageSize) {
        }

        public void onPageScrolled(int page, float pageOffset, @Px int pageOffsetPixels) {
        }

        public void onPageSelected(int page) {
        }

        public void onPageScrollStateChanged( int state) {
        }
    }

    private class DataSetChangeObserver extends RecyclerView.AdapterDataObserver {
        @Override
        public void onChanged() {
            int itemCount = adapter.getItemCount();
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
