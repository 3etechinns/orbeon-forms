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

import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.control.controls.XFormsOutputControl;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.saxon.om.FastStringBuffer;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Handle xforms:output.
 *
 * @noinspection SimplifiableIfStatement
 */
public class XFormsOutputHandler extends XFormsControlLifecyleHandler {

    public XFormsOutputHandler() {
        super(false);
    }

    protected void handleLabel(String staticId, String effectiveId, Attributes attributes, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (!XFormsConstants.XXFORMS_DOWNLOAD_APPEARANCE_QNAME.equals(getAppearance(attributes))) {
            super.handleLabel(staticId, effectiveId, attributes, xformsControl, isTemplate);
        }
    }

    protected void handleControlStart(String uri, String localname, String qName, Attributes attributes, String staticId, String effectiveId, XFormsSingleNodeControl xformsControl) throws SAXException {

        final XFormsOutputControl outputControl = (XFormsOutputControl) xformsControl;

        final ContentHandler contentHandler = handlerContext.getController().getOutput();
        final boolean isConcreteControl = outputControl != null;

        final String mediatypeValue = attributes.getValue("mediatype");
        final boolean isImageMediatype = mediatypeValue != null && mediatypeValue.startsWith("image/");
        final boolean isHTMLMediaType = (mediatypeValue != null && mediatypeValue.equals("text/html"));

        final AttributesImpl newAttributes;
        if (handlerContext.isNewXHTMLLayout()) {
            reusableAttributes.clear();
            newAttributes = reusableAttributes;
            newAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-output-output");
        } else {
            final FastStringBuffer classes = getInitialClasses(uri, localname, attributes, outputControl);
            handleMIPClasses(classes, getPrefixedId(), outputControl);
            newAttributes = getAttributes(attributes, classes.toString(), effectiveId);

            if (isConcreteControl) {
                // Output extension attributes in no namespace
                outputControl.addExtensionAttributes(newAttributes, "");
            }
        }

        if (XFormsConstants.XXFORMS_TEXT_APPEARANCE_QNAME.equals(getAppearance(attributes))) {
            // Just output value for "text" appearance
            if (isImageMediatype || isHTMLMediaType) {
                throw new ValidationException("Cannot use mediatype value for \"xxforms:text\" appearance: " + mediatypeValue, handlerContext.getLocationData());
            }

            if (isConcreteControl) {
                final String displayValue = outputControl.getExternalValue(pipelineContext);
                if (displayValue != null && displayValue.length() > 0)
                    contentHandler.characters(displayValue.toCharArray(), 0, displayValue.length());
            }

        } else {
            // Create xhtml:span or xhtml:div
            final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
            // We need to generate a div here for IE, which doesn't support working with innerHTML on spans.
            final String enclosingElementLocalname = isHTMLMediaType ? "div" : "span";
            final String enclosingElementQName = XMLUtils.buildQName(xhtmlPrefix, enclosingElementLocalname);

            // Handle accessibility attributes (de facto, tabindex is supported on all elements)
            handleAccessibilityAttributes(attributes, newAttributes);

            if (!handlerContext.isNewXHTMLLayout())
                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName, newAttributes);
            {
                if (XFormsConstants.XXFORMS_DOWNLOAD_APPEARANCE_QNAME.equals(getAppearance(attributes))) {
                    // Download appearance

                    final String aQName = XMLUtils.buildQName(xhtmlPrefix, "a");
                    final AttributesImpl aAttributes = handlerContext.isNewXHTMLLayout() ? newAttributes : new AttributesImpl();
                    final String hrefValue = XFormsOutputControl.getExternalValue(pipelineContext, outputControl, null);

                    if (hrefValue == null || hrefValue.trim().equals("")) {
                        // No URL so make sure a click doesn't cause navigation, and add class
                        aAttributes.addAttribute("", "href", "href", ContentHandlerHelper.CDATA, "#");
                        XMLUtils.appendToClassAttribute(aAttributes, "xforms-readonly");
                    } else {
                        // URL value
                        aAttributes.addAttribute("", "href", "href", ContentHandlerHelper.CDATA, hrefValue);
                    }

                    // Add _blank target in order to prevent:
                    // 1. The browser replacing the current page, and
                    // 2. The browser displaying the "Are you sure you want to navigate away from this page?" warning dialog
                    // This, as of 2009-05, seems to be how most sites handle this
                    aAttributes.addAttribute("", "target", "target", ContentHandlerHelper.CDATA, "_blank");

                    // Output xxforms:* extension attributes
                    if (xformsControl != null)
                        xformsControl.addExtensionAttributes(aAttributes, XFormsConstants.XXFORMS_NAMESPACE_URI);

                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName, aAttributes);

                    final String labelValue = (xformsControl != null) ? xformsControl.getLabel(pipelineContext) : null;
                    final boolean mustOutputHTMLFragment = xformsControl != null && xformsControl.isHTMLLabel(pipelineContext);
                    outputLabelText(contentHandler, xformsControl, labelValue, xhtmlPrefix, mustOutputHTMLFragment);

                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName);

                } else if (isImageMediatype) {
                    // Case of image media type with URI
                    final String imgQName = XMLUtils.buildQName(xhtmlPrefix, "img");
                    final AttributesImpl imgAttributes = handlerContext.isNewXHTMLLayout() ? newAttributes : new AttributesImpl();
                    // @src="..."
                    // NOTE: If producing a template, or if the image URL is blank, we point to an existing dummy image
                    final String srcValue = XFormsOutputControl.getExternalValue(pipelineContext, outputControl, mediatypeValue);
                    imgAttributes.addAttribute("", "src", "src", ContentHandlerHelper.CDATA, srcValue);

                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "img", imgQName, imgAttributes);
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "img", imgQName);
                } else if (isHTMLMediaType) {
                    // HTML case

                    if (handlerContext.isNewXHTMLLayout())
                        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName, newAttributes);

                    if (isConcreteControl) {
                        final String htmlValue = XFormsOutputControl.getExternalValue(pipelineContext, outputControl, mediatypeValue);
                        XFormsUtils.streamHTMLFragment(contentHandler, htmlValue, outputControl.getLocationData(), xhtmlPrefix);
                    }

                    if (handlerContext.isNewXHTMLLayout())
                        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName);
                } else {
                    // Regular text case

                    if (handlerContext.isNewXHTMLLayout())
                        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName, newAttributes);

                    if (isConcreteControl) {
                        final String textValue = XFormsOutputControl.getExternalValue(pipelineContext, outputControl, mediatypeValue);
                        if (textValue != null && textValue.length() > 0)
                            contentHandler.characters(textValue.toCharArray(), 0, textValue.length());
                    }

                    if (handlerContext.isNewXHTMLLayout())
                        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName);
                }
            }
            if (!handlerContext.isNewXHTMLLayout())
                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, enclosingElementLocalname, enclosingElementQName);
        }
    }
}
