/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.intentresolver.AbstractMultiProfilePagerAdapter.CrossProfileIntentsChecker;
import com.android.intentresolver.AbstractMultiProfilePagerAdapter.MyUserIdProvider;
import com.android.intentresolver.chooser.TargetInfo;

import java.util.List;
import java.util.function.Function;

/*
 * Simple wrapper around chooser activity to be able to initiate it under test
 */
public class ResolverWrapperActivity extends ResolverActivity {
    static final OverrideData sOverrides = new OverrideData();
    private UsageStatsManager mUsm;

    public ResolverWrapperActivity() {
        super(/* isIntentPicker= */ true);
    }

    // ResolverActivity inspects the launched-from UID at onCreate and needs to see some
    // non-negative value in the test.
    @Override
    public int getLaunchedFromUid() {
        return 1234;
    }

    @Override
    public ResolverListAdapter createResolverListAdapter(Context context,
            List<Intent> payloadIntents, Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, UserHandle userHandle) {
        return new ResolverWrapperAdapter(
                context,
                payloadIntents,
                initialIntents,
                rList,
                filterLastUsed,
                createListController(userHandle),
                userHandle,
                payloadIntents.get(0),  // TODO: extract upstream
                this);
    }

    @Override
    protected MyUserIdProvider createMyUserIdProvider() {
        if (sOverrides.mMyUserIdProvider != null) {
            return sOverrides.mMyUserIdProvider;
        }
        return super.createMyUserIdProvider();
    }

    @Override
    protected CrossProfileIntentsChecker createCrossProfileIntentsChecker() {
        if (sOverrides.mCrossProfileIntentsChecker != null) {
            return sOverrides.mCrossProfileIntentsChecker;
        }
        return super.createCrossProfileIntentsChecker();
    }

    @Override
    protected WorkProfileAvailabilityManager createWorkProfileAvailabilityManager() {
        if (sOverrides.mWorkProfileAvailability != null) {
            return sOverrides.mWorkProfileAvailability;
        }
        return super.createWorkProfileAvailabilityManager();
    }

    ResolverWrapperAdapter getAdapter() {
        return (ResolverWrapperAdapter) mMultiProfilePagerAdapter.getActiveListAdapter();
    }

    ResolverListAdapter getPersonalListAdapter() {
        return ((ResolverListAdapter) mMultiProfilePagerAdapter.getAdapterForIndex(0));
    }

    ResolverListAdapter getWorkListAdapter() {
        if (mMultiProfilePagerAdapter.getInactiveListAdapter() == null) {
            return null;
        }
        return ((ResolverListAdapter) mMultiProfilePagerAdapter.getAdapterForIndex(1));
    }

    @Override
    public boolean isVoiceInteraction() {
        if (sOverrides.isVoiceInteraction != null) {
            return sOverrides.isVoiceInteraction;
        }
        return super.isVoiceInteraction();
    }

    @Override
    public void safelyStartActivity(TargetInfo cti) {
        if (sOverrides.onSafelyStartCallback != null &&
                sOverrides.onSafelyStartCallback.apply(cti)) {
            return;
        }
        super.safelyStartActivity(cti);
    }

    @Override
    protected ResolverListController createListController(UserHandle userHandle) {
        if (userHandle == UserHandle.SYSTEM) {
            return sOverrides.resolverListController;
        }
        return sOverrides.workResolverListController;
    }

    @Override
    public PackageManager getPackageManager() {
        if (sOverrides.createPackageManager != null) {
            return sOverrides.createPackageManager.apply(super.getPackageManager());
        }
        return super.getPackageManager();
    }

    protected UserHandle getCurrentUserHandle() {
        return mMultiProfilePagerAdapter.getCurrentUserHandle();
    }

    @Override
    protected UserHandle getWorkProfileUserHandle() {
        return sOverrides.workProfileUserHandle;
    }

    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        super.startActivityAsUser(intent, options, user);
    }

    /**
     * We cannot directly mock the activity created since instrumentation creates it.
     * <p>
     * Instead, we use static instances of this object to modify behavior.
     */
    static class OverrideData {
        @SuppressWarnings("Since15")
        public Function<PackageManager, PackageManager> createPackageManager;
        public Function<TargetInfo, Boolean> onSafelyStartCallback;
        public ResolverListController resolverListController;
        public ResolverListController workResolverListController;
        public Boolean isVoiceInteraction;
        public UserHandle workProfileUserHandle;
        public Integer myUserId;
        public boolean hasCrossProfileIntents;
        public boolean isQuietModeEnabled;
        public WorkProfileAvailabilityManager mWorkProfileAvailability;
        public MyUserIdProvider mMyUserIdProvider;
        public CrossProfileIntentsChecker mCrossProfileIntentsChecker;

        public void reset() {
            onSafelyStartCallback = null;
            isVoiceInteraction = null;
            createPackageManager = null;
            resolverListController = mock(ResolverListController.class);
            workResolverListController = mock(ResolverListController.class);
            workProfileUserHandle = null;
            myUserId = null;
            hasCrossProfileIntents = true;
            isQuietModeEnabled = false;

            mWorkProfileAvailability = new WorkProfileAvailabilityManager(null, null, null) {
                @Override
                public boolean isQuietModeEnabled() {
                    return isQuietModeEnabled;
                }

                @Override
                public boolean isWorkProfileUserUnlocked() {
                    return true;
                }

                @Override
                public void requestQuietModeEnabled(boolean enabled) {
                    isQuietModeEnabled = enabled;
                }

                @Override
                public void markWorkProfileEnabledBroadcastReceived() {}

                @Override
                public boolean isWaitingToEnableWorkProfile() {
                    return false;
                }
            };

            mMyUserIdProvider = new MyUserIdProvider() {
                @Override
                public int getMyUserId() {
                    return myUserId != null ? myUserId : UserHandle.myUserId();
                }
            };

            mCrossProfileIntentsChecker = mock(CrossProfileIntentsChecker.class);
            when(mCrossProfileIntentsChecker.hasCrossProfileIntents(any(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> hasCrossProfileIntents);
        }
    }
}
