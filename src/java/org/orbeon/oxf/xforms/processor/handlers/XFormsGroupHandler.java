/**
 * Copyright (C) 2009 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.processor.handlers;

import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.processor.XFormsElementFilterContentHandler;
import org.orbeon.oxf.xml.*;
import org.orbeon.saxon.om.FastStringBuffer;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Handle xforms:group.
 */
public class XFormsGroupHandler extends XFormsControlLifecyleHandler {

    // Appearances
    private boolean isFieldsetAppearance;
    private boolean isInternalAppearance;

    private boolean isGroupInTable;

    // Label information gathered during prepareHandler()
    private String labelValue;
    private FastStringBuffer labelClasses;

    private DeferredContentHandler currentSavedOutput;
    private OutputInterceptor outputInterceptor;

    private static final String XHTML_PREFIX = "{" + XMLConstants.XHTML_NAMESPACE_URI + "}";
    private static final int XHTML_PREFIX_LENGTH = XHTML_PREFIX.length();
    private static final Map TABLE_CONTAINERS  = new HashMap();

    static {
        TABLE_CONTAINERS.put("table", "");
        TABLE_CONTAINERS.put("tbody", "");
        TABLE_CONTAINERS.put("thead", "");
        TABLE_CONTAINERS.put("tfoot", "");
        TABLE_CONTAINERS.put("tr", "");
    }

    public XFormsGroupHandler() {
        super(false, true);
    }

    protected void prepareHandler(String uri, String localname, String qName, Attributes attributes, String staticId, String effectiveId, XFormsSingleNodeControl xformsControl) {
        // Special appearance that does not output any HTML. This is temporary until xforms:group is correctly supported within xforms:repeat.
        isInternalAppearance = XFormsConstants.XXFORMS_INTERNAL_APPEARANCE_QNAME.equals(getAppearance(attributes));
        if (isInternalAppearance)
            return;

        isFieldsetAppearance = XFormsConstants.XXFORMS_FIELDSET_APPEARANCE_QNAME.equals(getAppearance(attributes));

        // Determine whether the closest xhtml:* parent is xhtml:table|xhtml:tbody|xhtml:thead|xhtml:tfoot|xhtml:tr
        final ElementHandlerController controller = handlerContext.getController();
        {
            final Stack elementNames = controller.getElementNames();
            for (int i = elementNames.size() - 1; i >= 0; i--) {
                final String currentElementName = (String) elementNames.get(i);
                if (currentElementName.startsWith(XHTML_PREFIX)) {
                    final String currentLocalName = currentElementName.substring(XHTML_PREFIX_LENGTH);
                    isGroupInTable = (TABLE_CONTAINERS.get(currentLocalName) != null);
                    break;
                }
            }
        }

        if (!isGroupInTable) {
            // Group outside table

            // Gather information about label value and classes

            // Value
            if (handlerContext.isTemplate() || xformsControl == null) {
                labelValue = null;
            } else {
                labelValue = xformsControl.getLabel(pipelineContext);
            }

            // Label
            final boolean hasLabel = XFormsControl.hasLabel(containingDocument, getPrefixedId());
            if (hasLabel) {
                labelClasses = new FastStringBuffer("xforms-label");

                // Handle relevance on label
                if ((xformsControl == null && !handlerContext.isTemplate()) || (xformsControl != null && !xformsControl.isRelevant())) {
                    labelClasses.append(" xforms-disabled");
                }

                // Copy over existing label classes if any
                final String labelClassAttribute = containingDocument.getStaticState().getLabelElement(getPrefixedId()).attributeValue("class");
                if (labelClassAttribute != null) {
                    labelClasses.append(' ');
                    labelClasses.append(labelClassAttribute);
                }
            }
        }
    }

    public void handleControlStart(String uri, String localname, String qName, Attributes attributes, String staticId, final String effectiveId, XFormsSingleNodeControl xformsControl) throws SAXException {

        // No additional markup for internal appearance
        if (isInternalAppearance)
            return;

        // Start xhtml:span or xhtml:fieldset
        final String groupElementName = isFieldsetAppearance ? "fieldset" : "span";
        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String groupElementQName = XMLUtils.buildQName(xhtmlPrefix, groupElementName);

        final ElementHandlerController controller = handlerContext.getController();

        if (!isGroupInTable) {
            // Group outside table

            // Get classes
            final FastStringBuffer classes = getInitialClasses(uri, localname, attributes, null);
            handleMIPClasses(classes, getPrefixedId(), xformsControl);

            final ContentHandler contentHandler = controller.getOutput();

            if (isFieldsetAppearance) {
                // Fieldset appearance

                // Start xhtml:fieldset element
                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName, getAttributes(attributes, classes.toString(), effectiveId));

                // Output an xhtml:legend element if and only if there is an xforms:label element. This help with
                // styling in particular.
                final boolean hasLabel = XFormsControl.hasLabel(containingDocument, getPrefixedId());
                if (hasLabel) {

                    // Handle label classes
                    reusableAttributes.clear();
                    reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, labelClasses.toString());
                    reusableAttributes.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, effectiveId + "-label");

                    // Output xhtml:legend with label content
                    final String legendQName = XMLUtils.buildQName(xhtmlPrefix, "legend");
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "legend", legendQName, reusableAttributes);
                    if (labelValue != null && !labelValue.equals(""))
                        contentHandler.characters(labelValue.toCharArray(), 0, labelValue.length());
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "legend", legendQName);
                }
            } else {
                // Default appearance

                // Label is handled by handleLabel()

                // Start xhtml:span element
                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName, getAttributes(attributes, classes.toString(), effectiveId));
            }
        } else {
            // Group within table

            // Get classes for the first delimiter
            // As of August 2009, actually only need the marker class as well as xforms-disabled if the group is non-relevant
            final FastStringBuffer classes = new FastStringBuffer("xforms-group-begin-end");
            handleMIPClasses(classes, getPrefixedId(), xformsControl);

            // Place interceptor on output

            // NOTE: Strictly, we should be able to do without the interceptor. We use it here because it
            // automatically handles ids and element names
            currentSavedOutput = controller.getOutput();
            if (!handlerContext.isNoScript()) {
                outputInterceptor = new OutputInterceptor(currentSavedOutput, groupElementQName, new OutputInterceptor.Listener() {
                    public void generateFirstDelimiter(OutputInterceptor outputInterceptor) throws SAXException {
                        // Delimiter: begin group
                        outputInterceptor.outputDelimiter(currentSavedOutput, outputInterceptor.getDelimiterNamespaceURI(),
                                outputInterceptor.getDelimiterPrefix(), outputInterceptor.getDelimiterLocalName(), classes.toString(), "group-begin-" + effectiveId);
                    }
                });
                // TODO: is the use of XFormsElementFilterContentHandler necessary now?
                controller.setOutput(new DeferredContentHandlerImpl(new XFormsElementFilterContentHandler(outputInterceptor)));

                // Set control classes
                outputInterceptor.setAddedClasses(classes);
            } else if (isDisabled(xformsControl)) {
                // Group not visible, set output to a black hole
                handlerContext.getController().setOutput(new DeferredContentHandlerAdapter());
            }

            // Don't support label, help, alert, or hint and other appearances, only the content!
        }
    }

    public void handleControlEnd(String uri, String localname, String qName, Attributes attributes, String staticId, String effectiveId, XFormsSingleNodeControl xformsControl) throws SAXException {

        // No additional markup for internal appearance
        if (isInternalAppearance)
            return;

        final ElementHandlerController controller = handlerContext.getController();
        if (!isGroupInTable) {
            // Group outside table

            // Close xhtml:span
            final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
            final String groupElementName = isFieldsetAppearance ? "fieldset" : "span";
            final String groupElementQName = XMLUtils.buildQName(xhtmlPrefix, groupElementName);
            controller.getOutput().endElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName);
        } else {
            // Group within table

            if (!handlerContext.isNoScript()) {
                // Restore output
                controller.setOutput(currentSavedOutput);

                // Delimiter: end repeat
                outputInterceptor.flushCharacters(true, true);
                outputInterceptor.outputDelimiter(currentSavedOutput, outputInterceptor.getDelimiterNamespaceURI(),
                        outputInterceptor.getDelimiterPrefix(), outputInterceptor.getDelimiterLocalName(), "xforms-group-begin-end", "group-end-" + effectiveId);
            } else if (isDisabled(xformsControl)) {
                // Group not visible, restore output
                handlerContext.getController().setOutput(currentSavedOutput);
            }

            // Don't support help, alert, or hint!
        }
    }

    protected void handleLabel(String staticId, String effectiveId, Attributes attributes, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (!isInternalAppearance && !isGroupInTable && !isFieldsetAppearance) {// regular group
            // Output an xhtml:label element if and only if there is an xforms:label element. This help with
            // styling in particular.
            reusableAttributes.clear();
            reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, labelClasses.toString());
            outputLabelFor(handlerContext, reusableAttributes, effectiveId, effectiveId, "label", handlerContext.getLabelElementName(), labelValue, xformsControl != null && xformsControl.isHTMLLabel(pipelineContext));
        }
    }

    protected void handleHint(String staticId, String effectiveId, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (!isInternalAppearance && !isGroupInTable)
            super.handleHint(staticId, effectiveId, xformsControl, isTemplate);
    }

    protected void handleAlert(String staticId, String effectiveId, Attributes attributes, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (!isInternalAppearance && !isGroupInTable)
            super.handleAlert(staticId, effectiveId, attributes, xformsControl, isTemplate);
    }

    protected void handleHelp(String staticId, String effectiveId, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (!isInternalAppearance && !isGroupInTable)
            super.handleHelp(staticId, effectiveId, xformsControl, isTemplate);
    }
}
