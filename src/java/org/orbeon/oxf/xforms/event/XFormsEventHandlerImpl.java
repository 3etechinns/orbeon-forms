/**
 *  Copyright (C) 2005 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.event;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;

import java.util.*;

/**
 * Represents an XForms (or just plain XML Events) event handler implementation.
 */
public class XFormsEventHandlerImpl implements XFormsEventHandler {

    private Element eventHandlerElement;
    private String ancestorObserverStaticId;

    private Map eventNames;
    private boolean isAllEvents;
    private String[] observerStaticIds;
    private Map targetStaticIds;
    //private String handler;
    private boolean isBubblingPhase;        // "true" means "default" (bubbling), "false" means "capture"
    private boolean isPropagate;            // "true" means "continue", "false" means "stop"
    private boolean isPerformDefaultAction; // "true" means "perform", "false" means "cancel"

    public XFormsEventHandlerImpl(Element eventHandlerElement, String ancestorObserverStaticId) {

        this.eventHandlerElement = eventHandlerElement;
        this.ancestorObserverStaticId = ancestorObserverStaticId;

        // Gather observers
        // NOTE: Supporting space-separated handlers is an extension, which may make it into XML Events 2
        {
            final String observerAttribute = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_OBSERVER_ATTRIBUTE_QNAME);
            final Element parentElement = eventHandlerElement.getParent();
            if (observerAttribute != null) {
                // ev:observer attribute specifies observers
                observerStaticIds = StringUtils.split(observerAttribute);
            } else if (parentElement != null && parentElement.attributeValue("id") != null) {
                // Observer is parent
                observerStaticIds = new String[] { parentElement.attributeValue("id")};
            } else {
                // No observer
                observerStaticIds = new String[0];
            }
        }

        // Gather event names
        // NOTE: Supporting space-separated event names is an extension, which may make it into XML Events 2
        {
            final String eventAttribute = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_EVENT_ATTRIBUTE_QNAME);
            eventNames = new HashMap();
            final String[] eventNamesArray = StringUtils.split(eventAttribute);
            for (int i = 0; i < eventNamesArray.length; i++) {
                eventNames.put(eventNamesArray[i], "");
            }
            // Special #all value catches all events
            if (eventNames.get(XFormsConstants.XXFORMS_ALL_EVENTS) != null) {
                eventNames = null;
                isAllEvents = true;
            }
        }

        // Gather target ids
        // NOTE: Supporting space-separated target ids is an extension, which may make it into XML Events 2
        {
            final String targetAttribute = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_TARGET_ATTRIBUTE_QNAME);
            if (targetAttribute == null) {
                targetStaticIds = null;
            } else {
                targetStaticIds = new HashMap();
                final String[] targetIdsArray = StringUtils.split(targetAttribute);
                for (int i = 0; i < targetIdsArray.length; i++) {
                    targetStaticIds.put(targetIdsArray[i], "");
                }
            }
        }

        {
            final String captureString = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_PHASE_ATTRIBUTE_QNAME);
            this.isBubblingPhase = !"capture".equals(captureString);
        }
        {
            final String propagateString = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_PROPAGATE_ATTRIBUTE_QNAME);
            this.isPropagate = !"stop".equals(propagateString);
        }
        {
            final String defaultActionString = eventHandlerElement.attributeValue(XFormsConstants.XML_EVENTS_DEFAULT_ACTION_ATTRIBUTE_QNAME);
            this.isPerformDefaultAction = !"cancel".equals(defaultActionString);
        }
    }

    public static void addActionHandler(Map eventNamesMap, Map eventHandlersMap, Element actionElement, String prefix, String ancestorObserverStaticId) {

        // Create event handler
        final XFormsEventHandlerImpl newEventHandlerImpl = new XFormsEventHandlerImpl(actionElement, ancestorObserverStaticId);

        // Register event handler
        final String[] observersStaticIds = newEventHandlerImpl.getObserversStaticIds();
        if (observersStaticIds.length > 0) {
            // There is at least one observer
            for (int j = 0; j < observersStaticIds.length; j++) {
                final String currentObserverStaticId = observersStaticIds[j];

                // NOTE: Handle special case of global id on containing document
                final String currentObserverPrefixedId
                        = XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID.equals(currentObserverStaticId)
                        ? currentObserverStaticId : prefix + currentObserverStaticId;

                // Get handlers for observer
                final List eventHandlersForObserver;
                {
                    final Object currentList = eventHandlersMap.get(currentObserverPrefixedId);
                    if (currentList == null) {
                        eventHandlersForObserver = new ArrayList();
                        eventHandlersMap.put(currentObserverPrefixedId, eventHandlersForObserver);
                    } else {
                        eventHandlersForObserver = (List) currentList;
                    }
                }

                // Add event handler
                eventHandlersForObserver.add(newEventHandlerImpl);
            }

            // Remember all event names
            if (newEventHandlerImpl.isAllEvents) {
                eventNamesMap.put(XFormsConstants.XXFORMS_ALL_EVENTS, "");
            } else {
                for (Iterator i = newEventHandlerImpl.eventNames.keySet().iterator(); i.hasNext();) {
                    final String eventName = (String) i.next();
                    eventNamesMap.put(eventName, "");
                }
            }
        }
    }

    public void handleEvent(PipelineContext pipelineContext, XBLContainer container,
                            XFormsEventObserver eventObserver, XFormsEvent event) {
        // Create a new top-level action interpreter to handle this event
        new XFormsActionInterpreter(pipelineContext, container, eventObserver, eventHandlerElement, ancestorObserverStaticId)
                .runAction(pipelineContext, event.getTargetObject().getEffectiveId(), eventObserver, eventHandlerElement);
    }

    public String[] getObserversStaticIds() {
        return observerStaticIds;
    }

    public boolean isBubblingPhase() {
        return isBubblingPhase;
    }

    public boolean isPropagate() {
        return isPropagate;
    }

    public boolean isPerformDefaultAction() {
        return isPerformDefaultAction;
    }

    public boolean isMatchEventName(String eventName) {
        return isAllEvents || eventNames.get(eventName) != null;
    }

    public boolean isMatchTarget(String targetStaticId) {
        // Match if no target id is specified, or if any specifed target matches
        return targetStaticIds == null || targetStaticIds.get(targetStaticId) != null;
    }
}
