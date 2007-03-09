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
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsControls;
import org.dom4j.Element;

import java.util.Map;

/**
 * Represents an xforms:case pseudo-control.
 */
public class XFormsCaseControl extends XFormsControl {
    public XFormsCaseControl(XFormsContainingDocument containingDocument, XFormsControl parent, Element element, String name, String id) {
        super(containingDocument, parent, element, name, id);
    }

    public boolean isSelected() {

        final XFormsControl switchControl = getParent();
        final XFormsControls.SwitchState switchState = containingDocument.getXFormsControls().getCurrentSwitchState();

        final Map switchIdToSelectedCaseIdMap = switchState.getSwitchIdToSelectedCaseIdMap();
        final String selectedCaseId = (String) switchIdToSelectedCaseIdMap.get(switchControl.getEffectiveId());

        return getEffectiveId().equals(selectedCaseId);
    }

    public boolean isVisible() {
        final XFormsControl switchControl = getParent();
        return isSelected() || switchControl.isStaticReadonly();
    }
}
