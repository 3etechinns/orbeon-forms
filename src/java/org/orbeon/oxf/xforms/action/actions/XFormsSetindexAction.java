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
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.action.XFormsAction;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatControl;
import org.orbeon.oxf.xforms.event.XFormsEventHandlerContainer;
import org.orbeon.oxf.xforms.event.events.XFormsScrollFirstEvent;
import org.orbeon.oxf.xforms.event.events.XFormsScrollLastEvent;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.om.Item;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 9.3.7 The setindex Element
 */
public class XFormsSetindexAction extends XFormsAction {
    public void execute(XFormsActionInterpreter actionInterpreter, PipelineContext pipelineContext, String targetId,
                        XFormsEventHandlerContainer eventHandlerContainer, Element actionElement,
                        boolean hasOverriddenContext, Item overriddenContext) {

        final XFormsContainingDocument containingDocument = actionInterpreter.getContainingDocument();

        final String repeatId = XFormsUtils.namespaceId(containingDocument, actionElement.attributeValue("repeat"));
        final String indexXPath = actionElement.attributeValue("index");

        final NodeInfo currentSingleNode = actionInterpreter.getContextStack().getCurrentSingleNode();
        if (currentSingleNode == null)
            return;

        final String indexString = XPathCache.evaluateAsString(pipelineContext,
                actionInterpreter.getContextStack().getCurrentNodeset(), actionInterpreter.getContextStack().getCurrentPosition(),
                "number(" + indexXPath + ")", containingDocument.getStaticState().getNamespaceMappings(actionElement), null, XFormsContainingDocument.getFunctionLibrary(),
                actionInterpreter.getContextStack().getFunctionContext(), null,
                (LocationData) actionElement.getData());

        executeSetindexAction(pipelineContext, containingDocument, repeatId, indexString);
    }

    public static void executeSetindexAction(final PipelineContext pipelineContext, final XFormsContainingDocument containingDocument, final String repeatId, final String indexString) {
        if ("NaN".equals(indexString)) {
            // "If the index evaluates to NaN the action has no effect."
            return;
        }

        final XFormsControls xformsControls = containingDocument.getXFormsControls();
        xformsControls.rebuildCurrentControlsState(pipelineContext);
        final XFormsControls.ControlsState currentControlsState = xformsControls.getCurrentControlsState();

        final int index = Integer.parseInt(indexString);

        final Map repeatIdToRepeatControl = currentControlsState.getRepeatIdToRepeatXFormsControl();
        final XFormsRepeatControl repeatControl = (XFormsRepeatControl) repeatIdToRepeatControl.get(repeatId);

        if (repeatControl != null) {
            // Found control for repeat id
            if (index <= 0) {
                // "If the selected index is 0 or less, an xforms-scroll-first event is dispatched
                // and the index is set to 1."
                containingDocument.dispatchEvent(pipelineContext, new XFormsScrollFirstEvent(repeatControl));
                currentControlsState.updateRepeatIndex(repeatId, 1);
            } else {
                final List children = repeatControl.getChildren();

                if (children != null && index > children.size()) {
                    // "If the selected index is greater than the index of the last repeat
                    // item, an xforms-scroll-last event is dispatched and the index is set to
                    // that of the last item."

                    containingDocument.dispatchEvent(pipelineContext, new XFormsScrollLastEvent(repeatControl));
                    currentControlsState.updateRepeatIndex(repeatId, children.size());
                } else {
                    // Otherwise just set the index
                    currentControlsState.updateRepeatIndex(repeatId, index);
                }
            }

            // "The indexes for inner nested repeat collections are re-initialized to startindex."
            {
                // First step: set all children indexes to 0
                final List nestedRepeatIds = containingDocument.getStaticState().getNestedRepeatIds(repeatId);
                final Map nestedRepeatIdsMap = new HashMap();
                if (nestedRepeatIds != null) {
                    for (Iterator i = nestedRepeatIds.iterator(); i.hasNext();) {
                        final String currentRepeatId = (String) i.next();
                        nestedRepeatIdsMap.put(currentRepeatId, "");
                        currentControlsState.updateRepeatIndex(currentRepeatId, 0);
                    }
                }

                // Adjust controls ids that could have gone out of bounds
                XFormsIndexUtils.adjustRepeatIndexes(pipelineContext, xformsControls, nestedRepeatIdsMap);
            }

            // TODO: "The implementation data structures for tracking computational dependencies are
            // rebuilt or updated as a result of this action."
            for (Iterator i = containingDocument.getModels().iterator(); i.hasNext();) {
                XFormsModel currentModel = (XFormsModel) i.next();
                currentModel.applyComputedExpressionBinds(pipelineContext);
            }

            containingDocument.getXFormsControls().markDirtySinceLastRequest();
        } else {
            // "If there is a null search result for the target object and the source object is an XForms action such as
            // dispatch, send, setfocus, setindex or toggle, then the action is terminated with no effect."
            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.logDebug("xforms:setindex", "index does not refer to an existing xforms:repeat element, ignoring action",
                        new String[] { "repeat id", repeatId } );
        }
    }
}
