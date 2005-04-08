/**
 *  Copyright (C) 2004 Orbeon, Inc.
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
package org.orbeon.oxf.processor.xforms.input.action;

import org.dom4j.Document;
import org.dom4j.Element;
import org.jaxen.FunctionContext;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.xforms.output.InstanceData;
import org.orbeon.oxf.processor.xforms.XFormsUtils;
import org.orbeon.oxf.util.SecureUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Insert implements Action {

    private static final String POSITION_BEFORE = "before";
    private static final String POSITION_AFTER = "after";

    private String nodeset;
    private String at;
    private String position;

    public void setParameters(Map parameters) {
        nodeset = (String) parameters.get(NODESET_ATTRIBUTE_NAME);
        at = (String) parameters.get(AT_ATTRIBUTE_NAME);
        position = (String) parameters.get(POSITION_ATTRIBUTE_NAME);
    }

    public void run(PipelineContext context, FunctionContext functionContext, String encryptionPassword, Document instance) {

        String[] ids = nodeset.split(" ");
        Map idToNodeMap = ((InstanceData) instance.getRootElement().getData()).getIdToNodeMap();

        // Get element to duplicate (last one in the nodeset)
        final Element elementToDuplicate;
        {
            if (ids.length == 0)
                throw new OXFException("nodeset attribute in insert action must return a non-empty nodeset");
            String lastIndex = ids[ids.length - 1];
            Integer lastIndexInt;
            if(XFormsUtils.isNameEncryptionEnabled())
                lastIndexInt = new Integer(SecureUtils.decrypt(context, encryptionPassword, lastIndex));
            else
                lastIndexInt = new Integer(lastIndex);

            Object lastNode = idToNodeMap.get(lastIndexInt);
            if (!(lastNode instanceof Element))
                throw new OXFException("last node in nodeset attribute from insert action must must be an element");
            elementToDuplicate = (Element) lastNode;
        }

        // Determine where to insert the duplicated element
        if (XFormsUtils.isNameEncryptionEnabled())
            at = SecureUtils.decrypt(context, encryptionPassword, at);
        int atValue = Integer.parseInt(at) - 1;
        if (atValue < 0) atValue = 0;
        if (atValue >= ids.length) atValue = ids.length - 1;

        // Get element at "at" position
        final Element atElement;
        {
            String id = ids[atValue];
            if (XFormsUtils.isNameEncryptionEnabled())
                id = SecureUtils.decrypt(context, encryptionPassword, id);
            Object atNode = idToNodeMap.get(new Integer(id));
            if (!(atNode instanceof Element))
                throw new OXFException("node pointed by 'at' position in nodeset attribute from"
                        + " insert action must must be an element");
            atElement = (Element) atNode;
        }


        // Locate position of atElement (element pointed by "at") among his siblings
        int atElementIndex = 0;
        List atElementSiblings = atElement.getParent().elements();
        {
            boolean found = false;
            for (Iterator i = atElementSiblings.iterator(); i.hasNext();) {
                if (i.next() == atElement) {
                    found = true;
                    break;
                }
                atElementIndex++;
            }
            if (!found)
                throw new OXFException("Nodeset defined by '" + nodeset + "' is not homogeneous");
        }

        // Actually do the insertion
        atElementSiblings.add(POSITION_BEFORE.equals(position) ? atElementIndex : atElementIndex + 1,
                ( org.dom4j.Element )elementToDuplicate.clone() );
    }
}
