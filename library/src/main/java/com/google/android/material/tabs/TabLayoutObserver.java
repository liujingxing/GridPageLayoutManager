package com.google.android.material.tabs;

import static androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING;
import static androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE;
import static androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import androidx.recyclerview.widget.GridPageLayoutManager;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import java.lang.ref.WeakReference;

public final class TabLayoutObserver {
    @NonNull private final TabLayout tabLayout;
    @NonNull private final GridPageLayoutManager gridPager;
    private final boolean autoRefresh;
    private final boolean smoothScroll;
    private final TabConfigurationStrategy tabConfigurationStrategy;
    @Nullable private RecyclerView.Adapter<?> adapter;
    private boolean attached;

    @Nullable private TabLayoutOnPageChangeCallback onPageChangeCallback;
    @Nullable private TabLayout.OnTabSelectedListener onTabSelectedListener;
    @Nullable private RecyclerView.AdapterDataObserver pagerAdapterObserver;

    public TabLayoutObserver(@NonNull TabLayout tabLayout, @NonNull GridPageLayoutManager gridPager, @NonNull TabConfigurationStrategy tabConfigurationStrategy) {
        this(tabLayout, gridPager, /* autoRefresh= */ true, tabConfigurationStrategy);
    }

    public TabLayoutObserver(@NonNull TabLayout tabLayout, @NonNull GridPageLayoutManager gridPager, boolean autoRefresh, @NonNull TabConfigurationStrategy tabConfigurationStrategy) {
        this(tabLayout, gridPager, autoRefresh, /* smoothScroll= */ true, tabConfigurationStrategy);
    }

    public TabLayoutObserver(@NonNull TabLayout tabLayout, @NonNull GridPageLayoutManager gridPager, boolean autoRefresh, boolean smoothScroll, @NonNull TabConfigurationStrategy tabConfigurationStrategy) {
        this.tabLayout = tabLayout;
        this.gridPager = gridPager;
        this.autoRefresh = autoRefresh;
        this.smoothScroll = smoothScroll;
        this.tabConfigurationStrategy = tabConfigurationStrategy;
    }

    public void onAdapterChanged(@Nullable Adapter oldAdapter, @Nullable Adapter newAdapter) {

    }

    public void attach() {
        if (attached) {
            throw new IllegalStateException("GridPagerMediator is already attached");
        }
        adapter = gridPager.getAdapter();
        if (adapter == null) {
            throw new IllegalStateException(
                "GridPagerMediator attached before RecyclerView has an " + "adapter");
        }
        attached = true;

        // Add our custom OnPageChangeCallback to the ViewPager
        onPageChangeCallback = new TabLayoutOnPageChangeCallback(tabLayout);
        gridPager.registerOnQueueChangeListener(onPageChangeCallback);

        // Now we'll add a tab selected listener to set ViewPager's current item
        onTabSelectedListener = new ViewPagerOnTabSelectedListener(gridPager, smoothScroll);
        tabLayout.addOnTabSelectedListener(onTabSelectedListener);

        // Now we'll populate ourselves from the pager adapter, adding an observer if
        // autoRefresh is enabled
        if (autoRefresh) {
            // Register our observer on the new adapter
            pagerAdapterObserver = new PagerAdapterObserver();
            adapter.registerAdapterDataObserver(pagerAdapterObserver);
        }

        populateTabsFromPagerAdapter();

        // Now update the scroll position to match the ViewPager's current item
        tabLayout.setScrollPosition(gridPager.getCurrentQueueItem(), 0f, true);
    }


    public void detach() {
        if (autoRefresh && adapter != null && pagerAdapterObserver != null) {
            adapter.unregisterAdapterDataObserver(pagerAdapterObserver);
            pagerAdapterObserver = null;
        }
        if (onTabSelectedListener != null) {
            tabLayout.removeOnTabSelectedListener(onTabSelectedListener);
            onTabSelectedListener = null;
        }

        if (onPageChangeCallback != null) {
            gridPager.unregisterOnQueueChangeListener(onPageChangeCallback);
            onPageChangeCallback = null;
        }
        adapter = null;
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    @SuppressWarnings("WeakerAccess")
    void populateTabsFromPagerAdapter() {
        tabLayout.removeAllTabs();

        if (gridPager != null) {
            int adapterCount = gridPager.getQueueSize();
            for (int i = 0; i < adapterCount; i++) {
                TabLayout.Tab tab = tabLayout.newTab();
                tabConfigurationStrategy.onConfigureTab(tab, i);
                tabLayout.addTab(tab, false);
            }
            // Make sure we reflect the currently set ViewPager item
            if (adapterCount > 0) {
                int lastItem = tabLayout.getTabCount() - 1;
                int currItem = Math.min(gridPager.getCurrentQueueItem(), lastItem);
                if (currItem != tabLayout.getSelectedTabPosition()) {
                    tabLayout.selectTab(tabLayout.getTabAt(currItem));
                }
            }
        }
    }

    public interface TabConfigurationStrategy {
        void onConfigureTab(@NonNull TabLayout.Tab tab, int position);
    }


    private static class TabLayoutOnPageChangeCallback extends GridPageLayoutManager.OnPageChangeListener {
        @NonNull private final WeakReference<TabLayout> tabLayoutRef;
        private int previousScrollState;
        private int scrollState;

        TabLayoutOnPageChangeCallback(TabLayout tabLayout) {
            tabLayoutRef = new WeakReference<>(tabLayout);
            reset();
        }

        @Override
        public void onPageScrollStateChanged(final int state) {
            previousScrollState = scrollState;
            scrollState = state;
            TabLayout tabLayout = tabLayoutRef.get();
            if (tabLayout != null) {
                tabLayout.updateViewPagerScrollState(scrollState);
            }
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            TabLayout tabLayout = tabLayoutRef.get();
            if (tabLayout != null) {
                // Only update the tab view selection if we're not settling, or we are settling after
                // being dragged
                boolean updateSelectedTabView = scrollState != SCROLL_STATE_SETTLING
                    || previousScrollState == SCROLL_STATE_DRAGGING;
                // Update the indicator if we're not settling after being idle. This is caused
                // from a setCurrentItem() call and will be handled by an animation from
                // onPageSelected() instead.
                boolean updateIndicator = !(scrollState == SCROLL_STATE_SETTLING
                    && previousScrollState == SCROLL_STATE_IDLE);
                tabLayout.setScrollPosition(position, positionOffset, updateSelectedTabView, updateIndicator, false);
            }
        }

        @Override
        public void onPageSelected(final int position) {
            TabLayout tabLayout = tabLayoutRef.get();
            if (tabLayout != null
                && tabLayout.getSelectedTabPosition() != position
                && position < tabLayout.getTabCount()) {
                // Select the tab, only updating the indicator if we're not being dragged/settled
                // (since onPageScrolled will handle that).
                boolean updateIndicator = scrollState == SCROLL_STATE_IDLE
                    || (scrollState == SCROLL_STATE_SETTLING && previousScrollState == SCROLL_STATE_IDLE);
                tabLayout.selectTab(tabLayout.getTabAt(position), updateIndicator);
            }
        }

        void reset() {
            previousScrollState = scrollState = SCROLL_STATE_IDLE;
        }
    }

    private static class ViewPagerOnTabSelectedListener implements TabLayout.OnTabSelectedListener {
        private final GridPageLayoutManager viewPager;
        private final boolean smoothScroll;

        ViewPagerOnTabSelectedListener(GridPageLayoutManager viewPager, boolean smoothScroll) {
            this.viewPager = viewPager;
            this.smoothScroll = smoothScroll;
        }

        @Override
        public void onTabSelected(@NonNull TabLayout.Tab tab) {
            viewPager.setCurrentQueueItem(tab.getPosition(), smoothScroll);
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {
            // No-op
        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {
            // No-op
        }
    }

    private class PagerAdapterObserver extends RecyclerView.AdapterDataObserver {
        PagerAdapterObserver() {
        }

        @Override
        public void onChanged() {
            populateTabsFromPagerAdapter();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            populateTabsFromPagerAdapter();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
            populateTabsFromPagerAdapter();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            populateTabsFromPagerAdapter();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            populateTabsFromPagerAdapter();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            populateTabsFromPagerAdapter();
        }
    }
}
