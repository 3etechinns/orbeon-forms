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

import org.orbeon.oxf.xforms.control.controls.XFormsSwitchControl;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Handle xforms:switch.
 */
public class XFormsSwitchHandler extends HandlerBase {

    protected String effectiveSwitchId;

    public XFormsSwitchHandler() {
        super(false, false);
    }

    public void start(String uri, String localname, String qName, Attributes attributes) throws SAXException {
        effectiveSwitchId = handlerContext.getEffectiveId(attributes);

        // Find classes to add
        final StringBuffer classes = getInitialClasses(localname, attributes, null);
        final XFormsSwitchControl switchXFormsControl = ((XFormsSwitchControl) containingDocument.getObjectById(effectiveSwitchId));
        HandlerBase.handleMIPClasses(classes, switchXFormsControl);

        // Start xhtml:span
        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");
        handlerContext.getController().getOutput().startElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName, getAttributes(attributes, classes.toString(), effectiveSwitchId));
    }

    public void end(String uri, String localname, String qName) throws SAXException {
        // Close xhtml:span
        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String spanQName = XMLUtils.buildQName(xhtmlPrefix, "span");
        handlerContext.getController().getOutput().endElement(XMLConstants.XHTML_NAMESPACE_URI, "span", spanQName);
    }
}
