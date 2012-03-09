/**
 *  Copyright (C) 2007 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.control

import controls._
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms._
import analysis.controls.AppearanceTrait
import analysis.ElementAnalysis
import processor.XFormsServer
import xbl.XBLContainer
import org.orbeon.oxf.xforms.BindingContext
import org.orbeon.saxon.om.Item
import collection.JavaConverters._
import org.dom4j.QName
import java.util.{Collections, Map ⇒ JMap, List ⇒ JList}

object Controls {

    // Create the entire tree of control from the root
    def createTree(containingDocument: XFormsContainingDocument, controlIndex: ControlIndex, state: JMap[String, JMap[String, String]]) {

        val bindingContext = containingDocument.getContextStack.resetBindingContext()
        val rootControl = containingDocument.getStaticState.topLevelPart.getTopLevelControls.head

        buildTree(controlIndex, containingDocument, bindingContext, None, rootControl, Seq(), Option(state)) foreach { root ⇒
            if (XFormsProperties.getDebugLogging.contains("control-tree"))
                containingDocument.getControls.getIndentedLogger.logDebug("new control tree", root.toXMLString)
        }
    }

    // Create a new repeat iteration for insertion into the current tree of controls
    def createRepeatIterationTree(
            containingDocument: XFormsContainingDocument,
            controlIndex: ControlIndex,
            repeatControl: XFormsRepeatControl,
            iterationIndex: Int) = {

        val idSuffix = XFormsUtils.getEffectiveIdSuffixParts(repeatControl.getEffectiveId).toSeq :+ iterationIndex

        // This is the context of the iteration
        // buildTree() does a pushBinding(), but that won't change the context (no @ref, etc. on the iteration itself)
        val container = repeatControl.container
        val bindingContext = {
            val contextStack = container.getContextStack
            contextStack.setBinding(repeatControl.getBindingContext)
            contextStack.pushIteration(iterationIndex)
        }

        // This has to be the case at this point, otherwise it's a bug in our code
        assert(repeatControl.staticControl.iteration.isDefined)

        buildTree(
            controlIndex,
            container,
            bindingContext,
            Some(repeatControl),
            repeatControl.staticControl.iteration.get,
            idSuffix,
            None
        ).get.asInstanceOf[XFormsRepeatIterationControl] // we "know" this, right?
    }

    // Create a new subtree of controls (used by xxf:dynamic)
    def createSubTree(
            container: XBLContainer,
            controlIndex: ControlIndex,
            containerControl: XFormsContainerControl,
            rootAnalysis: ElementAnalysis) = {

        val idSuffix = XFormsUtils.getEffectiveIdSuffixParts(containerControl.getEffectiveId).toSeq
        val bindingContext = container.getContextStack.resetBindingContext()

        buildTree(
            controlIndex,
            container,
            bindingContext,
            Some(containerControl),
            rootAnalysis,
            idSuffix,
            None
        )
    }

    // Build a component subtree
    private def buildTree(
            controlIndex: ControlIndex,
            container: XBLContainer,
            bindingContext: BindingContext,
            parentOption: Option[XFormsControl],
            staticElement: ElementAnalysis,
            idSuffix: Seq[Int],
            stateMap: Option[JMap[String, JMap[String, String]]]): Option[XFormsControl] = {

        // Determine effective id
        val effectiveId =
            if (idSuffix.isEmpty)
                staticElement.prefixedId
            else
                staticElement.prefixedId + REPEAT_HIERARCHY_SEPARATOR_1 + (idSuffix mkString REPEAT_HIERARCHY_SEPARATOR_2_STRING)

        // This is used only during state deserialization
        val stateToRestore = stateMap map (_.get(effectiveId))

        // Instantiate the control
        // TODO LATER: controls must take ElementAnalysis, not Element

        // NOTE: If we are unable to create a control (case of Model at least), this has no effect
        Option(XFormsControlFactory.createXFormsControl(container, parentOption.orNull, staticElement.element, effectiveId, stateToRestore.orNull)) map {
            control ⇒
                // Determine binding
                control.evaluateBinding(bindingContext, update = false)

                // Index the new control
                controlIndex.indexControl(control)

                // Build the control's children if any
                control.buildChildren(buildTree(controlIndex, _, _, Some(control), _, _, stateMap), idSuffix)

                control
        }
    }

    // Build children controls if any, delegating the actual construction to the given `buildTree` function
    def buildChildren(
            control: XFormsControl,
            children: ⇒ Iterable[ElementAnalysis],
            buildTree: (XBLContainer, BindingContext, ElementAnalysis, Seq[Int]) ⇒ Option[XFormsControl],
            idSuffix: Seq[Int]) {
        // Start with the context within the current control
        var newBindingContext = control.bindingContextForChild
        // Build each child
        children foreach { childElement ⇒
            buildTree(control.container, newBindingContext, childElement, idSuffix) foreach { newChildControl ⇒
                // Update the context based on the just created control
                newBindingContext = newChildControl.bindingContextForFollowing
            }
        }
    }

    /**
     * Find an effective control id based on a source and a control static id, following XBL scoping and the repeat
     * structure.
     *
     * @param sourceEffectiveId  reference to source control, e.g. "list$age.3"
     * @param targetPrefixedId   reference to target control, e.g. "list$xf-10"
     * @return effective control id, or null if not found
     */
    def findEffectiveControlId(ops: StaticStateGlobalOps, controls: XFormsControls, sourceEffectiveId: String, targetPrefixedId: String): String = {
        
        val tree = controls.getCurrentControlTree 
        
        // Don't do anything if there are no controls
        if (tree.getChildren.isEmpty)
            return null
        
        // NOTE: The implementation tries to do a maximum using the static state. One reason is that the source
        // control's effective id might not yet have an associated control during construction. E.g.:
        //
        // <xf:group id="my-group" ref="employee[index('employee-repeat')]">
        //
        // In that case, the XFormsGroupControl does not yet exist when its binding is evaluated. However, its
        // effective id is known and passed as source, and can be used for resolving the id passed to the index()
        // function.
        //
        // We trust the caller to pass a valid source effective id. That value is always internal, i.e. not created by a
        // form author. On the other hand, the target id cannot be trusted as it is typically specified by the form
        // author.
        
        // 1: Check preconditions
        require(sourceEffectiveId ne null, "Source effective id is required.")
        
        // 3: Implement XForms 1.1 "4.7.1 References to Elements within a repeat Element" algorithm
        
        // Find closest common ancestor repeat

        val sourcePrefixedId = XFormsUtils.getPrefixedId(sourceEffectiveId)
        val sourceParts = XFormsUtils.getEffectiveIdSuffixParts(sourceEffectiveId)

        val targetIndexBuilder = new StringBuilder

        def appendIterationToSuffix(iteration: Int) {
            if (targetIndexBuilder.length == 0)
                targetIndexBuilder.append(REPEAT_HIERARCHY_SEPARATOR_1)
            else if (targetIndexBuilder.length != 1)
                targetIndexBuilder.append(REPEAT_HIERARCHY_SEPARATOR_2)

            targetIndexBuilder.append(iteration.toString)
        }

        val ancestorRepeatPrefixedId = ops.findClosestCommonAncestorRepeat(sourcePrefixedId, targetPrefixedId)

        ancestorRepeatPrefixedId foreach { ancestorRepeatPrefixedId ⇒
            // There is a common ancestor repeat, use the current common iteration as starting point
            for (i ← 0 to ops.getAncestorRepeats(ancestorRepeatPrefixedId).size)
                appendIterationToSuffix(sourceParts(i))
        }

        // Find list of ancestor repeats for destination WITHOUT including the closest ancestor repeat if any
        // NOTE: make a copy because the source might be an immutable wrapped Scala collection which we can't reverse
        val targetAncestorRepeats = ops.getAncestorRepeats(targetPrefixedId, ancestorRepeatPrefixedId).reverse

        // Follow repeat indexes towards target
        for (repeatPrefixedId ← targetAncestorRepeats) {
            val repeatControl = tree.getControl(repeatPrefixedId + targetIndexBuilder.toString).asInstanceOf[XFormsRepeatControl]
            // Control might not exist
            if (repeatControl eq null)
                return null
            // Update iteration suffix
            appendIterationToSuffix(repeatControl.getIndex)
        }

        // Return target
        targetPrefixedId + targetIndexBuilder.toString
    }

    // Update the container's and all its descendants' bindings
    def updateBindings(containerControl: XFormsContainerControl) = {
        val xpathDependencies = containerControl.containingDocument.getXPathDependencies
        xpathDependencies.bindingUpdateStart()
        val updater = new BindingUpdater(containerControl.containingDocument, containerControl.parent.bindingContextForChild)
        visitControls(containerControl, true, updater)
        xpathDependencies.bindingUpdateDone()
        updater
    }

    // Update the bindings for the entire tree of controls
    def updateBindings(containingDocument: XFormsContainingDocument) = {
        val updater = new BindingUpdater(containingDocument, containingDocument.getContextStack.resetBindingContext())
        visitAllControls(containingDocument, updater)
        updater
    }

    class BindingUpdater(val containingDocument: XFormsContainingDocument, val startBindingContext: BindingContext) extends XFormsControlVisitorListener {

        var newIterationsIds = Set.empty[String]

        // Start with initial context
        var bindingContext = startBindingContext
        val xpathDependencies = containingDocument.getXPathDependencies

        var level = 0
        var newlyRelevantLevel = -1

        var visitedCount = 0
        var updateCount = 0
        var optimizedCount = 0

        def startVisitControl(control: XFormsControl): Boolean = {

            // If this is a new iteration, don't recurse into it
            if (newIterationsIds.nonEmpty && control.isInstanceOf[XFormsRepeatIterationControl] && newIterationsIds(control.effectiveId))
                return false

            level += 1
            visitedCount += 1

            // Value of relevance before messing with the binding
            val wasRelevant = control.isRelevant

            // Update is required if:
            //
            // - we are within a newly relevant container
            // - or dependencies tell us an update is required
            // - or the control has a @model attribute (TODO TEMP HACK: because that causes model variable evaluation!)
            def mustReEvaluateBinding =
                (newlyRelevantLevel != -1 && level > newlyRelevantLevel) ||
                xpathDependencies.requireBindingUpdate(control.prefixedId) ||
                (control.staticControl.element.attribute(XFormsConstants.MODEL_QNAME) ne null)

            // Only update the binding if needed
            if (mustReEvaluateBinding) {
                control match {
                    case repeatControl: XFormsRepeatControl ⇒
                        // Update iterations
                        val oldRepeatSeq = control.getBindingContext.getNodeset
                        control.evaluateBinding(bindingContext, update = true)
                        val newIterations = repeatControl.updateIterations(oldRepeatSeq, null, isInsertDelete = false)

                        // Remember newly created iterations so we don't recurse into them in startRepeatIteration()
                        // o It is not needed to recurse into them because their bindings are up to date since they have just been created
                        // o However they have not yet been evaluated. They will be evaluated at the same time the other controls are evaluated
                        // NOTE: don't call ControlTree.initializeRepeatIterationTree() here because refresh evaluates controls and dispatches events
                        newIterationsIds = newIterations map (_.getEffectiveId) toSet
                    case control ⇒
                        // Simply set new binding
                        control.evaluateBinding(bindingContext, update = true)
                }
                updateCount += 1
            } else {
                control.refreshBinding(bindingContext)
                optimizedCount += 1
            }

            // Update context for children controls
            bindingContext = control.bindingContextForChild

            // Remember whether we are in a newly relevant subtree
            val isRelevant = control.isRelevant // determine when binding is set on control
            if (newlyRelevantLevel == -1 && control.isInstanceOf[XFormsContainerControl] && ! wasRelevant && isRelevant)
                newlyRelevantLevel = level // entering level of containing

            true
        }

        def endVisitControl(control: XFormsControl) = {

            // Check if we are exiting the level of a containing control becoming relevant
            if (newlyRelevantLevel == level)
                newlyRelevantLevel = -1

            // Update context for following controls
            bindingContext = control.bindingContextForFollowing

            // When we exit a repeat control, discard the list of new iterations so we don't unnecessarily test on them
            if (control.isInstanceOf[XFormsRepeatControl])
                newIterationsIds = Set.empty[String]

            level -= 1
        }
    }

    // Whether two nodesets contain identical items
    def compareNodesets(nodeset1: JList[Item], nodeset2: JList[Item]): Boolean = {
        // Can't be the same if the size has changed
        if (nodeset1.size != nodeset2.size)
            return false

        val j = nodeset2.iterator
        for (currentItem1 ← nodeset1.asScala) {
            val currentItem2 = j.next
            if (! XFormsUtils.compareItems(currentItem1, currentItem2))
                return false
        }

        true
    }

    // Return the set of appearances for the given element, if any
    def appearances(elementAnalysis: ElementAnalysis) = elementAnalysis match {
        case appearanceTrait: AppearanceTrait ⇒ appearanceTrait.jAppearances
        case _ ⇒ Collections.emptySet[QName]
    }

    // Iterator over a control's ancestors
    class AncestorIterator(start: XFormsControl) extends Iterator[XFormsControl] {
        private var _next = start
        def hasNext = _next ne null
        def next() = {
            val result = _next
            _next = _next.parent
            result
        }
    }

    trait XFormsControlVisitorListener {
        def startVisitControl(control: XFormsControl): Boolean
        def endVisitControl(control: XFormsControl)
    }

    class XFormsControlVisitorAdapter extends XFormsControlVisitorListener {
        def startVisitControl(control: XFormsControl) = true
        def endVisitControl(control: XFormsControl) = ()
    }

    // Visit all the controls
    def visitAllControls(containingDocument: XFormsContainingDocument, listener: XFormsControlVisitorListener) =
        visitSiblings(listener, containingDocument.getControls.getCurrentControlTree.getChildren.asScala)

    // Visit all the descendant controls of the given container control
    def visitControls(containerControl: XFormsContainerControl, includeCurrent: Boolean, listener: XFormsControlVisitorListener) {
        // Container itself
        if (includeCurrent)
            if (! listener.startVisitControl(containerControl))
                return

        // Children
        visitSiblings(listener, containerControl.children)

        // Container itself
        if (includeCurrent)
            listener.endVisitControl(containerControl)
    }

    def visitSiblings(listener: XFormsControlVisitorListener, children: Seq[XFormsControl]): Unit =
        for (currentControl ← children) {
            if (listener.startVisitControl(currentControl)) {
                currentControl match {
                    case container: XFormsContainerControl ⇒
                        visitSiblings(listener, container.children)
                    case nonContainer ⇒
                        // NOTE: Unfortunately we handle children actions of non container controls a bit differently
                        val childrenActions = nonContainer.getChildrenActions.asScala
                        if (childrenActions.nonEmpty)
                            visitSiblings(listener, childrenActions)
                }

                listener.endVisitControl(currentControl)
            }
        }
}