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
package org.orbeon.oxf.xforms.control

import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.processor.converter.XHTMLRewrite
import org.orbeon.oxf.util.NetUtils
import org.orbeon.oxf.xforms._
import analysis.controls.AppearanceTrait
import org.orbeon.oxf.xforms.analysis.ChildrenBuilderTrait
import org.orbeon.oxf.xforms.analysis.ElementAnalysis
import org.orbeon.oxf.xforms.analysis.XPathDependencies
import org.orbeon.oxf.xforms.control.controls.XFormsActionControl
import org.orbeon.oxf.xforms.event.XFormsEventObserver
import org.orbeon.oxf.xforms.event.XFormsEventTarget
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.oxf.xml.ForwardingXMLReceiver
import org.orbeon.oxf.xml.XMLUtils
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData
import org.orbeon.oxf.xml.dom4j.LocationData
import org.xml.sax.Attributes
import scala.Option
import scala.collection.Seq
import scala.collection.JavaConverters._
import org.orbeon.oxf.xforms.BindingContext
import java.util.{Collections, LinkedList, List ⇒ JList}
import org.dom4j.{QName, Element}
import org.orbeon.saxon.om.Item

/**
 * Represents an XForms control.
 *
 * The implementation is split into a series of traits to make each chunk more palatable.
 */
class XFormsControl(
        val container: XBLContainer,
        var parent: XFormsControl,
        val element: Element,
        var effectiveId: String)
    extends ControlXPathSupport
    with ControlAjaxSupport
    with ControlLHHASupport
    with ControlLocalSupport
    with ControlExtensionAttributesSupport
    with ControlEventSupport
    with ControlBindingSupport
    with ControlXMLDumpSupport
    with XFormsEventTarget
    with XFormsEventObserver
    with ExternalCopyable {

    // Static information (never changes for the lifetime of the containing document)
    // TODO: Pass staticControl during construction (find which callers don't pass the necessary information)
    private val _staticControl = Option(container) map (_.getPartAnalysis.getControlAnalysis(XFormsUtils.getPrefixedId(effectiveId))) orNull
    def staticControl = _staticControl
    final val containingDocument = Option(container) map (_.getContainingDocument) orNull

    final val prefixedId = Option(staticControl) map (_.prefixedId) getOrElse XFormsUtils.getPrefixedId(effectiveId)
    final val _element = Option(staticControl) map (_.element) getOrElse element

    parent match {
        case container: XFormsContainerControl ⇒ container.addChild(this)
        case _ ⇒
    }

    final def getId = staticControl.staticId
    final def getPrefixedId = prefixedId

    def getScope(containingDocument: XFormsContainingDocument) = staticControl.scope
    final def getXBLContainer(containingDocument: XFormsContainingDocument) = container

    final def getName = staticControl.localName
    final def getControlElement = element

    // For cloning only!
    final def setParent(parent: XFormsControl) = this.parent = parent

    def getContextStack = container.getContextStack
    final def getIndentedLogger = containingDocument.getControls.getIndentedLogger

    final def getResolutionScope =
        container.getPartAnalysis.scopeForPrefixedId(prefixedId)

    final def getChildElementScope(element: Element) =
        container.getPartAnalysis.scopeForPrefixedId(container.getFullPrefix + XFormsUtils.getElementId(element))

    // Update this control's effective id based on the parent's effective id
    def updateEffectiveId() {
        if (staticControl.isWithinRepeat) {
            val parentEffectiveId = parent.getEffectiveId
            val parentSuffix = XFormsUtils.getEffectiveIdSuffix(parentEffectiveId)
            effectiveId = XFormsUtils.getPrefixedId(effectiveId) + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1 + parentSuffix
            if (childrenActions ne null) {
                for (actionControl ← childrenActions.asScala)
                    actionControl.updateEffectiveId()
            }
        }
    }

    def getEffectiveId = effectiveId

    // Used by repeat iterations
    def setEffectiveId(effectiveId: String) =
        this.effectiveId = effectiveId

    final def getLocationData =
        if (staticControl ne null) staticControl.locationData else if (element ne null) element.getData.asInstanceOf[LocationData] else null

    // Semi-dynamic information (depends on the tree of controls, but does not change over time)
    private var childrenActions: JList[XFormsActionControl] = null

    // Dynamic information (changes depending on the content of XForms instances)
    private var previousEffectiveId: String = null

    // NOP, can be overridden
    def iterationRemoved(): Unit = ()

    // NOTE: As of 2011-11-22, this is here so that effective ids of nested actions can be updated when iterations
    // are moved around. Ideally anyway, iterations should not require effective id changes. An iteration tree should
    // probably have a uuid, and updating it should be done in constant time.
    // As of 2012-02-23, this is also here so that binding updates can take place on nested actions. However this too is
    // not really necessary, as an action's binding is just a copy of its parent binding as we don't want to needlessly
    // evaluate the actual binding before the action runs. But we have this so that the setBindingContext,
    // bindingContextForChild, bindingContextForFollowing operations work on nested actions too.
    // Possibly we could make *any* control able to have nested content. This would make sense from a class hierarchy
    // perspective.
    final def addChildAction(actionControl: XFormsActionControl) {
        if (childrenActions == null)
            childrenActions = new LinkedList[XFormsActionControl]
        childrenActions.add(actionControl)
    }

    final def getChildrenActions =
        Option(childrenActions) getOrElse Collections.emptyList[XFormsActionControl]

    final def previousEffectiveIdCommit() = {
        val result = previousEffectiveId
        previousEffectiveId = effectiveId
        result
    }

    def commitCurrentUIState() {
        wasRelevantCommit()
        previousEffectiveIdCommit()
    }

    final def getAppearances = XFormsControl.appearances(staticControl)
    def isStaticReadonly = false

    // Optional mediatype
    final def mediatype = staticControl match {
        case appearanceTrait: AppearanceTrait ⇒ appearanceTrait.mediatype
        case _ ⇒ None
    }

    def getJavaScriptInitialization: (String, String, String) = null

    def getCommonJavaScriptInitialization = {
        val appearances = getAppearances
        // First appearance only (should probably handle all of them, but historically only one appearance was handled)
        val firstAppearance = if (! appearances.isEmpty) Some(Dom4jUtils.qNameToExplodedQName(appearances.iterator.next)) else None
        (getName, firstAppearance orElse mediatype orNull, getEffectiveId)
    }

    // Compare this control with another control, as far as the comparison is relevant for the external world.
    def equalsExternal(other: XFormsControl): Boolean = {
        if (other eq null)
            return false

        if (this eq other)
            return true

        compareRelevance(other) && compareLHHA(other) && compareExtensionAttributes(other)
    }

    final def evaluate() {
        try evaluateImpl()
        catch {
            case e: ValidationException ⇒ {
                throw ValidationException.wrapException(e, new ExtendedLocationData(getLocationData, "evaluating control", getControlElement, "element", Dom4jUtils.elementToDebugString(getControlElement)))
            }
        }
    }

    // Notify the control that some of its aspects (value, label, etc.) might have changed and require re-evaluation. It
    // is left to the control to figure out if this can be optimized.
    def markDirtyImpl(xpathDependencies: XPathDependencies) {
        markLHHADirty()
        markExtensionAttributesDirty()
    }

    // Evaluate this control.
    // TODO: move this method to XFormsValueControl and XFormsValueContainerControl?
    def evaluateImpl() {
        // TODO: these should be evaluated lazily
        // Evaluate standard extension attributes
        evaluateExtensionAttributes(AjaxSupport.STANDARD_EXTENSION_ATTRIBUTES)

        // Evaluate custom extension attributes
        Option(getExtensionAttributes) foreach
            (evaluateExtensionAttributes(_))
    }

    /**
     * Clone a control. It is important to understand why this is implemented: to create a copy of a tree of controls
     * before updates that may change control bindings. Also, it is important to understand that we clone "back", that
     * is the new clone will be used as the reference copy for the difference engine.
     */
    def getBackCopy: AnyRef = {
        // NOTE: this.parent is handled by subclasses
        val cloned = super.clone.asInstanceOf[XFormsControl]

        updateLHHACopy(cloned)
        updateLocalCopy(cloned)
        updateExtensionAttributesCopy(cloned)

        cloned
    }

    /**
     * Set the focus on this control.
     *
     * @return  true iif control accepted focus
     */
    // By default, a control doesn't accept focus
    def setFocus() = false

    // Build children controls if any, delegating the actual construction to the given `buildTree` function
    def buildChildren(buildTree: (XBLContainer, BindingContext, ElementAnalysis, Seq[Int]) ⇒ Option[XFormsControl], idSuffix: Seq[Int]) =
        staticControl match {
            case withChildren: ChildrenBuilderTrait ⇒ Controls.buildChildren(this, withChildren.children, buildTree, idSuffix)
            case _ ⇒
        }
}

object XFormsControl {

    def controlSupportsRefreshEvents(control: XFormsControl) =
        (control ne null) && control.supportsRefreshEvents

    // Find a control's binding, either single-item or item-sequence binding
    // TODO: Instead of pattern matching, add `binding` method to all controls
    def controlBinding(control: XFormsControl): Seq[Item] = control match {
        case control: XFormsSingleNodeControl ⇒
            // Single-node (single-item) binding
            Option(control.getBoundItem).toList
        case control: XFormsNoSingleNodeContainerControl ⇒
            // Node-set (item-sequence) binding
            Option(control.bindingContext) filter (_.isNewBind) map (_.nodeset.asScala) getOrElse Seq()
        case _ ⇒
            Seq()
    }

    // Find a control's binding context
    // TODO: Instead of pattern matching, add `bindingContext` method to all controls
    def controlBindingContext(control: XFormsControl): Seq[Item] =
        Option(control.bindingContext) flatMap
            (binding ⇒ Option(binding.parent)) map
                (binding ⇒ binding.nodeset.asScala) getOrElse
                    Seq()

    // Rewrite an HTML value which may contain URLs, for example in @src or @href attributes. Also deals with closing element tags.
    def getEscapedHTMLValue(locationData: LocationData, rawValue: String): String = {

        if (rawValue eq null)
            return null

        val sb = new StringBuilder(rawValue.length * 2) // just an approx of the size it may take
        // NOTE: we do our own serialization here, but it's really simple (no namespaces) and probably reasonably efficient
        val rewriter = NetUtils.getExternalContext.getResponse
        XFormsUtils.streamHTMLFragment(new XHTMLRewrite().getRewriteXMLReceiver(rewriter, new ForwardingXMLReceiver {

            private var isStartElement = false

            override def characters(chars: Array[Char], start: Int, length: Int) {
                sb.append(XMLUtils.escapeXMLMinimal(new String(chars, start, length))) // NOTE: not efficient to create a new String here
                isStartElement = false
            }

            override def startElement(uri: String, localname: String, qName: String, attributes: Attributes) {
                sb.append('<')
                sb.append(localname)
                val attributeCount = attributes.getLength

                for (i ← 0 to attributeCount -1) {
                    val currentName = attributes.getLocalName(i)
                    val currentValue = attributes.getValue(i)
                    sb.append(' ')
                    sb.append(currentName)
                    sb.append("=\"")
                    sb.append(currentValue)
                    sb.append('"')
                }

                sb.append('>')
                isStartElement = true
            }

            override def endElement(uri: String, localname: String, qName: String) {
                if (! isStartElement || ! XFormsUtils.isVoidElement(localname)) {
                    // We serialize to HTML: don't close elements that just opened (will cover <br>, <hr>, etc.). Be sure not to drop closing elements of other tags though!
                    sb.append("</")
                    sb.append(localname)
                    sb.append('>')
                }
                isStartElement = false
            }

        }, true), rawValue, locationData, "xhtml")

        sb.toString
    }

    // Base trait for a control property (label, itemset, etc.)
    trait ControlProperty[T >: Null] {
        def value(): T
        def handleMarkDirty()
        def copy: ControlProperty[T]
    }

    // Immutable control property
    class ImmutableControlProperty[T >: Null](val value: T) extends ControlProperty[T] {
        override def handleMarkDirty() = ()
        override def copy = this
    }

    // Mutable control property supporting optimization
    trait MutableControlProperty[T >: Null] extends ControlProperty[T] with Cloneable {

        private var _value: T = null
        private var isEvaluated = false
        private var isOptimized = false

        protected def isRelevant: Boolean
        protected def wasRelevant: Boolean
        protected def requireUpdate: Boolean
        protected def notifyCompute()
        protected def notifyOptimized()

        protected def evaluateValue(): T

        def value(): T = {// NOTE: making this method final produces an AbstractMethodError with Java 5 (ok with Java 6)
            if (! isEvaluated) {
                _value =
                    if (isRelevant) {
                        notifyCompute()
                        evaluateValue()
                    } else
                        // NOTE: if the control is not relevant, nobody should ask about this in the first place
                        null
                isEvaluated = true
            } else if (isOptimized) {
                // This is only for statistics: if the value was not re-evaluated because of the dependency engine
                // giving us the green light, the first time the value is asked we notify the dependency engine of that
                // situation.
                notifyOptimized()
                isOptimized = false
            }

            _value
        }

        def handleMarkDirty() {

            def isDirty = ! isEvaluated
            def markOptimized() = isOptimized = true

            if (! isDirty) {
                // don't do anything if we are already dirty
                if (isRelevant != wasRelevant) {
                    // Control becomes relevant or non-relevant
                    markDirty()
                } else if (isRelevant) {
                    // Control remains relevant
                    if (requireUpdate)
                        markDirty()
                    else
                        markOptimized() // for statistics only
                }
            }
        }

        protected def markDirty() {
            _value = null
            isEvaluated = false
            isOptimized = false
        }

        def copy = super.clone.asInstanceOf[MutableControlProperty[T]]
    }

    // Return the set of appearances for the given element, if any
    def appearances(elementAnalysis: ElementAnalysis) = elementAnalysis match {
        case appearanceTrait: AppearanceTrait ⇒ appearanceTrait.jAppearances
        case _ ⇒ Collections.emptySet[QName]
    }

    // Whether the given control has the text/html mediatype
    private val HTMLMediatype = Some("text/html")
    def isHTMLMediatype(control: XFormsControl) = control.mediatype == HTMLMediatype
}
