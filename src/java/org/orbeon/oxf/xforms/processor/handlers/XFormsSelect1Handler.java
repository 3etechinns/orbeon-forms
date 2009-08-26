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

import org.dom4j.QName;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.control.XFormsValueControl;
import org.orbeon.oxf.xforms.control.controls.XFormsSelect1Control;
import org.orbeon.oxf.xforms.itemset.Item;
import org.orbeon.oxf.xforms.itemset.Itemset;
import org.orbeon.oxf.xforms.itemset.ItemsetListener;
import org.orbeon.oxf.xforms.itemset.XFormsItemUtils;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.util.Collections;
import java.util.Iterator;

/**
 * Handle xforms:select and xforms:select1.
 *
 * TODO: Subclasses per appearance.
 */
public class XFormsSelect1Handler extends XFormsControlLifecyleHandler {

    private boolean isMultiple;
    private boolean isOpenSelection;
    private boolean isAutocomplete;
    private boolean isAutocompleteNoFilter;
    private boolean isFull;
    private boolean isCompact;
    private boolean isTree;
    private boolean isMenu;

    private static final Item EMPTY_TOP_LEVEL_ITEM = new Item(false, false, Collections.EMPTY_LIST, "", "");

    public XFormsSelect1Handler() {
        super(false);
    }

    @Override
    protected void prepareHandler(String uri, String localname, String qName, Attributes attributes, String staticId, String effectiveId, XFormsSingleNodeControl xformsControl) {

        QName appearance = getAppearance(attributes);

        this.isMultiple = localname.equals("select");
        this.isOpenSelection = "open".equals(attributes.getValue("selection"));
        this.isAutocomplete = isOpenSelection
                && XFormsConstants.XXFORMS_AUTOCOMPLETE_APPEARANCE_QNAME.equals(appearance);

        // NOTE: We don't support autocompletion with xforms:select for now, only with xforms:select1
        if (isAutocomplete && isMultiple) {
            appearance = XFormsConstants.XFORMS_COMPACT_APPEARANCE_QNAME;
            isOpenSelection = false;
            isAutocomplete = false;
        }

        this.isAutocompleteNoFilter = isAutocomplete && "false".equals(attributes.getValue(XFormsConstants.XXFORMS_NAMESPACE_URI, "filter"));

        this.isFull = XFormsConstants.XFORMS_FULL_APPEARANCE_QNAME.equals(appearance);
        this.isCompact = XFormsConstants.XFORMS_COMPACT_APPEARANCE_QNAME.equals(appearance);
        this.isTree = XFormsConstants.XXFORMS_TREE_APPEARANCE_QNAME.equals(appearance);
        this.isMenu = XFormsConstants.XXFORMS_MENU_APPEARANCE_QNAME.equals(appearance);
    }

    @Override
    protected void addCustomClasses(StringBuilder classes, XFormsSingleNodeControl xformsControl) {
        if (isOpenSelection)
            classes.append(" xforms-select1-open");
        if (isAutocompleteNoFilter)
            classes.append(" xforms-select1-open-autocomplete-nofilter");
        if (isTree)
            classes.append(" xforms-initially-hidden");
    }

    @Override
    protected boolean isDefaultIncremental() {
        // Incremental mode is the default
        return true;
    }

    @Override
    protected QName getAppearance(Attributes attributes) {
        final QName tempAppearance = super.getAppearance(attributes);

        final QName appearance;
        if (tempAppearance != null)
            appearance = tempAppearance;
        else if (isMultiple)
            appearance = XFormsConstants.XFORMS_COMPACT_APPEARANCE_QNAME;// default for xforms:select
        else
            appearance = XFormsConstants.XFORMS_MINIMAL_APPEARANCE_QNAME;// default for xforms:select1

        return appearance;
    }

    protected void handleControlStart(String uri, String localname, String qName, Attributes attributes, String id, String effectiveId, XFormsSingleNodeControl xformsControl) throws SAXException {
        // Get items, dynamic or static, if possible
        final XFormsSelect1Control xformsSelect1Control = (XFormsSelect1Control) xformsControl;

        // Get items if:
        // 1. The itemset is static
        // 2. The control exists and is relevant
        final Itemset itemset = XFormsSelect1Control.getInitialItemset(pipelineContext, containingDocument, xformsSelect1Control, getPrefixedId());

        outputContent(attributes, id, effectiveId, uri, localname, xformsSelect1Control, itemset, isMultiple, isFull);
    }

    public void outputContent(Attributes attributes, String staticId, String effectiveId, String uri, String localname,
                              final XFormsValueControl xformsControl, Itemset itemset,
                              final boolean isMultiple, final boolean isFull) throws SAXException {

        final ContentHandler contentHandler = handlerContext.getController().getOutput();

        final AttributesImpl containerAttributes = getContainerAttributes(uri, localname, attributes, effectiveId, xformsControl, !isFull);

        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        if (!isStaticReadonly(xformsControl)) {
            if (isFull) {
                final String fullItemType = isMultiple ? "checkbox" : "radio";

                // In noscript mode, use <fieldset>

                // TODO: This really hasn't much to do with noscript; should we always use fieldset, or make this an
                // option? Benefit of limiting to noscript is that then no JS change is needed
                final String containingElementName = handlerContext.isNoScript() ? "fieldset" : "span";
                final String containingElementQName = XMLUtils.buildQName(xhtmlPrefix, containingElementName);

                final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");
                {
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, containingElementName, containingElementQName, containerAttributes);
                    {
                        if (handlerContext.isNoScript()) {
                            // Output <legend>
                            final String legendName = "legend";
                            final String legendQName = XMLUtils.buildQName(xhtmlPrefix, legendName);
                            reusableAttributes.clear();
                            // TODO: handle other attributes? xforms-disabled?
                            reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-label");
                            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, legendName, legendQName, reusableAttributes);
                            if (xformsControl != null) {
                                final boolean mustOutputHTMLFragment = xformsControl.isHTMLLabel(pipelineContext);
                                outputLabelText(contentHandler, xformsControl, xformsControl.getLabel(pipelineContext), xhtmlPrefix, mustOutputHTMLFragment);
                            }
                            contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, legendName, legendQName);
                        }

                        if (itemset != null) {
                            int itemIndex = 0;
                            for (Iterator<Item> i = itemset.toList().iterator(); i.hasNext(); itemIndex++) {
                                final Item item = i.next();
                                handleItemFull(pipelineContext, handlerContext, contentHandler, reusableAttributes, attributes, xhtmlPrefix, spanQName, containingDocument, xformsControl, staticId, effectiveId, isMultiple, fullItemType, item, Integer.toString(itemIndex), itemIndex == 0);
                            }
                        }
                    }
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, containingElementName, containingElementQName);
                }

                // NOTE: Templates for full items are output globally in XHTMLBodyHandler

            } else {

                if (isOpenSelection) {

                    if (isAutocomplete) {

                        // Create xhtml:span
                        final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");
                        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, containerAttributes);

                        {
                            {
                                // Create xhtml:input
                                final String inputQName = XMLUtils.buildQName(xhtmlPrefix, "input");

                                reusableAttributes.clear();
                                reusableAttributes.addAttribute("", "type", "type", ContentHandlerHelper.CDATA, "text");
                                reusableAttributes.addAttribute("", "name", "name", ContentHandlerHelper.CDATA, "xforms-select1-open-input-" + effectiveId);
                                reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-select1-open-input");
                                reusableAttributes.addAttribute("", "autocomplete", "autocomplete", ContentHandlerHelper.CDATA, "off");

                                final String value = (xformsControl == null) ? null : xformsControl.getValue(pipelineContext);
                                // NOTE: With open selection, we send all values to the client but not encrypt them because the client matches on values
                                reusableAttributes.addAttribute("", "value", "value", ContentHandlerHelper.CDATA, (value == null) ? "" : value);
                                handleReadOnlyAttribute(reusableAttributes, containingDocument, xformsControl);
                                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "input", inputQName, reusableAttributes);

                                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "input", inputQName);
                            }
                            {
                                // Create xhtml:select
                                final String selectQName = XMLUtils.buildQName(xhtmlPrefix, "select");

                                reusableAttributes.clear();
                                reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-select1-open-select");

                                if (isCompact)
                                    reusableAttributes.addAttribute("", "multiple", "multiple", ContentHandlerHelper.CDATA, "multiple");

                                // Handle accessibility attributes
                                handleAccessibilityAttributes(attributes, reusableAttributes);

                                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "select", selectQName, reusableAttributes);

                                final String optionQName = XMLUtils.buildQName(xhtmlPrefix, "option");
                                handleItemCompact(contentHandler, optionQName, xformsControl, isMultiple, EMPTY_TOP_LEVEL_ITEM);
                                if (itemset != null) {
                                    for (Item item: itemset.toList()) {
                                        if (item.getValue() != null)
                                            handleItemCompact(contentHandler, optionQName, xformsControl, isMultiple, item);
                                    }
                                }

                                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "select", selectQName);
                            }
                        }

                        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
                    } else {
                        // We do not support other appearances or regular open selection for now
                        throw new ValidationException("Open selection currently only supports the xxforms:autocomplete appearance.",
                                new ExtendedLocationData(handlerContext.getLocationData(), "producing markup for xforms:" + localname + " control",
                                        (xformsControl != null) ? xformsControl.getControlElement() : null));
                    }

                } else if (isTree) {
                    // xxforms:tree appearance

                    // Create xhtml:div with tree info
                    final String divQName = XMLUtils.buildQName(xhtmlPrefix, "div");

                    handleReadOnlyAttribute(containerAttributes, containingDocument, xformsControl);
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName, containerAttributes);
                    outputJSONTreeInfo(xformsControl, itemset, isMultiple, contentHandler);
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName);

                } else if (isMenu) {
                    // xxforms:menu appearance

                    // Create enclosing xhtml:div
                    final String divQName = XMLUtils.buildQName(xhtmlPrefix, "div");
                    final String ulQName = XMLUtils.buildQName(xhtmlPrefix, "ul");
                    final String liQName = XMLUtils.buildQName(xhtmlPrefix, "li");
                    final String aQName = XMLUtils.buildQName(xhtmlPrefix, "a");

                    handleReadOnlyAttribute(containerAttributes, containingDocument, xformsControl);
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName, containerAttributes);
                    {
                        // Create xhtml:div with initial menu entries
                        {
                            itemset.visit(contentHandler, new ItemsetListener() {

                                private boolean groupJustStarted = false;

                                public void startLevel(ContentHandler contentHandler, boolean topLevel) throws SAXException {

                                    reusableAttributes.clear();
                                    final String className;
                                    {
                                        if (topLevel)
                                            className = "yuimenubar";
                                        else
                                            className = "yuimenu";
                                    }
                                    reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, className);
                                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName, reusableAttributes);

                                    reusableAttributes.clear();
                                    reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "bd");
                                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName, reusableAttributes);

                                    reusableAttributes.clear();
                                    reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "first-of-type");
                                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "ul", ulQName, reusableAttributes);

                                    groupJustStarted = true;
                                }

                                public void endLevel(ContentHandler contentHandler) throws SAXException {
                                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "ul", ulQName);
                                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName);
                                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName);

                                    groupJustStarted = false;
                                }

                                public void startItem(ContentHandler contentHandler, Item item, boolean first) throws SAXException {

                                    final String className;
                                    {
                                        if (item.isTopLevel())
                                            className = "yuimenubaritem";
                                        else
                                            className = "yuimenuitem";
                                    }
                                    reusableAttributes.clear();
                                    reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, className + (groupJustStarted ? " first-of-type" : ""));
                                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "li", liQName, reusableAttributes);

                                    reusableAttributes.clear();
                                    reusableAttributes.addAttribute("", "href", "href", ContentHandlerHelper.CDATA, "#");
                                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName, reusableAttributes);

                                    final String text = item.getLabel();
                                    contentHandler.characters(text.toCharArray(), 0, text.length());

                                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "a", aQName);

                                    groupJustStarted = false;
                                }


                                public void endItem(ContentHandler contentHandler) throws SAXException {
                                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "li", liQName);

                                    groupJustStarted = false;
                                }
                            });

                        }

                        // Create xhtml:div with tree info
                        reusableAttributes.clear();
                        reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-initially-hidden");

                        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName, reusableAttributes);
                        {
                            outputJSONTreeInfo(xformsControl, itemset, isMultiple, contentHandler);
                        }
                        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName);
                    }
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "div", divQName);

                } else {
                    // Create xhtml:select
                    final String selectQName = XMLUtils.buildQName(xhtmlPrefix, "select");
                    containerAttributes.addAttribute("", "name", "name", ContentHandlerHelper.CDATA, effectiveId);// necessary for noscript mode

                    if (isCompact)
                        containerAttributes.addAttribute("", "multiple", "multiple", ContentHandlerHelper.CDATA, "multiple");

                    // Handle accessibility attributes
                    handleAccessibilityAttributes(attributes, containerAttributes);

                    handleReadOnlyAttribute(containerAttributes, containingDocument, xformsControl);
                    contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "select", selectQName, containerAttributes);
                    {
                        final String optionQName = XMLUtils.buildQName(xhtmlPrefix, "option");
                        final String optGroupQName = XMLUtils.buildQName(xhtmlPrefix, "optgroup");

                        if (itemset != null) {

    // Work in progress for in-bounds/out-of-bounds
    //                        if (!((XFormsSelect1Control) xformsControl).isInBounds(items)) {
    //                            // Control is out of bounds so add first item with out of bound value to handle this
    //                            handleItemCompact(contentHandler, optionQName, xformsControl, isMultiple,
    //                                    new XFormsItemUtils.Item(XFormsProperties.isEncryptItemValues(containingDocument),
    //                                            Collections.EMPTY_LIST, "", xformsControl.getValue(pipelineContext), 1));
    //                        }

                            itemset.visit(contentHandler, new ItemsetListener() {

                                private int optgroupCount = 0;

                                public void startLevel(ContentHandler contentHandler, boolean topLevel) throws SAXException {
                                    // NOP
                                }

                                public void endLevel(ContentHandler contentHandler) throws SAXException {
                                    if (optgroupCount-- > 0) {
                                        // End xhtml:optgroup
                                        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "optgroup", optGroupQName);
                                    }
                                }

                                public void startItem(ContentHandler contentHandler, Item item, boolean first) throws SAXException {

                                    final String label = item.getLabel();
                                    final String value = item.getValue();

                                    if (value == null) {
                                        final AttributesImpl optGroupAttributes = getAttributes(new AttributesImpl(), null, null);
                                        if (label != null)
                                            optGroupAttributes.addAttribute("", "label", "label", ContentHandlerHelper.CDATA, label);

                                        // Start xhtml:optgroup
                                        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "optgroup", optGroupQName, optGroupAttributes);
                                        optgroupCount++;
                                    } else {
                                        handleItemCompact(contentHandler, optionQName, xformsControl, isMultiple, item);
                                    }
                                }


                                public void endItem(ContentHandler contentHandler) throws SAXException {
                                }
                            });
                        }
                    }
                    contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "select", selectQName);
                }
            }
        } else {
            // Read-only mode

            final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");
            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, containerAttributes);
            if (!handlerContext.isTemplate()) {
                final String value = (xformsControl == null || xformsControl.getValue(pipelineContext) == null) ? "" : xformsControl.getValue(pipelineContext);
                final StringBuilder sb = new StringBuilder();
                if (itemset != null) {
                    int selectedFound = 0;
                    for (Item currentItem: itemset.toList()) {
                        if (XFormsItemUtils.isSelected(isMultiple, value, currentItem.getValue())) {
                            if (selectedFound > 0)
                                sb.append(" - ");
                            sb.append(currentItem.getLabel());
                            selectedFound++;
                        }
                    }
                }

                if (sb.length() > 0) {
                    final String result = sb.toString();
                    contentHandler.characters(result.toCharArray(), 0, result.length());
                }
            }
            contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
        }
    }

    public static void outputItemFullTemplate(PipelineContext pipelineContext, HandlerContext handlerContext,
                                              ContentHandler contentHandler, String xhtmlPrefix, String spanQName,
                                              XFormsContainingDocument containingDocument,
                                              AttributesImpl reusableAttributes, Attributes attributes, String templateId,
                                              String staticId, String effectiveId, boolean isMultiple, String fullItemType) throws SAXException {
        reusableAttributes.clear();
        reusableAttributes.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, templateId);
        reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-template");

        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, reusableAttributes);
        handleItemFull(pipelineContext, handlerContext, contentHandler, reusableAttributes, attributes,
                xhtmlPrefix, spanQName, containingDocument, null, staticId, effectiveId, isMultiple, fullItemType,
                new Item(isMultiple, false, Collections.EMPTY_LIST, // make sure the value "$xforms-template-value$" is not encrypted
                        "$xforms-template-label$", "$xforms-template-value$"),
                        "$xforms-item-index$", true);
        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
    }

    private void outputJSONTreeInfo(XFormsValueControl xformsControl, Itemset itemset, boolean many, ContentHandler contentHandler) throws SAXException {
        if (xformsControl != null && !handlerContext.isTemplate()) {
            // Produce a JSON fragment with hierachical information
            final String result = itemset.getJSONTreeInfo(pipelineContext, xformsControl.getValue(pipelineContext), many, handlerContext.getLocationData());
            contentHandler.characters(result.toCharArray(), 0, result.length());
        } else {
            // Don't produce any content when generating a template
        }
    }

    public static void handleItemFull(PipelineContext pipelineContext, HandlerContext handlerContext, ContentHandler contentHandler,
                                       AttributesImpl reusableAttributes, Attributes attributes, String xhtmlPrefix, String spanQName,
                                       XFormsContainingDocument containingDocument, XFormsValueControl xformsControl, String staticId,
                                       String effectiveId, boolean isMultiple, String type,
                                       Item item, String itemIndex, boolean isFirst) throws SAXException {

        // Create an id for the item (trying to make this unique)
        final String itemEffectiveId = staticId + "-opsitem" + itemIndex + handlerContext.getIdPostfix();

        // Whether this is selected
        boolean isSelected;
        if (!handlerContext.isTemplate() && xformsControl != null) {
            final String itemValue = ((item.getValue() == null) ? "" : item.getValue()).trim();
            final String controlValue = ((xformsControl.getValue(pipelineContext) == null) ? "" : xformsControl.getValue(pipelineContext)).trim();
            isSelected = XFormsItemUtils.isSelected(isMultiple, controlValue, itemValue);
        } else {
            isSelected = false;
        }

        // xhtml:span enclosing input and label
        final AttributesImpl spanAttributes = getAttributes(reusableAttributes, new AttributesImpl(), isSelected ? "xforms-selected" : "xforms-deselected", null);
        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, spanAttributes);

        {
            // xhtml:span enclosing just the input
            reusableAttributes.clear();
            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, reusableAttributes);
            {
                // xhtml:input
                final String inputQName = XMLUtils.buildQName(xhtmlPrefix, "input");

                reusableAttributes.clear();
                reusableAttributes.addAttribute("", "id", "id", ContentHandlerHelper.CDATA, itemEffectiveId);
                reusableAttributes.addAttribute("", "type", "type", ContentHandlerHelper.CDATA, type);

                // TODO: may have duplicate ids for itemsets [WHAT IS THIS COMMENT ABOUT?]
                // Get group name from selection control if possible, otherwise use effective id
                final String name = (!isMultiple && xformsControl instanceof XFormsSelect1Control) ? ((XFormsSelect1Control) xformsControl).getGroupName() : effectiveId;
                reusableAttributes.addAttribute("", "name", "name", ContentHandlerHelper.CDATA, name);

                reusableAttributes.addAttribute("", "value", "value", ContentHandlerHelper.CDATA, item.getExternalValue(pipelineContext));

                if (!handlerContext.isTemplate() && xformsControl != null) {

                    if (isSelected) {
                        reusableAttributes.addAttribute("", "checked", "checked", ContentHandlerHelper.CDATA, "checked");
                    }

                    if (isFirst) {
                        // Handle accessibility attributes
                        handleAccessibilityAttributes(attributes, reusableAttributes);
                    }
                }

                handleReadOnlyAttribute(reusableAttributes, containingDocument, xformsControl);
                contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "input", inputQName, reusableAttributes);
                contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "input", inputQName);
            }
            contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);

            // We don't output the label within <input></input>, because XHTML won't display it.

            final String label = item.getLabel();
            reusableAttributes.clear();
            outputLabelFor(handlerContext, reusableAttributes, itemEffectiveId, itemEffectiveId, LLHAC.LABEL, "label", label, false);// TODO: may be HTML for full appearance
        }

        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
    }

    private void handleItemCompact(ContentHandler contentHandler, String optionQName, XFormsValueControl xformsControl,
                                   boolean isMultiple, Item item) throws SAXException {

        final String optionValue = item.getValue();
        final AttributesImpl optionAttributes = getAttributes(new AttributesImpl(), null, null);

        optionAttributes.addAttribute("", "value", "value", ContentHandlerHelper.CDATA, item.getExternalValue(pipelineContext));

        // Figure out whether what items are selected
        if (!handlerContext.isTemplate() && xformsControl != null) {
            final String controlValue = xformsControl.getValue(pipelineContext);
            final boolean selected = (controlValue != null) && XFormsItemUtils.isSelected(isMultiple, controlValue, optionValue);
            if (selected)
                optionAttributes.addAttribute("", "selected", "selected", ContentHandlerHelper.CDATA, "selected");
        }

        // xhtml:option
        contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "option", optionQName, optionAttributes);
        final String label = item.getLabel();
        if (label != null)
            contentHandler.characters(label.toCharArray(), 0, label.length());
        contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "option", optionQName);
    }

    @Override
    protected String getForEffectiveId(String effectiveId) {
        // For full appearance we don't put a @for attribute so that selecting the main label doesn't select the item
        return isFull ? null : super.getForEffectiveId(effectiveId);
    }

    @Override
    protected void handleLabel(String staticId, String effectiveId, Attributes attributes, XFormsSingleNodeControl xformsControl, boolean isTemplate) throws SAXException {
        if (isStaticReadonly(xformsControl) || !isFull || !handlerContext.isNoScript()) {
            // In noscript mode for full items, this is handled by fieldset/legend
            super.handleLabel(staticId, effectiveId, attributes, xformsControl, isTemplate);
        }
    }
}
