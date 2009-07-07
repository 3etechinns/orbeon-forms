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
package org.orbeon.oxf.xforms.action;

import org.dom4j.Element;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.event.XFormsEventObserver;
import org.orbeon.oxf.xforms.function.XFormsFunction;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.om.Item;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Execute a top-level XForms action and the included nested actions if any.
 */
public class XFormsActionInterpreter {

    private final XBLContainer container;
    private final XFormsContainingDocument containingDocument;

    private final XFormsControls xformsControls;
    private final XFormsContextStack contextStack;

    public XFormsActionInterpreter(PipelineContext pipelineContext, XBLContainer container,
                                   XFormsEventObserver eventObserver, Element actionElement, String ancestorObserverStaticId) {

        this.container = container;
        this.containingDocument = container.getContainingDocument();

        this.xformsControls = containingDocument.getControls();
        this.contextStack = new XFormsContextStack(container);

        // Set XPath context based on lexical location
        final String eventContainerEffectiveId;
        if (eventObserver.getId().equals(ancestorObserverStaticId)) {
            // Observer is parent of action, so we have easily access to the effective context
            eventContainerEffectiveId = eventObserver.getEffectiveId();
        } else {
            // Observer is not parent of action, try to find effective id of parent

            // Try to find effective parent object
            // TODO: this resolution doesn't look right!
            final Object o = container.resolveObjectById(null, ancestorObserverStaticId);
            eventContainerEffectiveId = (o != null) ? ((XFormsEventObserver) o).getEffectiveId() : ancestorObserverStaticId;

            // TODO: The logic above is not quite right at the moment because the parent might be in a repeat.
            // Should work for outer controls, models, instances and submissions
        }

        setActionBindingContext(pipelineContext, containingDocument, actionElement, eventContainerEffectiveId);
    }

    private void setActionBindingContext(PipelineContext pipelineContext, XFormsContainingDocument containingDocument,
                                         Element actionElement, String effectiveEventContainerId) {

        // Get "fresh" observer
        final XFormsEventObserver eventObserver = (XFormsEventObserver) containingDocument.getObjectByEffectiveId(effectiveEventContainerId);

        // Set context on container element
        contextStack.setBinding(pipelineContext, eventObserver);

        // Check variables in scope for action handlers within controls

        // NOTE: This is not optimal, as variable values are re-evaluated and may have values different from the ones
        // used by the controls during refresh. Contemplate handling this differently, e.g. see
        // http://wiki.orbeon.com/forms/projects/core-xforms-engine-improvements
        if (eventObserver instanceof XFormsControl) {
            int variablesCount = 0;

            final List actionPrecedingElements = Dom4jUtils.findPrecedingElements(actionElement, ((XFormsControl) eventObserver).getControlElement());
            if (actionPrecedingElements.size() > 0) {
                Collections.reverse(actionPrecedingElements);
                for (Iterator i = actionPrecedingElements.iterator(); i.hasNext();) {
                    final Element currentElement = (Element) i.next();
                    final String currentElementName = currentElement.getName();
                    if (currentElementName.equals("variable")) {
                        // Create variable object
                        final Variable variable = new Variable(container, contextStack, currentElement);

                        // Push the variable on the context stack. Note that we do as if each variable was a "parent" of the following controls and variables.
                        // NOTE: The value is computed immediately. We should use Expression objects and do lazy evaluation in the future.
                        contextStack.pushVariable(currentElement, variable.getVariableName(), variable.getVariableValue(pipelineContext, true));

                        variablesCount++;
                    }
                }
            }

            if (variablesCount > 0 && XFormsServer.logger.isDebugEnabled())
                containingDocument.logDebug("action", "evaluated variables for outer action",
                        new String[] { "count", Integer.toString(variablesCount) });
        }

        // Push binding for outermost action
        contextStack.pushBinding(pipelineContext, actionElement);
    }

    public XBLContainer getXBLContainer() {
        return container;
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    public XFormsControls getXFormsControls() {
        return xformsControls;
    }

    public XFormsContextStack getContextStack() {
        return contextStack;
    }

    public XFormsFunction.Context getFunctionContext() {
        return contextStack.getFunctionContext();
    }

    /**
     * Return the namespace mappings for the given action element.
     *
     * @param actionElement Element to get namsepace mapping for
     * @return              Map<String prefix, String uri>
     */
    public Map<String, String> getNamespaceMappings(Element actionElement) {
        return container.getNamespaceMappings(actionElement);
    }

    /**
     * Execute an XForms action.
     *
     * @param pipelineContext       current PipelineContext
     * @param targetEffectiveId     effective id of the target control
     * @param eventObserver event handler containe this action is running in
     * @param actionElement         Element specifying the action to execute
     */
    public void runAction(final PipelineContext pipelineContext, String targetEffectiveId, XFormsEventObserver eventObserver, Element actionElement) {

        // Check that we understand the action element
        final String actionNamespaceURI = actionElement.getNamespaceURI();
        final String actionName = actionElement.getName();
        if (!XFormsActions.isActionName(actionNamespaceURI, actionName)) {
            throw new ValidationException("Invalid action: " + XMLUtils.buildExplodedQName(actionNamespaceURI, actionName),
                    new ExtendedLocationData((LocationData) actionElement.getData(), "running XForms action", actionElement,
                            new String[] { "action name", XMLUtils.buildExplodedQName(actionNamespaceURI, actionName) }));
        }

        try {
            // Extract conditional action (@if / @exf:if)
            final String ifConditionAttribute;
            {
                final String ifAttribute = actionElement.attributeValue("if");
                if (ifAttribute != null)
                    ifConditionAttribute = ifAttribute;
                else
                    ifConditionAttribute = actionElement.attributeValue(XFormsConstants.EXFORMS_IF_ATTRIBUTE_QNAME);
            }

            // Extract iterated action (@while / @exf:while)
            final String whileIterationAttribute;
            {
                final String whileAttribute = actionElement.attributeValue("while");
                if (whileAttribute != null)
                    whileIterationAttribute = whileAttribute;
                else
                    whileIterationAttribute = actionElement.attributeValue(XFormsConstants.EXFORMS_WHILE_ATTRIBUTE_QNAME);
            }

            // Extract iterated action (@xxforms:iterate / @exf:iterate)
            final String iterateIterationAttribute;
            {
                final String xxformsIterateAttribute = actionElement.attributeValue(XFormsConstants.XXFORMS_ITERATE_ATTRIBUTE_QNAME);
                if (xxformsIterateAttribute != null)
                    iterateIterationAttribute = xxformsIterateAttribute;
                else
                    iterateIterationAttribute = actionElement.attributeValue(XFormsConstants.EXFORMS_ITERATE_ATTRIBUTE_QNAME);
            }

            // NOTE: At this point, the context has already been set to the current action element

            if (iterateIterationAttribute != null) {
                // Gotta iterate

                // We have to restore the context to the in-scope evaluation context, then push @model/@context/@iterate
                // NOTE: It's not 100% how @context and @xxforms:iterate should interact here
                final XFormsContextStack.BindingContext actionBindingContext = contextStack.popBinding();
                final Map<String, String> namespaceContext = container.getNamespaceMappings(actionElement);
                {
                    final String contextAttribute = actionElement.attributeValue("context");
                    final String modelAttribute = actionElement.attributeValue("model");
                    contextStack.pushBinding(pipelineContext, null, contextAttribute, iterateIterationAttribute, modelAttribute, null, actionElement, namespaceContext);
                }
                {
                    final String refAttribute = actionElement.attributeValue("ref");
                    final String nodesetAttribute = actionElement.attributeValue("nodeset");
                    final String bindAttribute = actionElement.attributeValue("bind");

                    final int iterationCount = contextStack.getCurrentNodeset().size();
                    for (int index = 1; index <= iterationCount; index++) {

                        // Push iteration
                        contextStack.pushIteration(index);

                        // Then we also need to push back binding attributes, excluding @context and @model
                        contextStack.pushBinding(pipelineContext, refAttribute, null, nodesetAttribute, null, bindAttribute, actionElement, namespaceContext);

                        final Item overriddenContextNodeInfo = contextStack.getCurrentSingleItem();
                        runSingleIteration(pipelineContext, targetEffectiveId, eventObserver, actionElement, actionNamespaceURI,
                                actionName, ifConditionAttribute, whileIterationAttribute, true, overriddenContextNodeInfo);

                        // Restore context
                        contextStack.popBinding();
                        contextStack.popBinding();
                    }

                }
                // Restore context stack
                contextStack.popBinding();
                contextStack.restoreBinding(actionBindingContext);
            } else {
                // Do a single iteration run (but this may repeat over the @while condition!)

                runSingleIteration(pipelineContext, targetEffectiveId, eventObserver, actionElement, actionNamespaceURI,
                        actionName, ifConditionAttribute, whileIterationAttribute, contextStack.hasOverriddenContext(), contextStack.getContextItem());
            }
        } catch (Exception e) {
            throw ValidationException.wrapException(e, new ExtendedLocationData((LocationData) actionElement.getData(), "running XForms action", actionElement,
                    new String[] { "action name", XMLUtils.buildExplodedQName(actionNamespaceURI, actionName) }));
        }
    }

    private void runSingleIteration(PipelineContext pipelineContext, String targetEffectiveId, XFormsEventObserver eventObserver,
                                    Element actionElement, String actionNamespaceURI, String actionName, String ifConditionAttribute,
                                    String whileIterationAttribute, boolean hasOverriddenContext, Item contextItem) {

        // The context is now the overridden context
        int whileIteration = 1;
        while (true) {
            // Check if the conditionAttribute attribute exists and stop if false
            if (ifConditionAttribute != null) {
                boolean result = evaluateCondition(pipelineContext, actionElement, actionName, ifConditionAttribute, "if", contextItem);
                if (!result)
                    break;
            }
            // Check if the iterationAttribute attribute exists and stop if false
            if (whileIterationAttribute != null) {
                boolean result = evaluateCondition(pipelineContext, actionElement, actionName, whileIterationAttribute, "while", contextItem);
                if (!result)
                    break;
            }

            // We are executing the action
            if (XFormsServer.logger.isDebugEnabled()) {
                if (whileIterationAttribute == null)
                    containingDocument.logDebug("action", "executing", new String[] { "action name", actionName });
                else
                    containingDocument.logDebug("action", "executing", new String[] { "action name", actionName, "while iteration", Integer.toString(whileIteration) });
            }

            // Get action and execute it
            final XFormsAction xformsAction = XFormsActions.getAction(actionNamespaceURI, actionName);
            containingDocument.startHandleOperation();
            xformsAction.execute(this, pipelineContext, targetEffectiveId, eventObserver, actionElement, hasOverriddenContext, contextItem);
            containingDocument.endHandleOperation();

            // Stop if there is no iteration
            if (whileIterationAttribute == null)
                break;

            // If we repeat, we must re-evaluate the action binding.
            // For example:
            //   <xforms:delete nodeset="/*/foo[1]" while="/*/foo"/>
            // In that case, in the second iteration, xforms:repeat must find an up-to-date nodeset
            // NOTE: There is still the possibility that parent bindings will be out of date. What should be done there?
            contextStack.popBinding();
            contextStack.pushBinding(pipelineContext, actionElement);

            whileIteration++;
        }
    }

    private boolean evaluateCondition(PipelineContext pipelineContext, Element actionElement,
                                      String actionName, String conditionAttribute, String conditionType,
                                      Item contextItem) {

        // Execute condition relative to the overridden context if it exists, or the in-scope context if not
        final List<Item> contextNodeset;
        final int contextPosition;
        {
            if (contextItem != null) {
                // Use provided context item
                contextNodeset = Collections.singletonList(contextItem);
                contextPosition = 1;
            } else {
                // Use empty context
                contextNodeset = XFormsConstants.EMPTY_ITEM_LIST;
                contextPosition = 0;
            }
        }

        // Don't evaluate the condition if the context has gone missing
        {
            if (contextNodeset.size() == 0) {//  || containingDocument.getInstanceForNode((NodeInfo) contextNodeset.get(contextPosition - 1)) == null
                if (XFormsServer.logger.isDebugEnabled())
                    containingDocument.logDebug("action", "not executing", new String[] { "action name", actionName, "condition type", conditionType, "reason", "missing context" });
                return false;
            }
        }

        final List conditionResult = XPathCache.evaluate(pipelineContext,
                contextNodeset, contextPosition, "boolean(" + conditionAttribute + ")",
            container.getNamespaceMappings(actionElement), contextStack.getCurrentVariables(), XFormsContainingDocument.getFunctionLibrary(),
            contextStack.getFunctionContext(), null, (LocationData) actionElement.getData());

        if (!((Boolean) conditionResult.get(0)).booleanValue()) {
            // Don't execute action

            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.logDebug("action", "not executing", new String[] { "action name", actionName, "condition type", conditionType, "reason", "condition evaluated to 'false'", "condition", conditionAttribute });

            return false;
        } else {
            // Condition is true
            return true;
        }
    }
}