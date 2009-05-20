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
package org.orbeon.oxf.xforms.action.actions;

import org.dom4j.Element;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsContextStack;
import org.orbeon.oxf.xforms.XFormsInstance;
import org.orbeon.oxf.xforms.XFormsModel;
import org.orbeon.oxf.xforms.action.XFormsAction;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.event.XFormsEventObserver;
import org.orbeon.oxf.xforms.event.XFormsEventTarget;
import org.orbeon.oxf.xforms.event.events.XXFormsValueChanged;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.om.NodeInfo;

import java.util.Collections;
import java.util.List;

/**
 * 10.1.9 The setvalue Element
 */
public class XFormsSetvalueAction extends XFormsAction {
    public void execute(XFormsActionInterpreter actionInterpreter, PipelineContext pipelineContext, String targetId,
                        XFormsEventObserver eventObserver, Element actionElement,
                        boolean hasOverriddenContext, Item overriddenContext) {

        final XFormsContainingDocument containingDocument = actionInterpreter.getContainingDocument();
        final XFormsContextStack contextStack = actionInterpreter.getContextStack();

        final String value = actionElement.attributeValue("value");
        final String content = actionElement.getStringValue();

        final String valueToSet;
        if (value != null) {
            // Value to set is computed with an XPath expression

            final List currentNodeset;
            {
                final XFormsInstance currentInstance = contextStack.getCurrentInstance();// TODO: we should not use this
                currentNodeset = (contextStack.getCurrentNodeset() != null && contextStack.getCurrentNodeset().size() > 0)
                        ? contextStack.getCurrentNodeset()
                        : Collections.singletonList(currentInstance.getDocumentInfo());

                // NOTE: The above is actually not correct: the context should not become null or empty. This is
                // therefore just a workaround for a bug we hit:

                // o Do 2 setvalue in sequence
                // o The first one changes the context around the control containing the actions
                // o When the second one runs, context is empty, and setvalue either crashes or does nothing
                //
                // The correct solution is probably to NOT reevaluate the context of actions unless a rebuild is done.
                // This would require an update to the way we impelement the processing model.
            }

            valueToSet = XPathCache.evaluateAsString(pipelineContext,
                    currentNodeset, contextStack.getCurrentPosition(),
                    value, actionInterpreter.getNamespaceMappings(actionElement), contextStack.getCurrentVariables(),
                    XFormsContainingDocument.getFunctionLibrary(), contextStack.getFunctionContext(), null,
                    (LocationData) actionElement.getData());
        } else {
            // Value to set is static content
            valueToSet = content;
        }

        // Set value on current node
        final NodeInfo currentNode = contextStack.getCurrentSingleNode();
        if (currentNode != null) {
            // TODO: XForms 1.1: "Element nodes: If element child nodes are present, then an xforms-binding-exception
            // occurs. Otherwise, regardless of how many child nodes the element has, the result is that the string
            // becomes the new content of the element. In accord with the data model of [XPath 1.0], the element will
            // have either a single non-empty text node child, or no children string was empty.

            // Node exists, we can try to set the value
            doSetValue(pipelineContext, containingDocument, eventObserver, currentNode, valueToSet, null, false);
        } else {
            // Node doesn't exist, don't do anything
            // NOP
            if (XFormsServer.logger.isDebugEnabled()) {
                containingDocument.logDebug("setvalue", "not setting instance value", new String[] {
                        "reason", "destination node not found",
                        "value", valueToSet
                });
            }
        }
    }

    public static boolean doSetValue(PipelineContext pipelineContext, XFormsContainingDocument containingDocument,
                                     XFormsEventTarget eventTarget, NodeInfo currentNode,
                                     String valueToSet, String type, boolean isCalculate) {

        final String currentValue = XFormsInstance.getValueForNodeInfo(currentNode);
        final boolean changed = !currentValue.equals(valueToSet);

        if (XFormsServer.logger.isDebugEnabled()) {
            final XFormsInstance modifiedInstance = containingDocument.getInstanceForNode(currentNode);
            containingDocument.logDebug("setvalue", "setting instance value", new String[] { "value", valueToSet,
                    "changed", Boolean.toString(changed),
                    "instance", (modifiedInstance != null) ? modifiedInstance.getEffectiveId() : "N/A" });
        }

        // We take the liberty of not requiring RRR and marking the instance dirty if the value hasn't actually changed
        if (changed) {

            // Actually set the value
            XFormsInstance.setValueForNodeInfo(pipelineContext, containingDocument, eventTarget, currentNode, valueToSet, type);

            final XFormsInstance modifiedInstance = containingDocument.getInstanceForNode(currentNode);
            if (modifiedInstance != null) {// can be null if you set a value in a non-instance doc

                // Dispatch extension event to instance
                modifiedInstance.getContainer(containingDocument).dispatchEvent(pipelineContext, new XXFormsValueChanged(modifiedInstance));

                if (!isCalculate) {
                    // When this is called from a calculate, we don't set the flags as revalidate and refresh will have been set already

                    // "XForms Actions that change only the value of an instance node results in setting the flags for
                    // recalculate, revalidate, and refresh to true and making no change to the flag for rebuild".
                    final XFormsModel.DeferredActionContext deferredActionContext = modifiedInstance.getModel(containingDocument).getDeferredActionContext();
                    deferredActionContext.recalculate = true;
                    deferredActionContext.revalidate = true;
                    deferredActionContext.refresh = true;
                }
            }

            containingDocument.getControls().markDirtySinceLastRequest(true);

            return true;
        } else {
            return false;
        }
    }
}
