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

import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsControlFactory;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.control.XFormsValueControl;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.ElementHandler;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.saxon.om.FastStringBuffer;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Base class for all XHTML and XForms element handlers.
 */
public abstract class XFormsBaseHandler extends ElementHandler {

    private boolean repeating;
    private boolean forwarding;

    protected HandlerContext handlerContext;

    protected PipelineContext pipelineContext;
    protected XFormsContainingDocument containingDocument;

    protected AttributesImpl reusableAttributes = new AttributesImpl();

    protected XFormsBaseHandler(boolean repeating, boolean forwarding) {
        this.repeating = repeating;
        this.forwarding = forwarding;
    }

    public void setContext(Object context) {
        this.handlerContext = (HandlerContext) context;

        this.pipelineContext = handlerContext.getPipelineContext();
        this.containingDocument = handlerContext.getContainingDocument();

        super.setContext(context);
    }

    public boolean isRepeating() {
        return repeating;
    }

    public boolean isForwarding() {
        return forwarding;
    }

    /**
     * Whether the control is disabled. Occurs when:
     *
     * o control is not null and is non-relevant
     * o control is null and is not in a repeat template
     *
     * @param xformsControl control to check or null if no concrete control available
     * @return              whether the control is to be marked as disabled
     */
    protected boolean isDisabled(XFormsSingleNodeControl xformsControl) {
        return xformsControl != null && !xformsControl.isRelevant() || xformsControl == null && !handlerContext.isTemplate();
    }

    public static void handleReadOnlyAttribute(AttributesImpl newAttributes, XFormsContainingDocument containingDocument, XFormsSingleNodeControl xformsControl) {
        if (xformsControl != null && xformsControl.isReadonly() && !XFormsProperties.isStaticReadonlyAppearance(containingDocument)) {
            // @disabled="disabled"
            newAttributes.addAttribute("", "disabled", "disabled", ContentHandlerHelper.CDATA, "disabled");
        }
    }

    public void handleMIPClasses(FastStringBuffer sb, String controlPrefixedId, XFormsSingleNodeControl xformsControl) {

        // Output MIP classes only having a binding
        final boolean hasBinding = ((XFormsStaticState.ControlInfo) containingDocument.getStaticState().getControlInfoMap().get(controlPrefixedId)).hasBinding();
        if (hasBinding) {
            if (xformsControl != null) {
                // The case of a concrete control

                // Output standard MIP classes
                if (!xformsControl.isRelevant()) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append("xforms-disabled");
                }
                if (!xformsControl.isValid()) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append("xforms-invalid");
                }
                if (xformsControl.isReadonly()) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append("xforms-readonly");
                }
                if (xformsControl.isRequired()) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append("xforms-required");
                    if (xformsControl instanceof XFormsValueControl) {
                        if (isEmpty(xformsControl))
                            sb.append(" xforms-required-empty");
                        else
                            sb.append(" xforms-required-filled");
                    }
                }

                // Output custom MIPs classes
                final String customMIPs = xformsControl.getCustomMIPsClasses();
                if (customMIPs != null) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append(customMIPs);
                }

                // Output type class
                final String typeName = xformsControl.getBuiltinTypeName();
                if (typeName != null) {
                    // Control is bound to built-in schema type
                    if (sb.length() > 0)
                        sb.append(' ');

                    sb.append("xforms-type-");
                    sb.append(typeName);
                }
            } else if (!handlerContext.isTemplate()) {
                // Case of a non-concrete control - simply mark the control as disabled
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append("xforms-disabled");
            }
        }
    }

    private boolean isEmpty(XFormsControl xformsControl) {
        // TODO: Configure meaning of "empty" through property (trimming vs. no strict) 
        return xformsControl instanceof XFormsValueControl && "".equals(((XFormsValueControl) xformsControl).getValue(pipelineContext));
    }

    protected static void handleAccessibilityAttributes(Attributes srcAttributes, AttributesImpl destAttributes) {
        // Handle "tabindex"
        {
            // This is the standard XForms attribute
            String value = srcAttributes.getValue("navindex");
            if (value == null) {
                // Try the the XHTML attribute
                value = srcAttributes.getValue("tabindex");
            }
//            if (value == null) {
//                // Use automatically generated index
//                value = Integer.toString(handlerContext.nextTabIndex());
//            }

            if (value != null)
                destAttributes.addAttribute("", "tabindex", "tabindex", ContentHandlerHelper.CDATA, value);
        }
        // Handle "accesskey"
        {
            final String value = srcAttributes.getValue("accesskey");
            if (value != null)
                destAttributes.addAttribute("", "accesskey", "accesskey", ContentHandlerHelper.CDATA, value);
        }
    }
    protected AttributesImpl getAttributes(Attributes elementAttributes, String classes, String id) {
        return getAttributes(reusableAttributes, elementAttributes, classes, id);
    }

    protected static AttributesImpl getAttributes(AttributesImpl reusableAttributes, Attributes elementAttributes, String classes, String id) {
        reusableAttributes.clear();

        // Copy "id"
        if (id != null) {
            reusableAttributes.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, id);
        }
        // Create "class" attribute if necessary
        {
            if (classes != null && classes.length() > 0) {
                reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, classes);
            }
        }
        // Copy attributes in the xhtml namespace to no namespace
        for (int i = 0; i < elementAttributes.getLength(); i++) {
            if (XMLConstants.XHTML_NAMESPACE_URI.equals(elementAttributes.getURI(i))) {
                final String name = elementAttributes.getLocalName(i);
                if (!"class".equals(name)) {
                    reusableAttributes.addAttribute("", name, name, ContentHandlerHelper.CDATA, elementAttributes.getValue(i));
                }
            }
        }

        return reusableAttributes;
    }

    protected FastStringBuffer getInitialClasses(String controlURI, String controlName, Attributes controlAttributes, XFormsControl xformsControl) {
        return getInitialClasses(controlURI, controlName, controlAttributes, xformsControl, null, false);
    }

    protected FastStringBuffer getInitialClasses(String controlURI, String controlName, Attributes controlAttributes, XFormsControl xformsControl, QName appearance, boolean incrementalDefault) {

        final FastStringBuffer sb = new FastStringBuffer(50);
        // User-defined classes go first
        {
            {
                final String value = controlAttributes.getValue("class");
                if (value != null) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append(value);
                }
            }
            {
                final String value = controlAttributes.getValue(XMLConstants.XHTML_NAMESPACE_URI, "class");
                if (value != null) {
                    if (sb.length() > 0)
                        sb.append(' ');
                    sb.append(value);
                }
            }
        }

        // Control name
        {
            if (sb.length() > 0)
                sb.append(' ');

            // We only call xforms-control the actual controls as per the spec
            // TODO: XForms 1.1 has core and container controls, but now we depend on xforms-control class in client
            if (!XFormsControlFactory.isContainerControl(controlURI, controlName))
                sb.append("xforms-control xforms-");
            else
                sb.append("xforms-");
            sb.append(controlName);
        }
        {
            // Class for incremental mode
            final String value = controlAttributes.getValue("incremental");
            // Set the class if the default is non-incremental and the user explicitly set the value to true, or the
            // default is incremental and the user did not explicitly set it to false
            if ((!incrementalDefault && "true".equals(value)) || (incrementalDefault && !"false".equals(value))) {
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append("xforms-incremental");
            }
        }
        {
            // Class for appearance
            if (appearance == null)
                appearance = getAppearance(controlAttributes);

            if (appearance != null) {
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append("xforms-");
                sb.append(controlName);
                sb.append("-appearance-");
                // Allow xxforms:* and *
                if (XFormsConstants.XXFORMS_NAMESPACE_URI.equals(appearance.getNamespace().getURI()))
                    sb.append("xxforms-");
                else if (!"".equals(appearance.getNamespace().getURI()))
                    throw new ValidationException("Invalid appearance namespace URI: " + appearance.getNamespace().getURI(), handlerContext.getLocationData());
                sb.append(appearance.getName());
            }
        }
        {
            // Class for mediatype
            final String mediatypeValue = controlAttributes.getValue("mediatype");
            if (mediatypeValue != null) {

                // NOTE: We could certainly do a better check than this to make sure we have a valid mediatype
                final int slashIndex = mediatypeValue.indexOf('/');
                if (slashIndex == -1)
                    throw new ValidationException("Invalid mediatype attribute value: " + mediatypeValue, handlerContext.getLocationData());

                if (sb.length() > 0)
                    sb.append(' ');
                sb.append("xforms-mediatype-");
                if (mediatypeValue.endsWith("/*")) {
                    // Add class with just type: "image/*" -> "xforms-mediatype-image"
                    sb.append(mediatypeValue.substring(0, mediatypeValue.length() - 2));
                } else {
                    // Add class with type and subtype: "text/html" -> "xforms-mediatype-text-html"
                    sb.append(mediatypeValue.replace('/', '-'));
                    // Also add class with just type: "image/jpeg" -> "xforms-mediatype-image"
                    sb.append(" xforms-mediatype-");
                    sb.append(mediatypeValue.substring(0, slashIndex));
                }
            }
        }

        // Static read-only
        if (isStaticReadonly(xformsControl))
            sb.append(" xforms-static");

        return sb;
    }

    protected String uriFromQName(String qName) {
        return XMLUtils.uriFromQName(qName, handlerContext.getController().getNamespaceSupport());
    }

    protected boolean isStaticReadonly(XFormsControl xformsControl) {
        return xformsControl != null && xformsControl.isStaticReadonly();
    }

    protected void handleLabelHintHelpAlert(String forEffectiveId, String lhhaType, XFormsSingleNodeControl control, boolean isTemplate) throws SAXException {

        final boolean isHint = lhhaType.equals("hint");
        final boolean isAlert = lhhaType.equals("alert");

        // Don't handle alerts and help in read-only mode
        // TODO: Removing hints and help could be optional depending on appearance
        if (isStaticReadonly(control) && (isAlert || isHint))
            return;

        final boolean isLabel = lhhaType.equals("label");
        final boolean isHelp = lhhaType.equals("help");

        final String labelHintHelpAlertValue;
        final boolean mustOutputHTMLFragment;
        if (control != null) {
            // Get actual value from control
            if (isLabel) {
                labelHintHelpAlertValue = control.getLabel(pipelineContext);
                mustOutputHTMLFragment = control.isHTMLLabel(pipelineContext);
            } else if (isHelp) {
                // NOTE: Special case here where we get the escaped help to facilitate work below. Help is a special
                // case because it is stored as escaped HTML within a <label> element.
                labelHintHelpAlertValue = control.getEscapedHelp(pipelineContext);
                mustOutputHTMLFragment = false;
            } else if (isHint) {
                labelHintHelpAlertValue = control.getHint(pipelineContext);
                mustOutputHTMLFragment = control.isHTMLHint(pipelineContext);
            } else if (isAlert) {
                labelHintHelpAlertValue = control.getAlert(pipelineContext);
                mustOutputHTMLFragment = control.isHTMLAlert(pipelineContext);
            } else {
                throw new IllegalStateException("Illegal type requested");
            }
        } else {
            // Placeholder
            labelHintHelpAlertValue = null;
            mustOutputHTMLFragment = false;
        }

        final String elementName;
        final Attributes labelHintHelpAlertAttributes;
        {
            // Statically obtain attributes information
            final XFormsStaticState staticState = containingDocument.getStaticState();
            final Element lhhaElement;
            final String forPrefixedId = XFormsUtils.getEffectiveIdNoSuffix(forEffectiveId);
            if (isLabel) {
                elementName = handlerContext.getLabelElementName();
                lhhaElement = staticState.getLabelElement(forPrefixedId);
            } else if (isHelp) {
                elementName = handlerContext.getHelpElementName();
                lhhaElement = staticState.getHelpElement(forPrefixedId);
            } else if (isHint) {
                elementName = handlerContext.getHintElementName();
                lhhaElement = staticState.getHintElement(forPrefixedId);
            } else if (isAlert) {
                elementName = handlerContext.getAlertElementName();
                lhhaElement = staticState.getAlertElement(forPrefixedId);
            } else {
                throw new IllegalStateException("Illegal type requested");
            }

            labelHintHelpAlertAttributes = (lhhaElement != null) ? XMLUtils.convertAttributes(lhhaElement) : null;
        }

        if (labelHintHelpAlertAttributes != null || isAlert) {
            // If no attributes were found, there is no such label / help / hint / alert

            final FastStringBuffer classes = new FastStringBuffer(30);

            // Put user classes first if any
            if (labelHintHelpAlertAttributes != null) {
                final String userClass = labelHintHelpAlertAttributes.getValue("class");
                if (userClass != null)
                    classes.append(userClass);
            }

            // Handle alert state
            // TODO: Once we have the new HTML layout, this won't be needed anymore
            if (isAlert) {
                if (classes.length() > 0)
                    classes.append(' ');
                if (control != null && (!control.isValid() || control.isRequired() && isEmpty(control)))
                    classes.append("xforms-alert-active");
                else
                    classes.append("xforms-alert-inactive");
            }

            // Handle visibility
            // TODO: It would be great to actually know about the relevance of help, hint, and label. Right now, we just look at whether the value is empty
            if (control != null) {
                if (isAlert || isLabel) {
                    // Allow empty labels and alerts
                    if (!control.isRelevant()) {
                        if (classes.length() > 0)
                            classes.append(' ');
                        classes.append("xforms-disabled");
                    }
                } else {
                    // For help and hint, consider "non-relevant" if empty
                    final boolean isHintHelpRelevant = control.isRelevant() && !(labelHintHelpAlertValue == null || labelHintHelpAlertValue.equals(""));
                    if (!isHintHelpRelevant) {
                        if (classes.length() > 0)
                            classes.append(' ');
                        classes.append("xforms-disabled");
                    }
                }
            } else if (!isTemplate || isHelp) {
                // Null control outside of template OR help within template
                if (classes.length() > 0)
                    classes.append(' ');
                classes.append("xforms-disabled");
            }

            if (classes.length() > 0)
                classes.append(' ');
            classes.append("xforms-");
            classes.append(lhhaType);

            final String labelClasses = classes.toString();

            final boolean isNoscript = handlerContext.isNoScript();
            if (isHelp) {
                // HACK: For help, output XHTML image natively in order to help with the IE bug whereby IE reloads
                // background images way too often.

                final ContentHandler contentHandler = handlerContext.getController().getOutput();
                final String xhtmlPrefix = handlerContext.findXHTMLPrefix();

                if (isNoscript && control != null) {
                    // Start <a href="#my-control-id-help">

                    final AttributesImpl aAttributes = new AttributesImpl();
                    aAttributes.addAttribute("", "href", "href", ContentHandlerHelper.CDATA, "#" + control.getEffectiveId() + "-help");
                    aAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-help-anchor");

                    final String aQName = XMLUtils.buildQName(xhtmlPrefix, "a");
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName, aAttributes);
                }

                classes.append("-image"); // xforms-help-image class
                final String helpImageClasses = classes.toString();

                final AttributesImpl imgAttributes = new AttributesImpl();
                imgAttributes.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, forEffectiveId + "-help-image");
                imgAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, helpImageClasses);
                imgAttributes.addAttribute("", "src", "src", ContentHandlerHelper.CDATA, XFormsConstants.HELP_IMAGE_URI);
                imgAttributes.addAttribute("", "title", "title", ContentHandlerHelper.CDATA, "");// do we need a title for screen readers?
                imgAttributes.addAttribute("", "alt", "alt", ContentHandlerHelper.CDATA, "");// however it seems that we don't need an alt since the help content is there

                final String imgQName = XMLUtils.buildQName(xhtmlPrefix, "img");
                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "img", imgQName, imgAttributes);
                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "img", imgQName);

                if (isNoscript && control != null) {
                    // End </a>
                    final String aQName = XMLUtils.buildQName(xhtmlPrefix, "a");
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName);
                }
            }

            // Output label, except help in noscript mode since in that case help is displayed separately
            if (!(isNoscript && isHelp)) {

                // We handle null attributes as well because we want a placeholder for "alert" even if there is no xforms:alert
                final Attributes newAttributes = (labelHintHelpAlertAttributes != null) ? labelHintHelpAlertAttributes : new AttributesImpl();
                if (newAttributes != null) {
                    outputLabelFor(handlerContext, getAttributes(newAttributes, labelClasses, null), forEffectiveId, lhhaType, elementName, labelHintHelpAlertValue, mustOutputHTMLFragment);
                }
            }
        }
    }

    protected static void outputLabelFor(HandlerContext handlerContext, Attributes attributes, String forEffectiveId, String lhhaType, String elementName, String value, boolean mustOutputHTMLFragment) throws SAXException {

        // Replace id attribute to be foo-label, foo-hint, foo-help, or foo-alert
        final AttributesImpl newAttribute;
        if (lhhaType != null) {
            // E.g. foo$bar.1-2-3 -> foo$bar-alert.1-2-3
//            final String newId = XFormsUtils.getEffectiveIdNoSuffix(forEffectiveId) + "-" + lhhaType + XFormsUtils.getEffectiveIdSuffixWithSeparator(forEffectiveId);
//            newAttribute = XMLUtils.addOrReplaceAttribute(attributes, "", "", "id",  newId);

            // E.g. foo$bar.1-2-3 -> foo$bar.1-2-3-alert
            newAttribute = XMLUtils.addOrReplaceAttribute(attributes, "", "", "id", forEffectiveId + "-" + lhhaType);
        } else {
            newAttribute = new AttributesImpl(attributes);
        }

        // Add @for attribute
        newAttribute.addAttribute("", "for", "for", ContentHandlerHelper.CDATA, forEffectiveId);

        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String labelQName = XMLUtils.buildQName(xhtmlPrefix, elementName);
        final ContentHandler contentHandler = handlerContext.getController().getOutput();

        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, elementName, labelQName, newAttribute);
        // Only output content when there value is non-empty
        if (value != null && !value.equals("")) {
            if (mustOutputHTMLFragment) {
                XFormsUtils.streamHTMLFragment(contentHandler, value, null, xhtmlPrefix);
            } else {
                contentHandler.characters(value.toCharArray(), 0, value.length());
            }
        }
        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, elementName, labelQName);
    }

    protected static void outputLabelText(ContentHandler contentHandler, XFormsControl xformsControl, String value, String xhtmlPrefix, boolean mustOutputHTMLFragment) throws SAXException {
        // Only output content when there value is non-empty
        if (value != null && !value.equals("")) {
            if (mustOutputHTMLFragment)
                XFormsUtils.streamHTMLFragment(contentHandler, value, xformsControl != null ? xformsControl.getLocationData() : null, xhtmlPrefix);
            else
                contentHandler.characters(value.toCharArray(), 0, value.length());
        }
    }

    protected static void copyAttributes(Attributes sourceAttributes, String sourceNamespaceURI, String[] sourceAttributeLocalNames, AttributesImpl destAttributes) {
        for (int i = 0; i < sourceAttributeLocalNames.length; i++) {
            final String attributeName = sourceAttributeLocalNames[i];
            final String attributeValue = sourceAttributes.getValue(sourceNamespaceURI, attributeName);
            if (attributeValue != null)
                destAttributes.addAttribute("", attributeName, attributeName, ContentHandlerHelper.CDATA, attributeValue);
        }
    }

    protected QName getAppearance(Attributes controlAttributes) {
        final String appearanceValue = controlAttributes.getValue("appearance");
        if (appearanceValue == null)
            return null;

        final String appearanceLocalname = XMLUtils.localNameFromQName(appearanceValue);
        final String appearancePrefix = XMLUtils.prefixFromQName(appearanceValue);
        final String appearanceURI = uriFromQName(appearanceValue);

        return new QName(appearanceLocalname, new Namespace(appearancePrefix, appearanceURI));
    }
}
