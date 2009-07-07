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
package org.orbeon.oxf.xforms.control.controls;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xforms.action.actions.XFormsDeleteAction;
import org.orbeon.oxf.xforms.action.actions.XFormsInsertAction;
import org.orbeon.oxf.xforms.control.XFormsContainerControl;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsNoSingleNodeContainerControl;
import org.orbeon.oxf.xforms.event.XFormsEvent;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.event.events.XXFormsDndEvent;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.om.Item;

import java.util.*;

/**
 * Represents an xforms:repeat container control.
 */
public class XFormsRepeatControl extends XFormsNoSingleNodeContainerControl {

    private int startIndex;
    private transient boolean restoredState;  // used by deserializeLocal() and childrenAdded()

    public static class XFormsRepeatControlLocal extends XFormsControlLocal {
        private int index = -1;

        private XFormsRepeatControlLocal() {
        }
        
        public int getIndex() {
            return index;
        }
    }

    public XFormsRepeatControl(XBLContainer container, XFormsControl parent, Element element, String name, String effectiveId) {
        super(container, parent, element, name, effectiveId);

        // Initial local state
        setLocal(new XFormsRepeatControlLocal());

        // Store initial repeat index information
        final String startIndexString = element.attributeValue("startindex");
        this.startIndex = (startIndexString != null) ? Integer.parseInt(startIndexString) : 1;
    }

    public boolean hasJavaScriptInitialization() {
        // If there is DnD, must tell the client to perform initialization
        return isDnD();
    }

    public void childrenAdded(PipelineContext pipelineContext) {
        // This is called once all children have been added

        // Ensure that the initial index is set, unless the state was already restored. In that case, the index was
        // reset from serialized data and the initial index must not be used.
        if (!restoredState) {
            final XFormsRepeatControlLocal local = (XFormsRepeatControlLocal) getCurrentLocal();
            local.index = ensureIndexBounds(getStartIndex());
        }
    }

    public int getStartIndex() {
        return startIndex;
    }

    /**
     * Set the repeat index. The index is automatically adjusted to fall within bounds.
     *
     * @param index  new repeat index
     */
    public void setIndex(int index) {
        final XFormsRepeatControlLocal local = (XFormsRepeatControlLocal) getLocalForUpdate();
        local.index = ensureIndexBounds(index);
    }

    private int ensureIndexBounds(int index) {
        return Math.min(Math.max(index, (getSize() > 0) ? 1 : 0), getSize());
    }

    public int getIndex() {
        final XFormsRepeatControlLocal local = (XFormsRepeatControlLocal) getCurrentLocal();
        if (local.index != -1) {
            return local.index;
        } else {
            throw new OXFException("Repeat index was not set for repeat id: " + getEffectiveId());
        }
    }

    protected void evaluate(PipelineContext pipelineContext) {
        
        // For now, repeat does not support label, help, etc. so don't call super.evaluate()

        // Evaluate iterations
        final List<XFormsControl> children = getChildren();
        if (children != null && children.size() > 0) {
            for (Iterator i = children.iterator(); i.hasNext();) {
                final XFormsRepeatIterationControl currentRepeatIteration = (XFormsRepeatIterationControl) i.next();
                currentRepeatIteration.evaluate(pipelineContext);
            }
        }
    }

    public void performDefaultAction(PipelineContext pipelineContext, XFormsEvent event) {
        if (XFormsEvents.XXFORMS_DND.equals(event.getEventName())) {

            // Only support this on DnD-enabled controls
            if (!isDnD())
                throw new ValidationException("Attempt to process xxforms-dnd event on non-DnD-enabled control: " + getEffectiveId(), getLocationData());

            // Perform DnD operation on node data
            final XXFormsDndEvent dndEvent = (XXFormsDndEvent) event;

            // Get all repeat iteration details
            final String[] dndStart = StringUtils.split(dndEvent.getDndStart(), '-');
            final String[] dndEnd = StringUtils.split(dndEvent.getDndEnd(), '-');

            // Find source information
            final List<Item> sourceNodeset;
            final int requestedSourceIndex;
            {
                sourceNodeset = getBindingContext().getNodeset();
                requestedSourceIndex = Integer.parseInt(dndStart[dndStart.length - 1]);

                if (requestedSourceIndex < 1 || requestedSourceIndex > sourceNodeset.size())
                    throw new ValidationException("Out of range Dnd start iteration: " + requestedSourceIndex, getLocationData());
            }

            // Find destination
            final List<Item> destinationNodeset;
            final int requestedDestinationIndex;
            {
                final XFormsRepeatControl destinationControl;
                if (dndEnd.length > 1) {
                    // DnD destination is a different repeat control
                    final String containingRepeatEffectiveId
                            = getPrefixedId() + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1
                                + StringUtils.join(dndEnd, XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2, 0, dndEnd.length - 1);

                    destinationControl = (XFormsRepeatControl) containingDocument.getObjectByEffectiveId(containingRepeatEffectiveId);
                } else {
                    // DnD destination is the current repeat control
                    destinationControl = this;
                }

                destinationNodeset = new ArrayList<Item>(destinationControl.getBindingContext().getNodeset());
                requestedDestinationIndex = Integer.parseInt(dndEnd[dndEnd.length - 1]);
            }

            // TODO: Detect DnD over repeat boundaries, and throw if not explicitly enabled

            // Delete node from source
            // NOTE: don't dispatch event, because one call to updateRepeatNodeset() is enough
            final List deletedNodes = XFormsDeleteAction.doDelete(pipelineContext, containingDocument, sourceNodeset, requestedSourceIndex, false);
            final NodeInfo deletedNodeInfo = (NodeInfo) deletedNodes.get(0);

            // Adjust destination collection to reflect new state
            final int deletedNodePosition = destinationNodeset.indexOf(deletedNodeInfo);
            final int actualDestinationIndex;
            final String destinationPosition;
            if (deletedNodePosition != -1) {
                // Deleted node was part of the destination nodeset
                // NOTE: This removes from our copy of the nodeset, not from the control's nodeset, which must not be touched until control bindings are updated
                destinationNodeset.remove(deletedNodePosition);
                // If the insertion position is after the delete node, must adjust it
                if (requestedDestinationIndex <= deletedNodePosition + 1) {
                    // Insertion point is before or on (degenerate case) deleted node
                    actualDestinationIndex = requestedDestinationIndex;
                    destinationPosition = "before";
                } else {
                    // Insertion point is after deleted node
                    actualDestinationIndex = requestedDestinationIndex - 1;
                    destinationPosition = "after";
                }
            } else {
                // Deleted node was not part of the destination nodeset
                if (requestedDestinationIndex <= destinationNodeset.size()) {
                    // Position within nodeset
                    actualDestinationIndex = requestedDestinationIndex;
                    destinationPosition = "before";
                } else {
                    // Position at the end of the nodeset
                    actualDestinationIndex = requestedDestinationIndex - 1;
                    destinationPosition = "after";
                }
            }

            // Insert nodes into destination
            final NodeInfo insertContextNodeInfo = deletedNodeInfo.getParent();
            // NOTE: Tell insert to not clone the node, as we know it is ready for insertion
            XFormsInsertAction.doInsert(pipelineContext, containingDocument, destinationPosition, destinationNodeset, insertContextNodeInfo, deletedNodes, actualDestinationIndex, false, true);

            // TODO: should dispatch xxforms-move instead of xforms-insert?

        }
        super.performDefaultAction(pipelineContext, event);
    }

    public boolean isDnD() {
        final String dndAttribute = getControlElement().attributeValue(XFormsConstants.XXFORMS_DND_QNAME);
        return dndAttribute != null && !"none".equals(dndAttribute);
    }

    public void updateNodeset(PipelineContext pipelineContext, List<Item> insertedNodeInfos) {

        // Get old nodeset
        final List<Item> oldRepeatNodeset = getBindingContext().getNodeset();

        // Get new nodeset
        final List<Item> newRepeatNodeset;
        {
            // Set new binding context on the repeat control
            // NOTE: here we just reevaluate against the parent; maybe we should reevaluate all the way down
            final XFormsContextStack contextStack = getXBLContainer().getContextStack();
            final XFormsControl parentControl = getParent();
            if (parentControl == null) {
                // TODO: does this prevent preceding top-level variables from working?
                contextStack.resetBindingContext(pipelineContext);
            } else {
                // NOTE: Don't do setBinding(parent) as that would not keep the variables in scope
                contextStack.setBinding(this);
                contextStack.popBinding();
            }
            contextStack.pushBinding(pipelineContext, getControlElement());
            setBindingContext(pipelineContext, contextStack.getCurrentBindingContext());

            newRepeatNodeset = getBindingContext().getNodeset();
        }

        // Move things around and create new iterations if needed
        if (!compareNodesets(oldRepeatNodeset, newRepeatNodeset)) {
            updateIterations(pipelineContext, oldRepeatNodeset, newRepeatNodeset, insertedNodeInfos);
        }
    }

    /**
     * Update this repeat's iterations given the old and new node-sets, and a list of inserted nodes if any (used for
     * index updates). This returns a list of entirely new repeat iterations added, if any. The repeat's index is
     * adjusted.
     *
     * NOTE: The new binding context must have been set on this control before calling.
     *
     * @param pipelineContext       pipeline context
     * @param oldRepeatNodeset      old node-set
     * @param newRepeatNodeset      new node-set
     * @param insertedNodeInfos     nodes just inserted by xforms:insert if any
     * @return                      List<XFormsRepeatIterationControl> of new iterations if any, or an empty list
     */
    public List updateIterations(PipelineContext pipelineContext, List<Item> oldRepeatNodeset, List<Item> newRepeatNodeset, List<Item> insertedNodeInfos) {

        // NOTE: The following assumes the nodesets have changed

        final XFormsControls controls = containingDocument.getControls();
        controls.cloneInitialStateIfNeeded();

        final boolean isInsert = insertedNodeInfos != null;

        final boolean isDebugEnabled = XFormsServer.logger.isDebugEnabled();
        if (newRepeatNodeset != null && newRepeatNodeset.size() > 0) {
            final ControlTree currentControlTree = controls.getCurrentControlTree();

            // For each new node, what its old index was, -1 if it was not there
            final int[] oldIndexes = findNodeIndexes(newRepeatNodeset, oldRepeatNodeset);

            // For each old node, what its new index is, -1 if it is no longer there
            final int[] newIndexes = findNodeIndexes(oldRepeatNodeset, newRepeatNodeset);

            // Remove control information for iterations that move or just disappear
            final List oldChildren = getChildren();

            for (int i = 0; i < newIndexes.length; i++) {
                final int currentNewIndex = newIndexes[i];

                if (currentNewIndex != i) {
                    // Node has moved or is removed

                    final boolean isRemoved = currentNewIndex == -1;
                    final XFormsRepeatIterationControl removedIteration = (XFormsRepeatIterationControl) oldChildren.get(i);
                    if (isRemoved) {
                        if (isDebugEnabled)
                            containingDocument.logDebug("repeat", "removing iteration", new String[] { "id", getEffectiveId(), "index", Integer.toString(i + 1) });

                        // Indicate to iteration that it is being removed
                        removedIteration.iterationRemoved(pipelineContext);
                    }

                    // Deindex old iteration
                    currentControlTree.deindexSubtree(removedIteration, true, isRemoved);
                }
            }

            // Iterate over new nodeset to move or add iterations
            final int newSize = newRepeatNodeset.size();
            final List<XFormsControl> newChildren = new ArrayList<XFormsControl>(newSize);
            final List<XFormsRepeatIterationControl> newIterations = new ArrayList<XFormsRepeatIterationControl>();
            for (int repeatIndex = 1; repeatIndex <= newSize; repeatIndex++) {// 1-based index

                final XFormsRepeatIterationControl newIteration;
                final int currentOldIndex = oldIndexes[repeatIndex - 1];
                if (currentOldIndex == -1) {
                    // This new node was not in the old nodeset so create a new one

                    if (isDebugEnabled) {
                        containingDocument.logDebug("repeat", "creating new iteration", new String[] { "id", getEffectiveId(), "index", Integer.toString(repeatIndex) });
                    }

                    final XFormsContextStack contextStack = getXBLContainer().getContextStack();
                    contextStack.pushIteration(repeatIndex);
                    newIteration = controls.createRepeatIterationTree(pipelineContext, contextStack.getCurrentBindingContext(), this, repeatIndex);
                    contextStack.popBinding();

                    newIterations.add(newIteration);
                } else {
                    // This new node was in the old nodeset so keep it
                    newIteration = (XFormsRepeatIterationControl) oldChildren.get(currentOldIndex);

                    if (newIteration.getIterationIndex() != repeatIndex) {
                        // Iteration index changed

                        if (isDebugEnabled) {
                            containingDocument.logDebug("repeat", "moving iteration",
                                    new String[] { "id", getEffectiveId(),
                                                   "old index", Integer.toString(newIteration.getIterationIndex()),
                                                   "new index", Integer.toString(repeatIndex) });
                        }

                        // Set new index
                        newIteration.setIterationIndex(repeatIndex);

                        // Index new iteration but do not cause events to be sent
                        currentControlTree.indexSubtree(newIteration, true, false);
                    }
                }

                // Add new iteration
                newChildren.add(newIteration);
            }
            // Set the new children iterations
            setChildren(newChildren);

            // Set new repeat index
            final int oldRepeatIndex = getIndex();// 1-based

            boolean didSetIndex = false;
            if (isInsert) {
                // Insert logic

                // We want to point to a new node (case of insert)

                // First, try to point to the last inserted node if found
                final int[] foobar = findNodeIndexes(insertedNodeInfos, newRepeatNodeset);

                for (int i = foobar.length - 1; i >= 0; i--) {
                    if (foobar[i] != -1) {
                        final int newRepeatIndex = foobar[i] + 1;

                        if (isDebugEnabled) {
                            containingDocument.logDebug("repeat", "setting index to new node",
                                    new String[] { "id", getEffectiveId(), "new index", Integer.toString(newRepeatIndex)});
                        }

                        setIndex(newRepeatIndex);
                        didSetIndex = true;
                        break;
                    }
                }
            }

            if (!didSetIndex) {
                // Delete logic

                // Try to point to the same node as before
                if (oldRepeatIndex > 0 && oldRepeatIndex <= newIndexes.length && newIndexes[oldRepeatIndex - 1] != -1) {
                    // The index was pointing to a node which is still there, so just move the index
                    final int newRepeatIndex = newIndexes[oldRepeatIndex - 1] + 1;

                    if (newRepeatIndex != oldRepeatIndex) {
                        if (isDebugEnabled) {
                            containingDocument.logDebug("repeat", "adjusting index for existing node",
                                    new String[] { "id", getEffectiveId(),
                                                   "old index", Integer.toString(oldRepeatIndex),
                                                   "new index", Integer.toString(newRepeatIndex)});
                        }

                        setIndex(newRepeatIndex);
                    }
                } else if (oldRepeatIndex > 0 && oldRepeatIndex <= newIndexes.length) {
                    // The index was pointing to a node which has been removed

                    if (oldRepeatIndex > newRepeatNodeset.size()) {
                        // "if the repeat index was pointing to one of the deleted repeat items, and if the new size of
                        // the collection is smaller than the index, the index is changed to the new size of the
                        // collection."

                        if (isDebugEnabled) {
                            containingDocument.logDebug("repeat", "setting index to the size of the new nodeset",
                                    new String[] { "id", getEffectiveId(), "new index", Integer.toString(newRepeatNodeset.size())});
                        }

                        setIndex(newRepeatNodeset.size());
                    } else {
                        // "if the new size of the collection is equal to or greater than the index, the index is not
                        // changed"
                        // NOP
                    }
                } else {
                    // Old index was out of bounds?

                    setIndex(getStartIndex());

                    if (isDebugEnabled) {
                        containingDocument.logDebug("repeat", "resetting index",
                                new String[] { "id", getEffectiveId(), "new index", Integer.toString(getIndex())});
                    }
                }
            }

            return newIterations;
        } else {
            // New repeat nodeset is now empty

            final ControlTree currentControlTree = controls.getCurrentControlTree();

            // Remove control information for iterations that disappear
            final List oldChildren = getChildren();
            if (oldChildren != null) {
                for (int i = 0; i < oldChildren.size(); i++) {

                    if (isDebugEnabled) {
                        containingDocument.logDebug("repeat", "removing iteration", new String[] { "id", getEffectiveId(), "index", Integer.toString(i + 1) });
                    }

                    // Deindex old iteration
                    currentControlTree.deindexSubtree((XFormsContainerControl) oldChildren.get(i), true, true);
                }
            }

            if (isDebugEnabled) {
                if (getIndex() != 0)
                    containingDocument.logDebug("repeat", "setting index to 0", new String[] { "id", getEffectiveId() });
            }

            setChildren(null);
            setIndex(0);

            return Collections.EMPTY_LIST;
        }
    }

    private int indexOfNodeInfo(List<Item> nodeset, NodeInfo nodeInfo) {
        int index = 0;
        for (Item currentNodeInfo: nodeset) {
            if (((NodeInfo) currentNodeInfo).isSameNodeInfo(nodeInfo)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private int[] findNodeIndexes(List<Item> nodeset1, List<Item> nodeset2) {
        final int[] result = new int[nodeset1.size()];

        int index = 0;
        for (Iterator i = nodeset1.iterator(); i.hasNext(); index++) {
            final NodeInfo currentNodeInfo = (NodeInfo) i.next();
            result[index] = indexOfNodeInfo(nodeset2, currentNodeInfo);
        }
        return result;
    }

    @Override
    public Map<String, String> serializeLocal() {
        // Serialize index
        return Collections.singletonMap("index", Integer.toString(getIndex()));
    }

    @Override
    public void deserializeLocal(Element element) {
        // Deserialize index

        // NOTE: Don't use setIndex() as we don't want to cause initialLocal != currentLocal
        final XFormsRepeatControlLocal local = (XFormsRepeatControlLocal) getCurrentLocal();
        local.index = Integer.parseInt(element.attributeValue("index"));

        // Indicate to childrenAdded() that default index must not be set
        restoredState = true;
    }
}
