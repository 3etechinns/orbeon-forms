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
import org.orbeon.oxf.xforms.event.XFormsEvent;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.event.events.XXFormsDialogOpenEvent;
import org.orbeon.oxf.xforms.event.events.XXFormsDialogCloseEvent;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.dom4j.Element;

/**
 * Represents an extension xxforms:dialog control
 */
public class XXFormsDialogControl extends XFormsControl {

    private String level;
    private boolean close;
    private boolean draggable;
    private String neighborControlId;

    public XXFormsDialogControl(XFormsContainingDocument containingDocument, XFormsControl parent, Element element, String name, String effectiveId) {
        super(containingDocument, parent, element, name, effectiveId);
        this.level = element.attributeValue("level");
        if (this.level == null)
            this.level = "modal";
        this.close = !"false".equals(element.attributeValue("close"));
        this.draggable = !"false".equals(element.attributeValue("draggable"));
        this.neighborControlId = element.attributeValue("neighbor");
    }

    public boolean hasJavaScriptInitialization() {
        return true;
    }

    public String getLevel() {
        return level;
    }

    public boolean isClose() {
        return close;
    }


    public boolean isDraggable() {
        return draggable;
    }


    public String getNeighborControlId() {
        return neighborControlId;
    }

    public void performDefaultAction(PipelineContext pipelineContext, XFormsEvent event) {
        if (XFormsEvents.XXFORMS_DIALOG_CLOSE.equals(event.getEventName())) {
            // Close the dialog
            final XXFormsDialogCloseEvent dialogCloseEvent = (XXFormsDialogCloseEvent) event;
            final XXFormsDialogControl dialogControl = (XXFormsDialogControl) dialogCloseEvent.getTargetObject();

            final XFormsControls xformsControls = containingDocument.getXFormsControls();
            xformsControls.showHideDialog(dialogControl.getEffectiveId(), false, null);
        } else if (XFormsEvents.XXFORMS_DIALOG_OPEN.equals(event.getEventName())) {
            // Open the dialog
            final XXFormsDialogOpenEvent dialogOpenEvent = (XXFormsDialogOpenEvent) event;
            final XXFormsDialogControl dialogControl = (XXFormsDialogControl) dialogOpenEvent.getTargetObject();

            final XFormsControls xformsControls = containingDocument.getXFormsControls();
            xformsControls.showHideDialog(dialogControl.getEffectiveId(), true, dialogOpenEvent.getNeighbor());
        }
        super.performDefaultAction(pipelineContext, event);
    }
}
