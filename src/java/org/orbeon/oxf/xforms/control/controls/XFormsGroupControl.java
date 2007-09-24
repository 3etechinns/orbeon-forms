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
package org.orbeon.oxf.xforms.control.controls;

import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.dom4j.Element;

/**
 * Represents an xforms:group pseudo-control.
 */
public class XFormsGroupControl extends XFormsSingleNodeControl {

    public static final String INTERNAL_APPEARANCE = Dom4jUtils.qNameToexplodedQName(XFormsConstants.XXFORMS_INTERNAL_APPEARANCE_QNAME);

    public XFormsGroupControl(XFormsContainingDocument containingDocument, XFormsControl parent, Element element, String name, String id) {
        super(containingDocument, parent, element, name, id);
    }

    public String getType() {
        return null;
    }
}
