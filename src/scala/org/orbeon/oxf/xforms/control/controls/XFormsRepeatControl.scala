/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.control.controls

import org.apache.commons.lang.StringUtils
import org.dom4j.Element
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.xforms._
import analysis.controls.{RepeatIterationControl, RepeatControl}
import analysis.ElementAnalysis
import control._
import org.orbeon.oxf.xforms.action.actions.XFormsDeleteAction
import org.orbeon.oxf.xforms.action.actions.XFormsInsertAction
import org.orbeon.oxf.xforms.event.XFormsEvent
import org.orbeon.oxf.xforms.event.XFormsEvents
import org.orbeon.oxf.xforms.event.events.XXFormsDndEvent
import org.orbeon.oxf.xforms.event.events.XXFormsIndexChangedEvent
import org.orbeon.oxf.xforms.event.events.XXFormsNodesetChangedEvent
import org.orbeon.oxf.xforms.event.events.XXFormsSetindexEvent
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.saxon.om.Item
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.oxf.xforms.XFormsConstants._

import control.controls.XFormsRepeatControl._
import java.util.{ArrayList, List ⇒ JList, Map ⇒ JMap, Collections}
import collection.JavaConverters._
import org.orbeon.oxf.xforms.BindingContext
import collection.mutable.{ArrayBuffer, LinkedHashMap}

// Represents an xforms:repeat container control.
class XFormsRepeatControl(container: XBLContainer, parent: XFormsControl, element: Element, effectiveId: String, state: JMap[String, String])
        extends XFormsNoSingleNodeContainerControl(container, parent, element, effectiveId)
        with NoLHHATrait {

    // TODO: this must be handled following the same pattern as usual refresh events
    private var refreshInfo: RefreshInfo = null

    // Initial local state
    setLocal(new XFormsRepeatControlLocal)

    // Restore state if needed
    @transient
    private var restoredState =
        if (state ne null) {
            // NOTE: Don't use setIndex() as we don't want to cause initialLocal != currentLocal
            val local = getCurrentLocal.asInstanceOf[XFormsRepeatControlLocal]
            local.index = state.get("index").toInt
            true
        } else
            false

    // Store initial repeat index information
    private val startIndexString = element.attributeValue("startindex")
    private val startIndex = Option(startIndexString) map (_.toInt) getOrElse  1
    def getStartIndex = startIndex

    override def staticControl = super.staticControl.asInstanceOf[RepeatControl]
    override def supportsRefreshEvents = true
    override val getAllowedExternalEvents = Set(XFormsEvents.XXFORMS_DND).asJava
    override def children = super.children.asInstanceOf[Seq[XFormsRepeatIterationControl]]

    // If there is DnD, must tell the client to perform initialization
    override def getJavaScriptInitialization =
        if (isDnD) getCommonJavaScriptInitialization else null

    override def onCreate() {
        super.onCreate()

        // Ensure that the initial state is set, either from default value, or for state deserialization.
        if (! restoredState)
            setIndexInternal(getStartIndex)
        else
            // NOTE: state deserialized → state previously serialized → control was relevant → onCreate() called
            restoredState = false

        // Reset refresh information
        refreshInfo = null
    }

    /**
     * Set the repeat index. The index is automatically adjusted to fall within bounds.
     *
     * @param index             new repeat index
     */
    def setIndex(index: Int) {

        val oldRepeatIndex = getIndex // 1-based

        // Set index
        setIndexInternal(index)
        if (oldRepeatIndex != getIndex) {
            // Dispatch custom event to notify that the repeat index has changed
            container.dispatchEvent(new XXFormsIndexChangedEvent(containingDocument, this, oldRepeatIndex, getIndex))
        }

        // Handle rebuild flags for container affected by changes to this repeat
        val resolutionScopeContainer = container.findResolutionScope(getPrefixedId)
        resolutionScopeContainer.setDeferredFlagsForSetindex()
    }

    private def setIndexInternal(index: Int) {
        val local = getLocalForUpdate.asInstanceOf[XFormsRepeatControl.XFormsRepeatControlLocal]
        local.index = ensureIndexBounds(index)
    }

    private def ensureIndexBounds(index: Int) =
        math.min(math.max(index, if (getSize > 0) 1 else 0), getSize)


    override def getSize =
        // Return the size based on the nodeset size, so we can call this before all iterations have been added.
        // Scenario:
        // o call index() or xxf:index() from within a variable within the iteration:
        // o not all iterations have been added, but the size must be known
        // NOTE: This raises an interesting question about the relevance of iterations. As of 2009-12-04, not sure
        // how we handle that!
        Option(getBindingContext) map (_.nodeset.size) getOrElse  0

    def getIndex =
        if (isRelevant) {
            val local = getCurrentLocal.asInstanceOf[XFormsRepeatControl.XFormsRepeatControlLocal]
            if (local.index != -1)
                local.index
            else
                throw new OXFException("Repeat index was not set for repeat id: " + getEffectiveId)
        } else
            0

    // Return the iteration corresponding to the current index if any, null otherwise
    def getIndexIteration =
        if (children.isEmpty || getIndex < 1)
            null
        else
            children(getIndex - 1)

    def doDnD(dndEvent: XXFormsDndEvent) {
        // Only support this on DnD-enabled controls
        if (! isDnD)
            throw new ValidationException("Attempt to process xxforms-dnd event on non-DnD-enabled control: " + getEffectiveId, getLocationData)

        // Get all repeat iteration details
        val dndStart = StringUtils.split(dndEvent.getDndStart, '-')
        val dndEnd = StringUtils.split(dndEvent.getDndEnd, '-')

        // Find source information
        val (sourceNodeset, requestedSourceIndex) =
            (getBindingContext.getNodeset, Integer.parseInt(dndStart(dndStart.length - 1)))

        if (requestedSourceIndex < 1 || requestedSourceIndex > sourceNodeset.size)
            throw new ValidationException("Out of range Dnd start iteration: " + requestedSourceIndex, getLocationData)

        // Find destination
        val (destinationNodeset, requestedDestinationIndex) = {
            val destinationControl =
            if (dndEnd.length > 1) {
                // DnD destination is a different repeat control
                val containingRepeatEffectiveId = getPrefixedId + REPEAT_HIERARCHY_SEPARATOR_1 + (dndEnd mkString REPEAT_HIERARCHY_SEPARATOR_2_STRING)
                containingDocument.getObjectByEffectiveId(containingRepeatEffectiveId).asInstanceOf[XFormsRepeatControl]
            } else
                // DnD destination is the current repeat control
                this

            (new ArrayList[Item](destinationControl.getBindingContext.getNodeset), dndEnd(dndEnd.length - 1).toInt)
        }

        // TODO: Detect DnD over repeat boundaries, and throw if not explicitly enabled

        // Delete node from source
        // NOTE: don't dispatch event, because one call to updateRepeatNodeset() is enough
        val deletedNodeInfo = {
            // This deletes exactly one node
            val deleteInfos = XFormsDeleteAction.doDelete(containingDocument, containingDocument.getControls.getIndentedLogger, sourceNodeset, requestedSourceIndex, false)
            deleteInfos.get(0).nodeInfo
        }


        // Adjust destination collection to reflect new state
        val deletedNodePosition = destinationNodeset.indexOf(deletedNodeInfo)
        val (actualDestinationIndex, destinationPosition) =
            if (deletedNodePosition != -1) {
                // Deleted node was part of the destination nodeset
                // NOTE: This removes from our copy of the nodeset, not from the control's nodeset, which must not be touched until control bindings are updated
                destinationNodeset.remove(deletedNodePosition)
                // If the insertion position is after the delete node, must adjust it
                if (requestedDestinationIndex <= deletedNodePosition + 1)
                    // Insertion point is before or on (degenerate case) deleted node
                    (requestedDestinationIndex, "before")
                else
                    // Insertion point is after deleted node
                    (requestedDestinationIndex - 1, "after")
            } else {
                // Deleted node was not part of the destination nodeset
                if (requestedDestinationIndex <= destinationNodeset.size)
                    // Position within nodeset
                    (requestedDestinationIndex, "before")
                else
                    // Position at the end of the nodeset
                    (requestedDestinationIndex - 1, "after")
            }

        // Insert nodes into destination
        val insertContextNodeInfo = deletedNodeInfo.getParent
        // NOTE: Tell insert to not clone the node, as we know it is ready for insertion
        XFormsInsertAction.doInsert(
            containingDocument,
            containingDocument.getControls.getIndentedLogger,
            destinationPosition,
            destinationNodeset,
            insertContextNodeInfo,
            Collections.singletonList(deletedNodeInfo),
            actualDestinationIndex,
            false,
            true
        )

        // TODO: should dispatch xxforms-move instead of xforms-insert?
    }

    def isDnD = {
        val dndAttribute = getControlElement.attributeValue(XXFORMS_DND_QNAME)
        dndAttribute != null && dndAttribute != "none"
    }

    override protected def pushBindingImpl(parentContext: BindingContext) = {
        // Do this before pushBinding() because after that controls are temporarily in an inconsistent state
        containingDocument.getControls.cloneInitialStateIfNeeded()

        super.pushBindingImpl(parentContext)
    }

    def updateNodesetForInsertDelete(insertedNodeInfos: JList[Item]): Unit = {

        // Get old nodeset
        val oldRepeatNodeset = getBindingContext.getNodeset

        val focusedBefore = containingDocument.getControls.getFocusedControl

        // Set new binding context on the repeat control
        locally {
            // NOTE: here we just reevaluate against the parent; maybe we should reevaluate all the way down
            val contextStack = container.getContextStack
            if (getBindingContext.parent eq null)
                // This might happen at the top-level if there is no model and no variables in scope?
                contextStack.resetBindingContext
            else {
                contextStack.setBinding(getBindingContext)
                // If there are some preceding variables in scope, the top of the stack is now the last scoped variable
                contextStack.popBinding
            }

            pushBinding(contextStack.getCurrentBindingContext, update = true)
        }

        // Move things around and create new iterations if needed
        if (! Controls.compareNodesets(oldRepeatNodeset, getBindingContext.getNodeset)) {
            // Update iterationsInitialStateIfNeeded()

            val newIterations = updateIterations(oldRepeatNodeset, insertedNodeInfos, isInsertDelete = true)

            // Evaluate all controls and then dispatches creation events
            val currentControlTree = containingDocument.getControls.getCurrentControlTree
            for (newIteration ← newIterations)
                currentControlTree.initializeSubTree(newIteration, true)

            // This will dispatch xforms-enabled/xforms-disabled/xxforms-nodeset-changed/xxforms-index-changed events if needed
            containingDocument.getControls.getCurrentControlTree.dispatchRefreshEvents(Collections.singletonList(getEffectiveId))
        }

        // Handle focus changes
        Focus.updateFocus(focusedBefore)
    }

    /**
     * Update this repeat's iterations given the old and new node-sets, and a list of inserted nodes if any (used for
     * index updates). This returns a list of entirely new repeat iterations added, if any. The repeat's index is
     * adjusted.
     *
     * NOTE: The new binding context must have been set on this control before calling.
     *
     * @param oldRepeatItems        old items
     * @param insertedItems         items just inserted by xforms:insert if any, or null
     * @return                      new iterations if any, or an empty list
     */
    def updateIterations(oldRepeatItems: JList[Item], insertedItems: JList[Item], isInsertDelete: Boolean): Seq[XFormsRepeatIterationControl] = {

        // NOTE: The following assumes the nodesets have changed

        val controls = containingDocument.getControls

        // Get current (new) nodeset
        val newRepeatNodeset = getBindingContext.getNodeset

        val isInsert = insertedItems ne null

        val currentControlTree = controls.getCurrentControlTree

        val indentedLogger = controls.getIndentedLogger
        val isDebugEnabled = indentedLogger.isDebugEnabled

        val oldRepeatIndex = getIndex// 1-based
        var updated = false

        val (newIterations, movedIterationsOldPositions, movedIterationsNewPositions) =
            if (! newRepeatNodeset.isEmpty) {

                // For each new node, what its old index was, -1 if it was not there
                val oldIndexes = findNodeIndexes(newRepeatNodeset, oldRepeatItems)

                // For each old node, what its new index is, -1 if it is no longer there
                val newIndexes = findNodeIndexes(oldRepeatItems, newRepeatNodeset)

                // Remove control information for iterations that move or just disappear
                val oldChildren = children

                for (i ← 0 to newIndexes.length - 1) {
                    val currentNewIndex = newIndexes(i)
                    if (currentNewIndex != i) {
                        // Node has moved or is removed
                        val isRemoved = currentNewIndex == -1
                        val movedOrRemovedIteration = oldChildren(i)
                        if (isRemoved) {
                            if (isDebugEnabled)
                                indentedLogger.startHandleOperation("xforms:repeat", "removing iteration", "id", getEffectiveId, "index", Integer.toString(i + 1))

                            // Dispatch destruction events
                            currentControlTree.dispatchDestructionEventsForRemovedContainer(movedOrRemovedIteration, true)

                            // Indicate to iteration that it is being removed
                            // As of 2012-03-07, only used by XFormsComponentControl to destroy the XBL container
                            movedOrRemovedIteration.iterationRemoved()
                            if (isDebugEnabled)
                                indentedLogger.endHandleOperation()

                        }

                        // Deindex old iteration
                        currentControlTree.deindexSubtree(movedOrRemovedIteration, true)
                        updated = true
                    }
                }

                // Set new repeat index (do this before creating new iterations so that index is available then)
                val didSetIndex =
                    if (isInsert) {
                        // Insert logic

                        // We want to point to a new node (case of insert)

                        // First, try to point to the last inserted node if found
                        findNodeIndexes(insertedItems, newRepeatNodeset).reverse find (_ != -1) map { index ⇒
                            val newRepeatIndex = index + 1
                            if (isDebugEnabled)
                                indentedLogger.logDebug("xforms:repeat", "setting index to new node", "id", getEffectiveId, "new index", newRepeatIndex.toString)

                            setIndexInternal(newRepeatIndex)
                            true
                        } getOrElse
                            false
                    } else
                        false

                if (! didSetIndex) {
                    // Non-insert logic (covers delete and other arbitrary changes to the repeat sequence)

                    // Try to point to the same node as before
                    if (oldRepeatIndex > 0 && oldRepeatIndex <= newIndexes.length && newIndexes(oldRepeatIndex - 1) != -1) {
                        // The index was pointing to a node which is still there, so just move the index
                        val newRepeatIndex = newIndexes(oldRepeatIndex - 1) + 1
                        if (newRepeatIndex != oldRepeatIndex) {
                            if (isDebugEnabled)
                                indentedLogger.logDebug("xforms:repeat", "adjusting index for existing node", "id", getEffectiveId,
                                    "old index", oldRepeatIndex.toString, "new index", newRepeatIndex.toString)

                            setIndexInternal(newRepeatIndex)
                        }
                    } else if (oldRepeatIndex > 0 && oldRepeatIndex <= newIndexes.length) {
                        // The index was pointing to a node which has been removed
                        if (oldRepeatIndex > newRepeatNodeset.size) {
                            // "if the repeat index was pointing to one of the deleted repeat items, and if the new size of
                            // the collection is smaller than the index, the index is changed to the new size of the
                            // collection."

                            if (isDebugEnabled)
                                indentedLogger.logDebug("xforms:repeat", "setting index to the size of the new nodeset",
                                    "id", getEffectiveId, "new index", newRepeatNodeset.size.toString)

                            setIndexInternal(newRepeatNodeset.size)
                        } else {
                            // "if the new size of the collection is equal to or greater than the index, the index is not
                            // changed"
                            // NOP
                        }
                    } else {
                        // Old index was out of bounds?
                        setIndexInternal(getStartIndex)
                        if (isDebugEnabled)
                            indentedLogger.logDebug("xforms:repeat", "resetting index", "id", getEffectiveId, "new index", getIndex.toString)
                    }
                }

                // Iterate over new nodeset to move or add iterations
                val newSize = newRepeatNodeset.size
                val newChildren = new ArrayBuffer[XFormsControl](newSize)
                val newIterations = new ArrayBuffer[XFormsRepeatIterationControl]
                val movedIterationsOldPositions = new ArrayList[java.lang.Integer]
                val movedIterationsNewPositions = new ArrayList[java.lang.Integer]

                for (repeatIndex ← 1 to newSize) {
                    val currentOldIndex = oldIndexes(repeatIndex - 1)
                    if (currentOldIndex == -1) {
                        // This new node was not in the old nodeset so create a new one
                        if (isDebugEnabled)
                            indentedLogger.startHandleOperation("xforms:repeat", "creating new iteration", "id", getEffectiveId, "index", repeatIndex.toString)

                        // Create repeat iteration
                        val newIteration = controls.createRepeatIterationTree(this, repeatIndex)
                        updated = true

                        newIterations += newIteration

                        if (isDebugEnabled)
                            indentedLogger.endHandleOperation()

                        // Add new iteration
                        newChildren += newIteration
                    } else {
                        // This new node was in the old nodeset so keep it

                        val existingIteration = oldChildren(currentOldIndex)
                        val newIterationOldIndex = existingIteration.iterationIndex
                        if (newIterationOldIndex != repeatIndex) {
                            // Iteration index changed
                            if (isDebugEnabled)
                                indentedLogger.logDebug("xforms:repeat", "moving iteration", "id", getEffectiveId,
                                    "old index", newIterationOldIndex.toString, "new index", repeatIndex.toString)

                            // Set new index
                            existingIteration.setIterationIndex(repeatIndex)

                            // Update iteration bindings
                            // NOTE: We used to only update the binding on the iteration itself
                            if (isInsertDelete)
                                Controls.updateBindings(existingIteration)

                            // Index iteration
                            currentControlTree.indexSubtree(existingIteration, true)
                            updated = true

                            // Add information for moved iterations
                            movedIterationsOldPositions.add(newIterationOldIndex)
                            movedIterationsNewPositions.add(repeatIndex)
                        } else {
                            // Iteration index stayed the same

                            // Update iteration bindings
                            // NOTE: We used to only update the binding on the iteration itself
                            if (isInsertDelete)
                                Controls.updateBindings(existingIteration)
                        }

                        // Add existing iteration
                        newChildren += existingIteration
                    }
                }

                // Set the new children iterations
                setChildren(newChildren)

                (newIterations, movedIterationsOldPositions, movedIterationsNewPositions)
            } else {
                // New repeat nodeset is now empty

                // Remove control information for iterations that disappear
                for (removedIteration ← children) {

                    if (isDebugEnabled)
                        indentedLogger.startHandleOperation("xforms:repeat", "removing iteration", "id", getEffectiveId,
                            "index", removedIteration.iterationIndex.toString)

                    // Dispatch destruction events and deindex old iteration
                    currentControlTree.dispatchDestructionEventsForRemovedContainer(removedIteration, true)
                    currentControlTree.deindexSubtree(removedIteration, true)

                    if (isDebugEnabled)
                        indentedLogger.endHandleOperation()

                    updated = true
                }

                if (isDebugEnabled)
                    if (getIndex != 0)
                        indentedLogger.logDebug("xforms:repeat", "setting index to 0", "id", getEffectiveId)

                clearChildren()
                setIndexInternal(0)

                (Seq.empty, Collections.emptyList[java.lang.Integer], Collections.emptyList[java.lang.Integer])
            }

        if (updated || oldRepeatIndex != getIndex) {
            // Keep information available until refresh events are dispatched, which must happen soon after this method was called
            refreshInfo = new RefreshInfo
            if (updated) {
                refreshInfo.isNodesetChanged = true
                refreshInfo.newIterations = newIterations
                refreshInfo.movedIterationsOldPositions = movedIterationsOldPositions
                refreshInfo.movedIterationsNewPositions = movedIterationsNewPositions
            }
            refreshInfo.oldRepeatIndex = oldRepeatIndex
        }
        else
            refreshInfo = null

        newIterations
    }

    def dispatchRefreshEvents() {
        if (isRelevant && (refreshInfo ne null)) {
            val refreshInfo = this.refreshInfo
            this.refreshInfo = null
            if (refreshInfo.isNodesetChanged) {
                // Dispatch custom event to xforms:repeat to notify that the nodeset has changed
                container.dispatchEvent(new XXFormsNodesetChangedEvent(containingDocument, this, refreshInfo.newIterations.asJava,
                    refreshInfo.movedIterationsOldPositions, refreshInfo.movedIterationsNewPositions))
            }

            if (refreshInfo.oldRepeatIndex != getIndex) {
                // Dispatch custom event to notify that the repeat index has changed
                container.dispatchEvent(new XXFormsIndexChangedEvent(containingDocument, this, refreshInfo.oldRepeatIndex, getIndex))
            }
        }
    }

    private def findNodeIndexes(nodeset1: JList[Item], nodeset2: JList[Item]) = {

        val nodeset2Scala = nodeset2.asScala

        def indexOfItem(otherItem: Item) =
            nodeset2Scala indexWhere (XFormsUtils.compareItems(_, otherItem))

        nodeset1.asScala map (indexOfItem(_)) toArray
    }

    // Serialize index
    override def serializeLocal: JMap[String, String] =
        Collections.singletonMap("index", Integer.toString(getIndex))

    // "4.3.7 The xforms-focus Event [...] Setting focus to a repeat container form control sets the focus to the
    // repeat object  associated with the repeat index"
    override def setFocus() =
        if (getIndex > 0)
            children(getIndex - 1).setFocus()
        else
            false

    override def computeRelevant: Boolean = {

        // If parent is not relevant then we are not relevant either
        if (! super.computeRelevant)
            return false

        for (currentItem ← bindingContext.getNodeset.asScala) {
            // If bound to non-node, consider as relevant (e.g. nodeset="(1 to 10)")
            if (! currentItem.isInstanceOf[NodeInfo])
                return true

            // Bound to node and node is relevant
            val currentNodeInfo = currentItem.asInstanceOf[NodeInfo]
            if (InstanceData.getInheritedRelevant(currentNodeInfo))
                return true
        }

        // No item was relevant so we are not relevant either
        false
    }

    override def performDefaultAction(event: XFormsEvent) = event match {
        case e: XXFormsSetindexEvent ⇒
            // Set the index if we receive this event
            setIndex(e.index)
        case _ ⇒
            super.performDefaultAction(event)
    }

    override def buildChildren(buildTree: (XBLContainer, BindingContext, ElementAnalysis, Seq[Int]) ⇒ Option[XFormsControl], idSuffix: Seq[Int]) = {

        // Build all children that are not repeat iterations
        Controls.buildChildren(this, staticControl.children filterNot (_.isInstanceOf[RepeatIterationControl]), buildTree, idSuffix)

        // Build one sub-tree per repeat iteration (iteration itself handles its own binding with pushBinding, depending on its index/suffix)
        val iterationAnalysis = staticControl.iteration.get
        for (iterationIndex ← 1 to getBindingContext.getNodeset.size)
            buildTree(container, getBindingContext, iterationAnalysis, idSuffix :+ iterationIndex)

        // TODO LATER: handle isOptimizeRelevance()
    }
}

object XFormsRepeatControl {

    class XFormsRepeatControlLocal extends ControlLocalSupport.XFormsControlLocal {
        var index = -1
    }

    private class RefreshInfo {
        var isNodesetChanged: Boolean = false
        var newIterations: Seq[XFormsRepeatIterationControl] = null
        var movedIterationsOldPositions: JList[java.lang.Integer] = null
        var movedIterationsNewPositions: JList[java.lang.Integer] = null
        var oldRepeatIndex: Int = 0
    }

    // Find the initial repeat indexes for the given tree
    def findInitialIndexes(doc: XFormsContainingDocument, tree: ControlTree) =
        findIndexes(doc, tree, _.getInitialLocal.asInstanceOf[XFormsRepeatControlLocal].index)

    // Find the current repeat indexes for the given tree
    def findCurrentIndexes(doc: XFormsContainingDocument, tree: ControlTree) =
        findIndexes(doc, tree, _.getIndex)

    private def findIndexes(doc: XFormsContainingDocument, tree: ControlTree, index: XFormsRepeatControl ⇒ Int) = {

        // Map prefixed ids to indexes
        val indexes = new LinkedHashMap[String, java.lang.Integer]

        // For each static repeats ordered from root to leaf, find the current index if any
        doc.getStaticOps.repeats foreach { repeat ⇒

            // Build the suffix by gathering all the ancestor repeat's indexes
            val suffix = RepeatControl.getAllAncestorRepeatsAcrossParts(repeat).reverse map
                (ancestor ⇒ indexes(ancestor.prefixedId)) mkString
                    REPEAT_HIERARCHY_SEPARATOR_2_STRING

            val effectiveId = repeat.prefixedId + (if (suffix.length > 0) REPEAT_HIERARCHY_SEPARATOR_1 + suffix else "")

            // Add the index to the map (0 if the control is not found)
            indexes += repeat.prefixedId → {
                tree.getControl(effectiveId) match {
                    case control: XFormsRepeatControl ⇒ index(control)
                    case _ ⇒ 0
                }
            }
        }

        indexes.asJava
    }
}