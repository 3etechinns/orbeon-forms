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
package org.orbeon.oxf.xforms.processor.handlers;

import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.control.XFormsValueControl;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Handle xforms:textarea.
 */
public class XFormsTextareaHandler extends XFormsValueControlHandler {

    private static final String[] XXFORMS_ATTRIBUTES_TO_COPY = { "rows", "cols" };
    private Attributes elementAttributes;

    public XFormsTextareaHandler() {
        super(false);
    }

    public void start(String uri, String localname, String qName, Attributes attributes) throws SAXException {
        elementAttributes = new AttributesImpl(attributes);
        super.start(uri, localname, qName, attributes);
    }

    public void end(String uri, String localname, String qName) throws SAXException {

        final ContentHandler contentHandler = handlerContext.getController().getOutput();
        final String id = handlerContext.getId(elementAttributes);
        final String effectiveId = handlerContext.getEffectiveId(elementAttributes);
        final XFormsValueControl xformsControl = handlerContext.isGenerateTemplate()
                ? null : (XFormsValueControl) containingDocument.getObjectById(effectiveId);
        final boolean isConcreteControl = xformsControl != null;

        // xforms:label
        handleLabelHintHelpAlert(id, effectiveId, "label", xformsControl);

        final AttributesImpl newAttributes;
        {
            final StringBuffer classes = getInitialClasses(localname, elementAttributes, xformsControl);
            handleMIPClasses(classes, xformsControl);
            newAttributes = getAttributes(elementAttributes, classes.toString(), effectiveId);
            handleReadOnlyAttribute(newAttributes, containingDocument, xformsControl);
        }

        // Create xhtml:textarea
        {
            final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
            if (!isStaticReadonly(xformsControl)) {
                final String textareaQName = XMLUtils.buildQName(xhtmlPrefix, "textarea");
                newAttributes.addAttribute("", "name", "name", ContentHandlerHelper.CDATA, effectiveId);

                // Copy special attributes in xxforms namespace
                copyAttributes(elementAttributes, XFormsConstants.XXFORMS_NAMESPACE_URI, XXFORMS_ATTRIBUTES_TO_COPY, newAttributes);

                // Handle accessibility attributes
                handleAccessibilityAttributes(elementAttributes, newAttributes);

                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "textarea", textareaQName, newAttributes);
                if (isConcreteControl) {
                    final String value = xformsControl.getValue();
                    if (value != null)
                        contentHandler.characters(value.toCharArray(), 0, value.length());
                }
                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "textarea", textareaQName);
            } else {
                final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");

                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, newAttributes);
                if (isConcreteControl) {
                    final String value = xformsControl.getValue();
                    if (value != null) {
                        final boolean isHTMLMediaType = "text/html".equals(xformsControl.getMediatype());
                        if (!isHTMLMediaType) {
                            contentHandler.characters(value.toCharArray(), 0, value.length());
                        } else {
                            XFormsUtils.streamHTMLFragment(contentHandler, value, xformsControl.getLocationData(), xhtmlPrefix);
                        }
                    }
                }
                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
            }
        }

        // xforms:help
        handleLabelHintHelpAlert(id, effectiveId, "help", xformsControl);

        // xforms:alert
        handleLabelHintHelpAlert(id, effectiveId, "alert", xformsControl);

        // xforms:hint
        handleLabelHintHelpAlert(id, effectiveId, "hint", xformsControl);
    }
}
