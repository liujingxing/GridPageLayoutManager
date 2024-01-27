package com.ljx.layoutmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Px;
import androidx.recyclerview.widget.GridPageLayoutManager.OnPageChangeListener;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

/**
 * User: ljx
 * Date: 2023/12/30
 * Time: 14:07
 */
public final class CompositeOnPageChangeCallback extends OnPageChangeListener {
    @NonNull
    private final List<OnPageChangeListener> mCallbacks;

    public CompositeOnPageChangeCallback(int initialCapacity) {
        mCallbacks = new ArrayList<>(initialCapacity);
    }

    /**
     * Adds the given callback to the list of subscribers
     */
    public void addOnPageChangeCallback(OnPageChangeListener callback) {
        mCallbacks.add(callback);
    }

    /**
     * Removes the given callback from the list of subscribers
     */
    public void removeOnPageChangeCallback(OnPageChangeListener callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, @Px int positionOffsetPixels) {
        try {
            for (OnPageChangeListener callback : mCallbacks) {
                callback.onPageScrolled(position, positionOffset, positionOffsetPixels);
            }
        } catch (ConcurrentModificationException ex) {
            throwCallbackListModifiedWhileInUse(ex);
        }
    }

    /**
     * @see androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback#onPageSelected(int)
     */
    @Override
    public void onPageSelected(int position) {
        try {
            for (OnPageChangeListener callback : mCallbacks) {
                callback.onPageSelected(position);
            }
        } catch (ConcurrentModificationException ex) {
            throwCallbackListModifiedWhileInUse(ex);
        }
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        try {
            for (OnPageChangeListener callback : mCallbacks) {
                callback.onPageScrollStateChanged(state);
            }
        } catch (ConcurrentModificationException ex) {
            throwCallbackListModifiedWhileInUse(ex);
        }
    }

    @Override
    public void onPageSize(int pageSize) {
        try {
            for (OnPageChangeListener callback : mCallbacks) {
                callback.onPageSize(pageSize);
            }
        } catch (ConcurrentModificationException ex) {
            throwCallbackListModifiedWhileInUse(ex);
        }
    }

    private void throwCallbackListModifiedWhileInUse(ConcurrentModificationException parent) {
        throw new IllegalStateException(
            "Adding and removing callbacks during dispatch to callbacks is not supported",
            parent
        );
    }

}