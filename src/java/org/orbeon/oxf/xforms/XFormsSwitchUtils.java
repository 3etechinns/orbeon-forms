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
package org.orbeon.oxf.xforms;

import org.dom4j.Attribute;
import org.dom4j.Element;
import org.dom4j.Node;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.controls.RepeatIterationControl;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatControl;
import org.orbeon.saxon.om.NodeInfo;

import java.util.*;

/**
 * Utilities related to xforms:switch.
 */
public class XFormsSwitchUtils {

    /**
     * Prepare switch information before a modification to the DOM.
     */
    public static boolean prepareSwitches(XFormsControls xformsControls) {
        // Store temporary switch information into appropriate nodes
        boolean found = false;
        final XFormsControls.SwitchState currentSwitchState = xformsControls.getCurrentSwitchState();
        if (currentSwitchState != null) {
            for (Iterator i = currentSwitchState.getSwitchIdToSelectedCaseIdMap().entrySet().iterator(); i.hasNext();) {
                final Map.Entry entry = (Map.Entry) i.next();
                final String switchId = (String) entry.getKey();

    //            System.out.println("xxx 1: switch id: " + switchId);

                if (switchId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1) {
                    // This switch id may be affected by this insertion

    //                System.out.println("xxx 1: has separator");

                    final XFormsControl switchXFormsControl = (XFormsControl) xformsControls.getObjectByEffectiveId(switchId);
                    XFormsControl parent = switchXFormsControl;
                    while ((parent = parent.getParent()) != null) {
                        if (parent instanceof RepeatIterationControl) {
                            // Found closest enclosing repeat iteration

                            final RepeatIterationControl repeatIterationInfo = (RepeatIterationControl) parent;
                            final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) repeatIterationInfo.getParent();

                            final List currentNodeset = repeatControlInfo.getBindingContext().getNodeset();

                            final NodeInfo nodeInfo = (NodeInfo) currentNodeset.get(repeatIterationInfo.getIteration() - 1);

                            // Store an original case id instead of an effective case id
                            final String caseId = (String) entry.getValue();
                            InstanceData.addSwitchIdToCaseId(nodeInfo, switchXFormsControl.getId(), caseId.substring(0, caseId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1)));

    //                        System.out.println("xxx 1: adding case id " + switchControlInfo.getOriginalId() + " " + entry.getValue());

                            found = true;

                            break;
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Prepare switch information before a modification to the DOM.
     */
    public static void prepareSwitches(XFormsControls xformsControls, Node sourceNode, Node clonedNode) {
        final boolean found = prepareSwitches(xformsControls);
        if (found) {
            // Propagate temporary switch information to new nodes
            copySwitchInfo(sourceNode, clonedNode);
        }
    }

    private static void copySwitchInfo(Node sourceNode, Node destNode) {

        {
            // NOTE: We create a new HashMap because each node must keep separate switch information
            final Map existingSwitchIdsToCaseIds = InstanceData.getSwitchIdsToCaseIds(sourceNode);
            InstanceData.setSwitchIdsToCaseIds(destNode, (existingSwitchIdsToCaseIds != null) ? new HashMap(existingSwitchIdsToCaseIds) : null);
        }

        if (sourceNode instanceof Element) {
            final Element sourceElement = (Element) sourceNode;
            final Element destElement = (Element) destNode;
            // Recurse over attributes
            {
                final Iterator j = destElement.attributes().iterator();
                for (Iterator i = sourceElement.attributes().iterator(); i.hasNext();) {
                    final Attribute sourceAttribute = (Attribute) i.next();
                    final Attribute destAttribute = (Attribute) j.next();

                    {
                        // NOTE: We create a new HashMap because each node must keep separate switch information
                        final Map existingSwitchIdsToCaseIds = InstanceData.getSwitchIdsToCaseIds(sourceAttribute);
                        InstanceData.setSwitchIdsToCaseIds(destAttribute, (existingSwitchIdsToCaseIds != null) ? new HashMap(existingSwitchIdsToCaseIds) : null);
                    }
                }
            }
            // Recurse over children elements
            {
                final Iterator j = destElement.elements().iterator();
                for (Iterator i = sourceElement.elements().iterator(); i.hasNext();) {
                    final Element sourceChild = (Element) i.next();
                    final Element destChild = (Element) j.next();
                    copySwitchInfo(sourceChild, destChild);
                }
            }
        }
    }

    /**
     * Update switch information after a modification to the DOM.
     */
    public static void updateSwitches(XFormsControls xformsControls) {
        final XFormsControls.SwitchState currentSwitchState = xformsControls.getCurrentSwitchState();
        if (currentSwitchState != null) {
            for (Iterator i = currentSwitchState.getSwitchIdToSelectedCaseIdMap().entrySet().iterator(); i.hasNext();) {
                final Map.Entry entry = (Map.Entry) i.next();
                final String switchId = (String) entry.getKey();

    //            System.out.println("xxx 2: switch id: " + switchId);

                if (switchId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1) {
                    // This switch id may be affected by this insertion

    //                System.out.println("xxx 2: has separator");

                    final XFormsControl switchXFormsControl = (XFormsControl) xformsControls.getObjectByEffectiveId(switchId);
                    XFormsControl parent = switchXFormsControl;
                    while ((parent = parent.getParent()) != null) {
                        if (parent instanceof RepeatIterationControl) {
                            // Found closest enclosing repeat iteration

                            final RepeatIterationControl repeatIterationInfo = (RepeatIterationControl) parent;
                            final XFormsRepeatControl repeatControlInfo = (XFormsRepeatControl) repeatIterationInfo.getParent();

                            final List currentNodeset = repeatControlInfo.getBindingContext().getNodeset();

                            final NodeInfo nodeInfo = (NodeInfo) currentNodeset.get(repeatIterationInfo.getIteration() - 1);

                            final String caseId = InstanceData.getCaseIdForSwitchId(nodeInfo, switchXFormsControl.getId());

    //                        System.out.println("xxx 2: found case id " + caseId);

                            if (caseId != null) {
                                // Set effective case id
                                final String effectiveCaseId = caseId + switchId.substring(switchId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1));
    //                            System.out.println("xxx 2: setting case id " + effectiveCaseId);
                                entry.setValue(effectiveCaseId);
                            }

                            break;
                        }
                    }
                }
            }
        }
    }
}
