/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.control.controls;

import org.dom4j.Element;
import org.orbeon.oxf.xforms.control.FocusableTrait;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.xbl.XBLContainer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents an xforms:trigger control.
 *
 * TODO: Use inheritance/interface to make this a single-node control that doesn't hold a value.
 */
public class XFormsTriggerControl extends XFormsSingleNodeControl implements FocusableTrait {
    public XFormsTriggerControl(XBLContainer container, XFormsControl parent, Element element, String id, Map<String, String> state) {
        super(container, parent, element, id);
    }

    private static boolean[] TRIGGER_LHHA_HTML_SUPPORT = { true, true, false, true };

    @Override
    public boolean[] lhhaHTMLSupport() {
        return TRIGGER_LHHA_HTML_SUPPORT;
    }

    @Override
    public boolean computeRelevant() {
        // NOTE: We used to make the trigger non-relevant if it was static-readonly. But this caused issues:
        //
        // o at the time computeRelevant() is called, MIPs haven't been read yet
        // o even if we specially read the readonly value from the binding here, then:
        //   o the static-readonly control becomes non-relevant
        //   o therefore its readonly value becomes false (the default)
        //   o therefore isStaticReadonly() returns false!
        //
        // So we keep the control relevant in this case.
        return super.computeRelevant();
    }

    private static final Set<String> ALLOWED_EXTERNAL_EVENTS = new HashSet<String>();
    static {
        ALLOWED_EXTERNAL_EVENTS.add(XFormsEvents.XFORMS_FOCUS);
        ALLOWED_EXTERNAL_EVENTS.add(XFormsEvents.XFORMS_HELP);
        ALLOWED_EXTERNAL_EVENTS.add(XFormsEvents.DOM_ACTIVATE);
        ALLOWED_EXTERNAL_EVENTS.add(XFormsEvents.XXFORMS_VALUE_OR_ACTIVATE);// for noscript mode
    }

    @Override
    public Set<String> getAllowedExternalEvents() {
        return ALLOWED_EXTERNAL_EVENTS;
    }

    @Override
    public boolean supportAjaxUpdates() {
        // Don't output anything for triggers in static readonly mode
        return ! isStaticReadonly();
    }
}
