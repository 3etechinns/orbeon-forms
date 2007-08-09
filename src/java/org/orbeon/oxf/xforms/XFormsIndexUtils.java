/**
 *  Copyright (C) 2006 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.Node;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatControl;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.om.NodeInfo;

import java.util.*;

/**
 * Useful functions for handling repeat indexes.
 */
public class XFormsIndexUtils {

    /**
     * Ajust repeat indexes so that they are put back within bounds.
     *
     * @param pipelineContext
     * @param xformsControls
     * @param currentControlsState
     */
    public static void adjustIndexes(PipelineContext pipelineContext, final XFormsControls xformsControls,
                                     final XFormsControls.ControlsState currentControlsState) {

        // NOTE: You can imagine really complicated stuff related to index
        // updates. Here, we assume that repeat iterations do
        // *not* depend on instance values that themselves depend on the index()
        // function. This scenario is not impossible, but fairly far-fetched I think, and we haven't seen it yet. So
        // once an instance structure and content is determined, we assume that it won't change in a significant way
        // with index updates performed below.

        // However, one scenario we want to allow is a repeat "detail" on the same level as a repeat "master", where
        // the repeat detail iteration depends on index('master').

        // TODO: detect use of index() function
        final Map updatedIndexesIds = new HashMap();
        currentControlsState.visitControlsFollowRepeats(pipelineContext, xformsControls, new XFormsControls.XFormsControlVisitorListener() {

            private int level = 0;

            public void startVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    // Found an xforms:repeat
                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) XFormsControl;
                    final String repeatId = repeatControlInfo.getOriginalId();
                    final List repeatNodeSet = xformsControls.getCurrentNodeset();

                    // Make sure the bounds of this xforms:repeat are correct
                    // for the rest of the visit.

                    final int adjustedNewIndex;
                    {
                        final int newIndex = ((Integer) currentControlsState.getRepeatIdToIndex().get(repeatId)).intValue();

                        // Adjust bounds if necessary
                        if (repeatNodeSet == null || repeatNodeSet.size() == 0)
                            adjustedNewIndex = 0;
                        else if (newIndex < 1)
                            adjustedNewIndex = 1;
                        else if (newIndex > repeatNodeSet.size())
                            adjustedNewIndex = repeatNodeSet.size();
                        else
                            adjustedNewIndex = newIndex;
                    }

                    // Set index
                    currentControlsState.updateRepeatIndex(repeatId, adjustedNewIndex);
                    updatedIndexesIds.put(repeatId, "");

                    level++;
                }
            }

            public void endVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    level--;
                }
            }
        });

        // Repeats that haven't been reached are set to 0
        for (Iterator i = currentControlsState.getRepeatIdToIndex().entrySet().iterator(); i.hasNext();) {
            final Map.Entry currentEntry = (Map.Entry) i.next();
            final String repeatId = (String) currentEntry.getKey();

            if (updatedIndexesIds.get(repeatId) == null) {
                currentControlsState.updateRepeatIndex(repeatId, 0);
            }
        }
    }

    /**
     * Adjust repeat indexes after an insertion.
     *
     * @param pipelineContext
     * @param xformsControls
     * @param currentControlsState
     * @param clonedNodes
     */
    public static void adjustIndexesAfterInsert(PipelineContext pipelineContext, final XFormsControls xformsControls,
                                               final XFormsControls.ControlsState currentControlsState, final List clonedNodes) {

        // NOTE: The code below assumes that there are no nested repeats bound to node-sets that intersect
        currentControlsState.visitControlsFollowRepeats(pipelineContext, xformsControls, new XFormsControls.XFormsControlVisitorListener() {

            private XFormsControl foundXFormsControl;

            public void startVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    // Found an xforms:repeat
                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) XFormsControl;
                    final String repeatId = repeatControlInfo.getOriginalId();
                    final List repeatNodeSet = xformsControls.getCurrentNodeset();

                    if (foundXFormsControl == null) {
                        // We are not yet inside a matching xforms:repeat

                        if (repeatNodeSet != null && repeatNodeSet.size() > 0) {
                            // Find whether one node of the repeat node-set contains one of the inserted nodes

                            int newRepeatIndex = -1;
                            {
                                int clonedNodesIndex = -1;
                                int currentRepeatIndex = 1;
                                for (Iterator i = repeatNodeSet.iterator(); i.hasNext(); currentRepeatIndex++) {
                                    final NodeInfo currentNodeInfo = (NodeInfo) i.next();
                                    if (currentNodeInfo instanceof NodeWrapper) { // underlying node can't match if it's not a NodeWrapper
                                        final Node currentNode = (Node) ((NodeWrapper) currentNodeInfo).getUnderlyingNode();

                                        final int currentNodeIndex = clonedNodes.indexOf(currentNode);

                                        if (currentNodeIndex != -1) {
                                            // This node of the repeat node-set points to an inserted node
                                            if (currentNodeIndex > clonedNodesIndex) {
                                                clonedNodesIndex = currentNodeIndex; // prefer nodes inserted last
                                                newRepeatIndex = currentRepeatIndex;
                                            }

                                            // Stop if this node of the repeat node-set points to the last inserted node
                                            if (clonedNodesIndex == clonedNodes.size() - 1)
                                                break;
                                        }
                                    }
                                }
                            }

                            if (newRepeatIndex != -1) {

                                // This xforms:repeat affected by the change

                                // "The index for any repeating sequence that is bound
                                // to the homogeneous collection where the node was
                                // added is updated to point to the newly added node."
                                currentControlsState.updateRepeatIndex(repeatId, newRepeatIndex);

                                // First step: set all children indexes to 0
                                final List nestedRepeatIds = currentControlsState.getNestedRepeatIds(xformsControls, repeatId);
                                if (nestedRepeatIds != null) {
                                    for (Iterator j = nestedRepeatIds.iterator(); j.hasNext();) {
                                        final String nestedRepeatId = (String) j.next();
                                        currentControlsState.updateRepeatIndex(nestedRepeatId, 0);
                                    }
                                }

                                foundXFormsControl = XFormsControl;
                            }

                            if (foundXFormsControl == null) {
                                // Still not found a control. Make sure the bounds of this
                                // xforms:repeat are correct for the rest of the visit.

                                final int adjustedNewIndex;
                                {
                                    final int newIndex = ((Integer) currentControlsState.getRepeatIdToIndex().get(repeatId)).intValue();

                                    // Adjust bounds if necessary
                                    if (newIndex < 1)
                                        adjustedNewIndex = 1;
                                    else if (newIndex > repeatNodeSet.size())
                                        adjustedNewIndex = repeatNodeSet.size();
                                    else
                                        adjustedNewIndex = newIndex;
                                }

                                // Set index
                                currentControlsState.updateRepeatIndex(repeatId, adjustedNewIndex);
                            }

                        } else {
                            // Make sure the index is set to zero when the node-set is empty
                            currentControlsState.updateRepeatIndex(repeatId, 0);
                        }
                    } else {
                        // This is a child xforms:repeat of a matching xforms:repeat
                        // Second step: update non-empty repeat indexes to the appropriate value

                        // "The indexes for inner nested repeat collections are re-initialized to startindex."

                        // NOTE: We do this, but we also adjust the index:
                        // "The index for this repeating structure is initialized to the
                        // value of startindex. If the initial startindex is less than 1 it
                        // defaults to 1. If the index is greater than the initial node-set
                        // then it defaults to the size of the node-set."

                        if (repeatNodeSet != null && repeatNodeSet.size() > 0) {
                            int newIndex = repeatControlInfo.getStartIndex();

                            if (newIndex < 1)
                                newIndex = 1;
                            if (newIndex > repeatNodeSet.size())
                                newIndex = repeatNodeSet.size();

                            currentControlsState.updateRepeatIndex(repeatId, newIndex);
                        } else {
                            // Make sure the index is set to zero when the node-set is empty
                            // (although this should already have been done above by the
                            // enclosing xforms:repeat)
                            currentControlsState.updateRepeatIndex(repeatId, 0);
                        }
                    }
                }
            }

            public void endVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    if (foundXFormsControl == XFormsControl)
                        foundXFormsControl = null;
                }
            }
        });
    }

    /**
     * Adjust indexes after a deletion.
     *
     * @param pipelineContext
     * @param xformsControls
     * @param previousRepeatIdToIndex
     * @param repeatIndexUpdates
     * @param nestedRepeatIndexUpdates
     * @param nodeToRemove
     */
    public static void adjustIndexesForDelete(PipelineContext pipelineContext, final XFormsControls xformsControls,
                                              final Map previousRepeatIdToIndex, final Map repeatIndexUpdates,
                                              final Map nestedRepeatIndexUpdates, final Node nodeToRemove) {

        // NOTE: The code below assumes that there are no nested repeats bound to node-sets that intersect
        xformsControls.getCurrentControlsState().visitControlsFollowRepeats(pipelineContext, xformsControls, new XFormsControls.XFormsControlVisitorListener() {

            private XFormsControl foundXFormsControl;
            private boolean reinitializeInner;

            public void startVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    // Found an xforms:repeat
                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) XFormsControl;
                    final String repeatId = repeatControlInfo.getOriginalId();

                    final List repeatNodeSet = xformsControls.getCurrentNodeset();
                    if (foundXFormsControl == null) {
                        // We are not yet inside a matching xforms:repeat

                        if (repeatNodeSet != null && repeatNodeSet.size() > 0) {
                            // Find whether one node of the repeat node-set contains the inserted node
                            for (Iterator i = repeatNodeSet.iterator(); i.hasNext();) {
                                final NodeInfo currentNode = (NodeInfo) i.next();
                                if ((currentNode instanceof NodeWrapper) // underlying node can't match if it's not a NodeWrapper
                                        && ((NodeWrapper) currentNode).getUnderlyingNode() == nodeToRemove) {
                                    // Found xforms:repeat affected by the change

                                    final int newIndex;
                                    if (repeatNodeSet.size() == 1) {
                                        // Delete the last element of the collection: the index must be set to 0
                                        newIndex = 0;
                                        reinitializeInner = false;
                                    } else {
                                        // Current index for this repeat
                                        final int currentIndex = ((Integer) previousRepeatIdToIndex.get(repeatId)).intValue();

                                        // Index of deleted element for this repeat
                                        final int deletionIndexInRepeat = repeatNodeSet.indexOf(nodeToRemove) + 1;

                                        if (currentIndex == deletionIndexInRepeat) {
                                            if (deletionIndexInRepeat == repeatNodeSet.size()) {

                                                // o "When the last remaining item in the collection is removed,
                                                // the index position becomes 0."

                                                // o "When the index was pointing to the deleted node, which was
                                                // the last item in the collection, the index will point to the new
                                                // last node of the collection and the index of inner repeats is
                                                // reinitialized."

                                                newIndex = currentIndex - 1;
                                                reinitializeInner = true;
                                            } else {
                                                // o "When the index was pointing to the deleted node, which was
                                                // not the last item in the collection, the index position is not
                                                // changed and the index of inner repeats is re-initialized."

                                                newIndex = currentIndex;
                                                reinitializeInner = true;
                                            }
                                        } else {
                                            // "The index should point to the same node
                                            // after a delete as it did before the delete"

                                            if (currentIndex < deletionIndexInRepeat) {
                                                newIndex = currentIndex;
                                            } else {
                                                newIndex = currentIndex - 1;
                                            }
                                            reinitializeInner = false;
                                        }
                                    }

                                    repeatIndexUpdates.put(repeatId, new Integer(newIndex));

                                    // Handle children
                                    if (reinitializeInner) {
                                        // First step: set all children indexes to 0
                                        final List nestedRepeatIds = xformsControls.getCurrentControlsState().getNestedRepeatIds(xformsControls, repeatId);
                                        if (nestedRepeatIds != null) {
                                            for (Iterator j = nestedRepeatIds.iterator(); j.hasNext();) {
                                                final String nestedRepeatId = (String) j.next();
                                                repeatIndexUpdates.put(nestedRepeatId, new Integer(0));
                                                nestedRepeatIndexUpdates.put(nestedRepeatId, "");
                                            }
                                        }
                                    }

                                    foundXFormsControl = XFormsControl;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            public void endVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    if (foundXFormsControl == XFormsControl)
                        foundXFormsControl = null;
                }
            }
        });
    }

    /**
     * Adjust controls ids that could have gone out of bounds.
     *
     * What we do here is that we bring back the index within bounds. The spec does not cover this
     * scenario.
     */
    public static void adjustRepeatIndexes(PipelineContext pipelineContext, final XFormsControls xformsControls, final Map forceUpdate) {

        // We don't rebuild before iterating because the caller has already rebuilt
        final XFormsControls.ControlsState currentControlsState = xformsControls.getCurrentControlsState();
        currentControlsState.visitControlsFollowRepeats(pipelineContext, xformsControls, new XFormsControls.XFormsControlVisitorListener() {

            public void startVisitControl(XFormsControl XFormsControl) {
                if (XFormsControl instanceof XFormsRepeatControl) {
                    // Found an xforms:repeat
                    final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) XFormsControl;
                    final String repeatId = repeatControlInfo.getOriginalId();

                    final List repeatNodeSet = xformsControls.getCurrentNodeset();

                    if (repeatNodeSet != null && repeatNodeSet.size() > 0) {
                        // Node-set is non-empty

                        final int adjustedNewIndex;
                        {
                            final int newIndex;
                            if (forceUpdate != null && forceUpdate.get(repeatId) != null) {
                                // Force update of index to start index
                                newIndex = repeatControlInfo.getStartIndex();

                                // NOTE: XForms 1.0 2nd edition actually says "To re-initialize
                                // a repeat means to change the index to 0 if it is empty,
                                // otherwise 1." However, for, xforms:insert, we are supposed to
                                // update to startindex. Here, for now, we decide to use
                                // startindex for consistency.
                                // TODO: check latest errata

                            } else {
                                // Just use current index
                                newIndex = ((Integer) currentControlsState.getRepeatIdToIndex().get(repeatId)).intValue();
                            }

                            // Adjust bounds if necessary
                            if (newIndex < 1)
                                adjustedNewIndex = 1;
                            else if (newIndex > repeatNodeSet.size())
                                adjustedNewIndex = repeatNodeSet.size();
                            else
                                adjustedNewIndex = newIndex;
                        }

                        // Set index
                        currentControlsState.updateRepeatIndex(repeatId, adjustedNewIndex);

                    } else {
                        // Node-set is empty, make sure index is set to 0
                        currentControlsState.updateRepeatIndex(repeatId, 0);
                    }
                }
            }

            public void endVisitControl(XFormsControl XFormsControl) {
            }
        });
    }
}
