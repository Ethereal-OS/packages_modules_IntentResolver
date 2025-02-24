/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.intentresolver;

import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.intentresolver.chooser.DisplayResolveInfo;
import com.android.intentresolver.model.AbstractResolverComparator;
import com.android.intentresolver.model.ResolverRankerServiceResolverComparator;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;

/**
 * A helper for the ResolverActivity that exposes methods to retrieve, filter and sort its list of
 * resolvers.
 */
public class ResolverListController {

    private final Context mContext;
    private final PackageManager mpm;
    private final int mLaunchedFromUid;

    // Needed for sorting resolvers.
    private final Intent mTargetIntent;
    private final String mReferrerPackage;

    private static final String TAG = "ResolverListController";
    private static final boolean DEBUG = false;

    private AbstractResolverComparator mResolverComparator;
    private boolean isComputed = false;

    public ResolverListController(
            Context context,
            PackageManager pm,
            Intent targetIntent,
            String referrerPackage,
            int launchedFromUid) {
        this(context, pm, targetIntent, referrerPackage, launchedFromUid,
                    new ResolverRankerServiceResolverComparator(
                        context, targetIntent, referrerPackage, null, null));
    }

    public ResolverListController(
            Context context,
            PackageManager pm,
            Intent targetIntent,
            String referrerPackage,
            int launchedFromUid,
            AbstractResolverComparator resolverComparator) {
        mContext = context;
        mpm = pm;
        mLaunchedFromUid = launchedFromUid;
        mTargetIntent = targetIntent;
        mReferrerPackage = referrerPackage;
        mResolverComparator = resolverComparator;
    }

    @VisibleForTesting
    public ResolveInfo getLastChosen() throws RemoteException {
        return AppGlobals.getPackageManager().getLastChosenActivity(
                mTargetIntent, mTargetIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                PackageManager.MATCH_DEFAULT_ONLY);
    }

    @VisibleForTesting
    public void setLastChosen(Intent intent, IntentFilter filter, int match)
            throws RemoteException {
        AppGlobals.getPackageManager().setLastChosenActivity(intent,
                intent.resolveType(mContext.getContentResolver()),
                PackageManager.MATCH_DEFAULT_ONLY,
                filter, match, intent.getComponent());
    }

    /**
     * Get data about all the ways the user with the specified handle can resolve any of the
     * provided {@code intents}.
     */
    public List<ResolvedComponentInfo> getResolversForIntentAsUser(
            boolean shouldGetResolvedFilter,
            boolean shouldGetActivityMetadata,
            boolean shouldGetOnlyDefaultActivities,
            List<Intent> intents,
            UserHandle userHandle) {
        int baseFlags = (shouldGetOnlyDefaultActivities ? PackageManager.MATCH_DEFAULT_ONLY : 0)
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | (shouldGetResolvedFilter ? PackageManager.GET_RESOLVED_FILTER : 0)
                | (shouldGetActivityMetadata ? PackageManager.GET_META_DATA : 0);
        return getResolversForIntentAsUserInternal(intents, userHandle, baseFlags);
    }

    private List<ResolvedComponentInfo> getResolversForIntentAsUserInternal(
            List<Intent> intents, UserHandle userHandle, int baseFlags) {
        List<ResolvedComponentInfo> resolvedComponents = null;
        for (int i = 0, N = intents.size(); i < N; i++) {
            Intent intent = intents.get(i);
            int flags = baseFlags;
            if (intent.isWebIntent()
                        || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) != 0) {
                flags |= PackageManager.MATCH_INSTANT;
            }
            // Because of AIDL bug, queryIntentActivitiesAsUser can't accept subclasses of Intent.
            intent = (intent.getClass() == Intent.class) ? intent : new Intent(
                    intent);
            final List<ResolveInfo> infos = mpm.queryIntentActivitiesAsUser(intent, flags,
                    userHandle);
            if (infos != null) {
                if (resolvedComponents == null) {
                    resolvedComponents = new ArrayList<>();
                }
                addResolveListDedupe(resolvedComponents, intent, infos);
            }
        }
        return resolvedComponents;
    }

    @VisibleForTesting
    public void addResolveListDedupe(
            List<ResolvedComponentInfo> into, Intent intent, List<ResolveInfo> from) {
        final int fromCount = from.size();
        final int intoCount = into.size();
        for (int i = 0; i < fromCount; i++) {
            final ResolveInfo newInfo = from.get(i);
            boolean found = false;
            // Only loop to the end of into as it was before we started; no dupes in from.
            for (int j = 0; j < intoCount; j++) {
                final ResolvedComponentInfo rci = into.get(j);
                if (isSameResolvedComponent(newInfo, rci)) {
                    found = true;
                    rci.add(intent, newInfo);
                    break;
                }
            }
            if (!found) {
                final ComponentName name = new ComponentName(
                        newInfo.activityInfo.packageName, newInfo.activityInfo.name);
                final ResolvedComponentInfo rci = new ResolvedComponentInfo(name, intent, newInfo);
                rci.setPinned(isComponentPinned(name));
                into.add(rci);
            }
        }
    }


    /**
     * Whether this component is pinned by the user. Always false for resolver; overridden in
     * Chooser.
     */
    public boolean isComponentPinned(ComponentName name) {
        return false;
    }

    // Filter out any activities that the launched uid does not have permission for.
    // To preserve the inputList, optionally will return the original list if any modification has
    // been made.
    @VisibleForTesting
    public ArrayList<ResolvedComponentInfo> filterIneligibleActivities(
            List<ResolvedComponentInfo> inputList, boolean returnCopyOfOriginalListIfModified) {
        ArrayList<ResolvedComponentInfo> listToReturn = null;
        for (int i = inputList.size()-1; i >= 0; i--) {
            ActivityInfo ai = inputList.get(i)
                    .getResolveInfoAt(0).activityInfo;
            int granted = ActivityManager.checkComponentPermission(
                    ai.permission, mLaunchedFromUid,
                    ai.applicationInfo.uid, ai.exported);

            if (granted != PackageManager.PERMISSION_GRANTED
                    || isComponentFiltered(ai.getComponentName())) {
                // Access not allowed! We're about to filter an item,
                // so modify the unfiltered version if it hasn't already been modified.
                if (returnCopyOfOriginalListIfModified && listToReturn == null) {
                    listToReturn = new ArrayList<>(inputList);
                }
                inputList.remove(i);
            }
        }
        return listToReturn;
    }

    // Filter out any low priority items.
    //
    // To preserve the inputList, optionally will return the original list if any modification has
    // been made.
    @VisibleForTesting
    public ArrayList<ResolvedComponentInfo> filterLowPriority(
            List<ResolvedComponentInfo> inputList, boolean returnCopyOfOriginalListIfModified) {
        ArrayList<ResolvedComponentInfo> listToReturn = null;
        // Only display the first matches that are either of equal
        // priority or have asked to be default options.
        ResolvedComponentInfo rci0 = inputList.get(0);
        ResolveInfo r0 = rci0.getResolveInfoAt(0);
        int N = inputList.size();
        for (int i = 1; i < N; i++) {
            ResolveInfo ri = inputList.get(i).getResolveInfoAt(0);
            if (DEBUG) Log.v(
                    TAG,
                    r0.activityInfo.name + "=" +
                            r0.priority + "/" + r0.isDefault + " vs " +
                            ri.activityInfo.name + "=" +
                            ri.priority + "/" + ri.isDefault);
            if (r0.priority != ri.priority ||
                    r0.isDefault != ri.isDefault) {
                while (i < N) {
                    if (returnCopyOfOriginalListIfModified && listToReturn == null) {
                        listToReturn = new ArrayList<>(inputList);
                    }
                    inputList.remove(i);
                    N--;
                }
            }
        }
        return listToReturn;
    }

    private void compute(List<ResolvedComponentInfo> inputList) throws InterruptedException {
        if (mResolverComparator == null) {
            Log.d(TAG, "Comparator has already been destroyed; skipped.");
            return;
        }
        final CountDownLatch finishComputeSignal = new CountDownLatch(1);
        mResolverComparator.setCallBack(() -> finishComputeSignal.countDown());
        mResolverComparator.compute(inputList);
        finishComputeSignal.await();
        isComputed = true;
    }

    @VisibleForTesting
    @WorkerThread
    public void sort(List<ResolvedComponentInfo> inputList) {
        try {
            long beforeRank = System.currentTimeMillis();
            if (!isComputed) {
                compute(inputList);
            }
            Collections.sort(inputList, mResolverComparator);

            long afterRank = System.currentTimeMillis();
            if (DEBUG) {
                Log.d(TAG, "Time Cost: " + Long.toString(afterRank - beforeRank));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Compute & Sort was interrupted: " + e);
        }
    }

    @VisibleForTesting
    @WorkerThread
    public void topK(List<ResolvedComponentInfo> inputList, int k) {
        if (inputList == null || inputList.isEmpty() || k <= 0) {
            return;
        }
        if (inputList.size() <= k) {
            // Fall into normal sort when number of ranked elements
            // needed is not smaller than size of input list.
            sort(inputList);
            return;
        }
        try {
            long beforeRank = System.currentTimeMillis();
            if (!isComputed) {
                compute(inputList);
            }

            // Top of this heap has lowest rank.
            PriorityQueue<ResolvedComponentInfo> minHeap = new PriorityQueue<>(k,
                    (o1, o2) -> -mResolverComparator.compare(o1, o2));
            final int size = inputList.size();
            // Use this pointer to keep track of the position of next element
            // to update in input list, starting from the last position.
            int pointer = size - 1;
            minHeap.addAll(inputList.subList(size - k, size));
            for (int i = size - k - 1; i >= 0; --i) {
                ResolvedComponentInfo ci = inputList.get(i);
                if (-mResolverComparator.compare(ci, minHeap.peek()) > 0) {
                    // When ranked higher than top of heap, remove top of heap,
                    // update input list with it, add this new element to heap.
                    inputList.set(pointer--, minHeap.poll());
                    minHeap.add(ci);
                } else {
                    // When ranked no higher than top of heap, update input list
                    // with this new element.
                    inputList.set(pointer--, ci);
                }
            }

            // Now we have top k elements in heap, update first
            // k positions of input list with them.
            while (!minHeap.isEmpty()) {
                inputList.set(pointer--, minHeap.poll());
            }

            long afterRank = System.currentTimeMillis();
            if (DEBUG) {
                Log.d(TAG, "Time Cost for top " + k + " targets: "
                        + Long.toString(afterRank - beforeRank));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Compute & greatestOf was interrupted: " + e);
        }
    }

    private static boolean isSameResolvedComponent(ResolveInfo a, ResolvedComponentInfo b) {
        final ActivityInfo ai = a.activityInfo;
        return ai.packageName.equals(b.name.getPackageName())
                && ai.name.equals(b.name.getClassName());
    }

    boolean isComponentFiltered(ComponentName componentName) {
        return false;
    }

    @VisibleForTesting
    public float getScore(DisplayResolveInfo target) {
        return mResolverComparator.getScore(target.getResolvedComponentName());
    }

    /**
     * Returns the app share score of the given {@code componentName}.
     */
    public float getScore(ComponentName componentName) {
        return mResolverComparator.getScore(componentName);
    }

    public void updateModel(ComponentName componentName) {
        mResolverComparator.updateModel(componentName);
    }

    public void updateChooserCounts(String packageName, int userId, String action) {
        mResolverComparator.updateChooserCounts(packageName, userId, action);
    }

    public void destroy() {
        mResolverComparator.destroy();
    }
}
