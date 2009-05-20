package org.orbeon.oxf.xforms;

import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.dom4j.DocumentWrapper;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.expr.LastPositionFinder;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.om.SequenceIterator;
import org.orbeon.saxon.om.ValueRepresentation;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.value.EmptySequence;
import org.orbeon.saxon.value.SequenceExtent;
import org.orbeon.saxon.value.StringValue;

import java.util.List;

/**
 * Represents an exforms:variable / xxforms:variable element.
 */
public class Variable {

    private XFormsContainer container;
    private XFormsContextStack contextStack;
    private Element variableElement;

    private String variableName;
    private String selectAttribute;

    private boolean evaluated;
    private ValueRepresentation variableValue;

    public Variable(XFormsContainer container, XFormsContextStack contextStack, Element variableElement) {
        this.container = container;
        this.contextStack = contextStack;
        this.variableElement = variableElement;

        this.variableName = variableElement.attributeValue("name");
        if (variableName == null)
            throw new ValidationException("xxforms:variable or exforms:variable element must have a \"name\" attribute", getLocationData());

        this.selectAttribute = variableElement.attributeValue("select");
//        if (selectAttribute == null)
//            throw new ValidationException("xxforms:variable or exforms:variable element must have a \"select\" attribute", getLocationData());
    }

    private void evaluate(PipelineContext pipelineContext, boolean useCache) {

        if (selectAttribute == null) {
            // Inline constructor (for now, only textual content, but in the future, we could allow xforms:output in it? more?)
            variableValue = new StringValue(variableElement.getStringValue());
        } else {
            // There is a select attribute

            // Push binding for evaluation, so that @context and @model are evaluated
            contextStack.pushBinding(pipelineContext, variableElement);
            {
                final XFormsContextStack.BindingContext bindingContext = contextStack.getCurrentBindingContext();
                final List currentNodeset = bindingContext.getNodeset();
                if (currentNodeset != null && currentNodeset.size() > 0) {
                    // TODO: in the future, we should allow null context for expressions that do not depend on the context
                    variableValue = XPathCache.evaluateAsExtent(pipelineContext,
                            currentNodeset, bindingContext.getPosition(),
                            selectAttribute, container.getNamespaceMappings(variableElement), bindingContext.getInScopeVariables(useCache),
                            XFormsContainingDocument.getFunctionLibrary(), contextStack.getFunctionContext(), null, getLocationData());
                } else {
                    variableValue = EmptySequence.getInstance();
                }
            }
            contextStack.popBinding();
        }
    }

    public String getVariableName() {
        return variableName;
    }

    public ValueRepresentation getVariableValue(PipelineContext pipelineContext, boolean useCache) {
        // Make sure the variable is evaluated
        if (!evaluated) {
            evaluated = true;
            evaluate(pipelineContext, useCache);
        }

        // Return value and rewrap if necessary
        if (variableValue instanceof SequenceExtent) {
            // Rewrap NodeWrapper contained in the variable value. Not the most efficient, but at this point we have to
            // to ensure that things work properly. See RewrappingSequenceIterator for more details.
            try {
                return new SequenceExtent(new RewrappingSequenceIterator(((SequenceExtent) variableValue).iterate(null)));
            } catch (XPathException e) {
                // Should not happen with SequenceExtent
                throw new OXFException(e);
            }
        } else {
            // Return value as is
            return variableValue;
        }
    }

    public LocationData getLocationData() {
        return (variableElement != null) ? (LocationData) variableElement.getData() : null;
    }

    public void testAs() {
//        final String testAs = "element(foobar)";
//        new XSLVariable().makeSequenceType(testAs);
    }

    /**
     * This iterator rewrapps NodeWrapper elements so that the original NodeWrapper is discarded and a new one created.
     * The reason we do this is that when we keep variables around, we don't want NodeWrapper.index to be set to
     * anything but -1. If we did that, then upon insertions of nodes in the DOM, the index would be out of date.
     */
    private static class RewrappingSequenceIterator implements SequenceIterator, LastPositionFinder {

        private SequenceIterator iter;
        private Item current;

        public RewrappingSequenceIterator(SequenceIterator iter) {
            this.iter = iter;
        }

        public Item next() throws XPathException {
            final Item item = iter.next();

            if (item instanceof NodeWrapper) {
                // Rewrap
                final NodeWrapper nodeWrapper = (NodeWrapper) item;
                final DocumentWrapper documentWrapper = (DocumentWrapper) nodeWrapper.getDocumentRoot();

                current = documentWrapper.wrap(nodeWrapper.getUnderlyingNode());
            } else {
                // Pass through
                current = item;
            }

            return current;
        }

        public Item current() {
            return current;
        }

        public int position() {
            return iter.position();
        }

        public SequenceIterator getAnother() throws XPathException {
            return new RewrappingSequenceIterator(iter.getAnother());
        }

        public int getProperties() {
            return iter.getProperties();
        }

        public int getLastPosition() throws XPathException {
            if (iter instanceof LastPositionFinder)
                return ((LastPositionFinder) iter).getLastPosition();
            throw new OXFException("Call to getLastPosition() and nested iterator is not a LastPositionFinder.");
        }
    }
}
