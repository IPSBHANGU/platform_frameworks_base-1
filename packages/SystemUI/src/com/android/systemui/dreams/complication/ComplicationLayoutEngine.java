/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.dagger.ComplicationHostViewComponent.SCOPED_COMPLICATIONS_LAYOUT;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Constraints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ComplicationLayoutEngine} arranges a collection of {@link ComplicationViewModel} based on
 * their layout parameters and attributes. The management of this set is done by
 * {@link ComplicationHostViewController}.
 */
public class ComplicationLayoutEngine  {
    public static final String TAG = "ComplicationLayoutEngine";

    /**
     * {@link ViewEntry} is an internal container, capturing information necessary for working with
     * a particular {@link Complication} view.
     */
    private static class ViewEntry implements Comparable<ViewEntry> {
        private final View mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final Parent mParent;
        @Complication.Category
        private final int mCategory;

        /**
         * Default constructor. {@link Parent} allows for the {@link ViewEntry}'s surrounding
         * view hierarchy to be accessed without traversing the entire view tree.
         */
        ViewEntry(View view, ComplicationLayoutParams layoutParams, int category, Parent parent) {
            mView = view;
            // Views that are generated programmatically do not have a unique id assigned to them
            // at construction. A new id is assigned here to enable ConstraintLayout relative
            // specifications. Existing ids for inflated views are not preserved.
            // {@link Complication.ViewHolder} should not reference the root container by id.
            mView.setId(View.generateViewId());
            mLayoutParams = layoutParams;
            mCategory = category;
            mParent = parent;
        }

        /**
         * Returns the {@link View} associated with the {@link Complication}. This is the instance
         * passed in at construction. The reference to this {@link View} is captured when the
         * {@link Complication} is added to the {@link ComplicationLayoutEngine}. The
         * {@link Complication} cannot modify the {@link View} reference beyond this point.
         */
        private View getView() {
            return mView;
        }

        /**
         * Returns The {@link ComplicationLayoutParams} associated with the view.
         */
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }

        /**
         * Interprets the {@link #getLayoutParams()} into {@link ConstraintLayout.LayoutParams} and
         * applies them to the view. The method accounts for the relationship of the {@link View} to
         * the other {@link Complication} views around it. The organization of the {@link View}
         * instances in {@link ComplicationLayoutEngine} can be seen as lists. A {@link View} is
         * either the head of its list or a following node. This head is passed into this method,
         * which can be a reference to the {@link View} to indicate it is the head.
         */
        public void applyLayoutParams(View head) {
            // Only the basic dimension parameters from the base ViewGroup.LayoutParams are carried
            // over verbatim from the complication specified LayoutParam. Other fields are
            // interpreted.
            final ConstraintLayout.LayoutParams params =
                    new Constraints.LayoutParams(mLayoutParams.width, mLayoutParams.height);

            final int direction = getLayoutParams().getDirection();

            // If no parent, view is the anchor. In this case, it is given the highest priority for
            // alignment. All alignment preferences are done in relation to the parent container.
            final boolean isRoot = head == mView;

            // Each view can be seen as a vector, having a point (described here as position) and
            // direction. When a view is the head of a position, then it is the first in a sequence
            // of complications to appear from that position. For example, being the head for
            // position POSITION_TOP | POSITION_END will cause the view to be shown as the first
            // view in that corner. In this case, the positions specify which sides to align with
            // the parent. If the view is not the head, the positions perpendicular to the direction
            // of the view specify which side to align with the opposing side of the head view.
            // Otherwise, the position aligns with the containing view. This means a
            // POSITION_BOTTOM | POSITION_START with DIRECTION_UP non-head view's bottom to be
            // aligned with the preceding view node's top and start to be aligned with the
            // parent's start.
            mLayoutParams.iteratePositions(position -> {
                switch(position) {
                    case ComplicationLayoutParams.POSITION_START:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_END) {
                            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.startToEnd = head.getId();
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_TOP:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_DOWN) {
                            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.topToBottom = head.getId();
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_BOTTOM:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_UP) {
                            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.bottomToTop = head.getId();
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_END:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_START) {
                            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.endToStart = head.getId();
                        }
                        break;
                }
            });

            mView.setLayoutParams(params);
        }

        /**
         * Informs the {@link ViewEntry}'s parent entity to remove the {@link ViewEntry} from
         * being shown further.
         */
        public void remove() {
            mParent.removeEntry(this);

            ((ViewGroup) mView.getParent()).removeView(mView);
        }

        @Override
        public int compareTo(ViewEntry viewEntry) {
            // If the two entries have different categories, system complications take precedence.
            if (viewEntry.mCategory != mCategory) {
                // Note that this logic will need to be adjusted if more categories are introduced.
                return mCategory == Complication.CATEGORY_SYSTEM ? 1 : -1;
            }

            // A higher weight indicates greater precedence if all else being equal.
            if (viewEntry.mLayoutParams.getWeight() != mLayoutParams.getWeight()) {
                return mLayoutParams.getWeight() > viewEntry.mLayoutParams.getWeight() ? 1 : -1;
            }

            return 0;
        }

        /**
         * {@link Builder} allows for a multiple entities to contribute to the {@link ViewEntry}
         * construction. This is necessary for setting an immutable parent, which might not be
         * known until the view hierarchy is traversed.
         */
        private static class Builder {
            private final View mView;
            private final ComplicationLayoutParams mLayoutParams;
            private final int mCategory;
            private Parent mParent;

            Builder(View view, ComplicationLayoutParams lp, @Complication.Category int category) {
                mView = view;
                mLayoutParams = lp;
                mCategory = category;
            }

            /**
             * Returns the set {@link ComplicationLayoutParams}
             */
            public ComplicationLayoutParams getLayoutParams() {
                return mLayoutParams;
            }

            /**
             * Returns the set {@link Complication.Category}.
             */
            @Complication.Category
            public int getCategory() {
                return mCategory;
            }

            /**
             * Sets the parent. Note that this references to the entity for handling events, such as
             * requesting the removal of the {@link View}. It is not the
             * {@link android.view.ViewGroup} which contains the {@link View}.
             */
            Builder setParent(Parent parent) {
                mParent = parent;
                return this;
            }

            /**
             * Builds and returns the resulting {@link ViewEntry}.
             */
            ViewEntry build() {
                return new ViewEntry(mView, mLayoutParams, mCategory, mParent);
            }
        }

        /**
         * An interface allowing an {@link ViewEntry} to signal events.
         */
        interface Parent {
            /**
             * Indicates the {@link ViewEntry} requests removal.
             */
            void removeEntry(ViewEntry entry);
        }
    }

    /**
     * {@link PositionGroup} represents a collection of {@link Complication} at a given location.
     * It further organizes the {@link Complication} by the direction in which they emanate from
     * this position.
     */
    private static class PositionGroup implements DirectionGroup.Parent {
        private final HashMap<Integer, DirectionGroup> mDirectionGroups = new HashMap<>();

        /**
         * Invoked by the {@link PositionGroup} holder to introduce a {@link Complication} view to
         * this group. It is assumed that the caller has correctly identified this
         * {@link PositionGroup} as the proper home for the {@link Complication} based on its
         * declared position.
         */
        public ViewEntry add(ViewEntry.Builder entryBuilder) {
            final int direction = entryBuilder.getLayoutParams().getDirection();
            if (!mDirectionGroups.containsKey(direction)) {
                mDirectionGroups.put(direction, new DirectionGroup(this));
            }

            return mDirectionGroups.get(direction).add(entryBuilder);
        }

        @Override
        public void onEntriesChanged() {
            // Whenever an entry is added/removed from a child {@link DirectionGroup}, it is vital
            // that all {@link DirectionGroup} children are visited. It is possible the overall
            // head has changed, requiring constraints to be adjusted.
            updateViews();
        }

        private void updateViews() {
            ViewEntry head = null;

            // Identify which {@link Complication} head from the set of {@link DirectionGroup}
            // should be treated as the {@link PositionGroup} head.
            for (DirectionGroup directionGroup : mDirectionGroups.values()) {
                final ViewEntry groupHead = directionGroup.getHead();
                if (head == null || (groupHead != null && groupHead.compareTo(head) > 0)) {
                    head = groupHead;
                }
            }

            // A headless position group indicates no complications.
            if (head == null) {
                return;
            }

            for (DirectionGroup directionGroup : mDirectionGroups.values()) {
                // Tell each {@link DirectionGroup} to update its containing {@link ViewEntry} based
                // on the identified head. This iteration will also capture any newly added views.
                directionGroup.updateViews(head.getView());
            }
        }
    }

    /**
     * A {@link DirectionGroup} organizes the {@link ViewEntry} of a parent group that point are
     * laid out in the same direction.
     */
    private static class DirectionGroup implements ViewEntry.Parent {
        /**
         * An interface implemented by the {@link DirectionGroup} parent to receive updates.
         */
        interface Parent {
            /**
             * Invoked to indicate a change to the {@link ViewEntry} composition for this
             * {@link DirectionGroup}.
             */
            void onEntriesChanged();
        }
        private final ArrayList<ViewEntry> mViews = new ArrayList<>();
        private final Parent mParent;

        /**
         * Creates a new {@link DirectionGroup} with the specified parent. Note that the
         * {@link DirectionGroup} does not store its own direction. It is the responsibility of the
         * {@link DirectionGroup.Parent} to maintain this association.
         */
        DirectionGroup(Parent parent) {
            mParent = parent;
        }

        /**
         * Returns the head of the group. It is assumed that the order of the {@link ViewEntry} is
         * proactively maintained.
         */
        public ViewEntry getHead() {
            return mViews.isEmpty() ? null : mViews.get(0);
        }

        /**
         * Adds a {@link ViewEntry} via {@link ViewEntry.Builder} to this group.
         */
        public ViewEntry add(ViewEntry.Builder entryBuilder) {
            final ViewEntry entry = entryBuilder.setParent(this).build();
            mViews.add(entry);

            // After adding view, reverse sort collection.
            Collections.sort(mViews);
            Collections.reverse(mViews);

            mParent.onEntriesChanged();

            return entry;
        }

        @Override
        public void removeEntry(ViewEntry entry) {
            // Sort is handled when the view is added, so should still be correct after removal.
            // However, the head may have been removed, which may affect the layout of views in
            // other DirectionGroups of the same PositionGroup.
            mViews.remove(entry);
            mParent.onEntriesChanged();
        }

        /**
         * Invoked by {@link Parent} to update the layout of all children {@link ViewEntry} with
         * the specified head. Note that the head might not be in this group and instead part of a
         * neighboring group.
         */
        public void updateViews(View groupHead) {
            Iterator<ViewEntry> it = mViews.iterator();

            while (it.hasNext()) {
                final ViewEntry viewEntry = it.next();
                viewEntry.applyLayoutParams(groupHead);
                groupHead = viewEntry.getView();
            }
        }
    }

    private final ConstraintLayout mLayout;
    private final HashMap<ComplicationId, ViewEntry> mEntries = new HashMap<>();
    private final HashMap<Integer, PositionGroup> mPositions = new HashMap<>();

    /** */
    @Inject
    public ComplicationLayoutEngine(@Named(SCOPED_COMPLICATIONS_LAYOUT) ConstraintLayout layout) {
        mLayout = layout;
    }

    /**
     * Adds a complication to this {@link ComplicationLayoutEngine}.
     * @param id A {@link ComplicationId} unique to this complication. If this matches a
     *           complication within this {@link ComplicationViewModel}, the existing complication
     *           will be removed.
     * @param view The {@link View} to be shown.
     * @param lp The {@link ComplicationLayoutParams} as expressed by the {@link Complication}.
     *           These will be interpreted into the final applied parameters.
     * @param category The {@link Complication.Category} for the {@link Complication}.
     */
    public void addComplication(ComplicationId id, View view,
            ComplicationLayoutParams lp, @Complication.Category int category) {
        // If the complication is present, remove.
        if (mEntries.containsKey(id)) {
            removeComplication(id);
        }

        final ViewEntry.Builder entryBuilder = new ViewEntry.Builder(view, lp, category);

        // Add position group if doesn't already exist
        final int position = lp.getPosition();
        if (!mPositions.containsKey(position)) {
            mPositions.put(position, new PositionGroup());
        }

        // Insert entry into group
        final ViewEntry entry = mPositions.get(position).add(entryBuilder);
        mEntries.put(id, entry);

        mLayout.addView(entry.getView());
    }

    /**
     * Removes a complication by {@link ComplicationId}.
     */
    public void removeComplication(ComplicationId id) {
        if (!mEntries.containsKey(id)) {
            Log.e(TAG, "could not find id:" + id);
            return;
        }

        final ViewEntry entry = mEntries.get(id);
        entry.remove();
    }
}
