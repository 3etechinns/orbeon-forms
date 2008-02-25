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
package org.orbeon.oxf.xml.dom4j;

import org.dom4j.*;
import org.dom4j.io.DocumentSource;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.processor.generator.DOMGenerator;
import org.orbeon.oxf.xml.NamespaceCleanupContentHandler;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.resources.OXFProperties;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParser;
import java.io.*;
import java.util.*;

/**
 * Collection of util routines for working with DOM4J.  In particular offers many methods found
 * in DocumentHelper.  The difference between these 'copied' methods and the orginals is that
 * our copies use our NonLazyUserData* classes. ( As opposed to DOM4J's defaults or whatever
 * happens to be specied in DOM4J's system property. )
 */
public class Dom4jUtils {


    /**
     * 03/30/2005 d : Currently DOM4J doesn't really support read only documents.  ( No real
     * checks in place.  If/when DOM4J adds real support then NULL_DOCUMENt should be made a
     * read only document.
     */
    public static final Document NULL_DOCUMENT;

    static {
        NULL_DOCUMENT = new NonLazyUserDataDocument();
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        Element nullElement = factory.createElement("null");
        nullElement.addAttribute(XMLConstants.XSI_NIL_QNAME, "true");
        NULL_DOCUMENT.setRootElement(nullElement);
    }

    private static SAXReader createSAXReader(boolean validating, boolean handleXInclude) throws SAXException {
        final SAXParser sxPrs = XMLUtils.newSAXParser(validating, handleXInclude);
        final XMLReader xr = sxPrs.getXMLReader();
        final SAXReader sxRdr = new SAXReader(xr);
        final NonLazyUserDataDocumentFactory fctry = NonLazyUserDataDocumentFactory.getInstance14();
        sxRdr.setDocumentFactory(fctry);
        return sxRdr;
    }

    private static SAXReader createSAXReader() throws SAXException {
        final SAXParser sxPrs = XMLUtils.newSAXParser();
        final XMLReader xr = sxPrs.getXMLReader();
        final SAXReader sxRdr = new SAXReader(xr);
        final NonLazyUserDataDocumentFactory fctry = NonLazyUserDataDocumentFactory.getInstance14();
        sxRdr.setDocumentFactory(fctry);
        return sxRdr;
    }

    private static String domToString
            (final Node n, final OutputFormat frmt) {
        try {
            final StringWriter wrtr = new StringWriter();
            // Ugh, XMLWriter doesn't accept null formatter _and_ default 
            // formatter is protected.
            final XMLWriter xmlWrtr
                    = frmt == null
                    ? new XMLWriter(wrtr) : new XMLWriter(wrtr, frmt);
            xmlWrtr.write(n);
            xmlWrtr.close();
            return wrtr.toString();
        } catch (final IOException e) {
            throw new OXFException(e);
        }
    }

    private static String domToString(final Branch brnch, final boolean trm, final boolean cmpct) {
        final OutputFormat frmt = new OutputFormat();
        if (cmpct) {
            frmt.setIndent(false);
            frmt.setNewlines(false);
            frmt.setTrimText(true);
        } else {
            frmt.setIndentSize(4);
            frmt.setNewlines(trm);
            frmt.setTrimText(trm);
        }
        return domToString(brnch, frmt);
    }


    /*
     * Replacement for DocumentHelper.parseText. DocumentHelper.parseText is not used since it
     * creates work for GC (because it relies on JAXP).
     */
    public static Document readDom4j(final String xmlString) throws SAXException, DocumentException {
        final StringReader stringReader = new StringReader(xmlString);
        return readDom4j(stringReader);
    }

    public static Document readDom4j(final Reader reader) throws SAXException, DocumentException {
        final SAXReader saxReader = createSAXReader();
        return saxReader.read(reader);
    }

    public static Document readDom4j(final Reader reader, final String uri) throws SAXException, DocumentException {
        final SAXReader saxReader = createSAXReader();
        return saxReader.read(reader, uri);
    }

    public static Document readDom4j(final InputStream inputStream) throws SAXException, DocumentException {
        // TODO: See TransformerUtils.readDom4j() instead and remove?
        final SAXReader saxReader = createSAXReader();
        return saxReader.read(inputStream);
    }

    public static Document readDom4j(final InputStream inputStream, final String uri, boolean validating, boolean handleXInclude) throws SAXException, DocumentException {
        final SAXReader saxReader = createSAXReader(validating, handleXInclude);
        return saxReader.read(inputStream, uri);
    }

    /**
     * Removes the elements and text inside the given element, but not the attributes or namespace
     * declarations on the element.
     */
    public static void clearElementContent(final Element elt) {
        final java.util.List cntnt = elt.content();
        for (final java.util.ListIterator j = cntnt.listIterator();
             j.hasNext();) {
            final Node chld = (Node) j.next();
            if (chld.getNodeType() == Node.TEXT_NODE
                    || chld.getNodeType() == Node.ELEMENT_NODE) {
                j.remove();
            }
        }
    }

    public static String makeSystemId(final Document d) {
        final Element e = d.getRootElement();
        return makeSystemId(e);
    }

    public static String makeSystemId(final Element e) {
        final LocationData ld = (LocationData) e.getData();
        final String ldSid = ld == null ? null : ld.getSystemID();
        return ldSid == null ? DOMGenerator.DefaultContext : ldSid;
    }

    /*
        *  Convert the result of XPathUtils.selectObjectValue() to a string
        */
    public static String objectToString(Object o) {
        StringBuffer buff = new StringBuffer();
        if (o instanceof List) {
            for (Iterator i = ((List) o).iterator(); i.hasNext();) {
                // this will be a node
                buff.append(objectToString(i.next()));
            }
        } else if (o instanceof Element) {
            buff.append(((Element) o).asXML());
        } else if (o instanceof Node) {
            buff.append(((Node) o).asXML());
        } else if (o instanceof String)
            buff.append((String) o);
        else if (o instanceof Number)
            buff.append((Number) o);
        else
            throw new OXFException("Should never happen");
        return buff.toString();
    }

    public static String domToString(final Element e, final boolean trm, final boolean cmpct) {
        return Dom4jUtils.domToString((Branch) e, trm, cmpct);
    }

    public static String domToString(final Element e) {
        return domToString(e, true, false);
    }

    public static String domToString(final Document d, final boolean trm, final boolean cmpct) {
        final Element relt = d.getRootElement();
        return domToString(relt, trm, cmpct);
    }

    public static String domToString(final Document d) {
        return domToString(d, true, false);
    }

    public static String domToString(final Text txt, final boolean t, final boolean c) {
        return txt.getText();
    }

    public static String domToString(final Text t) {
        return domToString(t, true, false);
    }

    /**
     * Checks type of n and, if apropriate, downcasts and returns
     * domToString( ( Type )n, t, c ).  Otherwise returns domToString( n, null )
     */
    public static String domToString(final Node n, final boolean t, final boolean c) {
        final String ret;
        switch (n.getNodeType()) {
            case Node.DOCUMENT_NODE: {
                ret = domToString((Document) n, t, c);
                break;
            }
            case Node.ELEMENT_NODE: {
                ret = domToString((Element) n, t, c);
                break;
            }
            case Node.TEXT_NODE: {
                ret = domToString((Text) n, t, c);
                break;
            }
            default :
                ret = domToString(n, null);
                break;
        }
        return ret;
    }

    public static String domToString(final Node nd) {
        return domToString(nd, true, false);
    }

    /**
     * Go over the Node and its children and make sure that there are no two contiguous text nodes so as to ensure that
     * XPath expressions run correctly. As per XPath 1.0 (http://www.w3.org/TR/xpath):
     *
     * "As much character data as possible is grouped into each text node: a text node never has an immediately
     * following or preceding sibling that is a text node. "
     *
     * @param nodeToNormalize Node hiearchy to normalize
     * @return                the input node, normalized
     */
    public static Node normalizeTextNodes(Node nodeToNormalize) {
        final List nodesToDetatch = new ArrayList();
        nodeToNormalize.accept(new VisitorSupport() {
            public void visit(Element element) {
                final List children = element.content();
                Node previousNode = null;
                StringBuffer sb = null;
                for (Iterator i = children.iterator(); i.hasNext();) {
                    final Node currentNode = (Node) i.next();
                    if (previousNode != null) {
                        if (previousNode instanceof Text && currentNode instanceof Text) {
                            final Text previousNodeText = (Text) previousNode;
                            if (sb == null)
                                sb = new StringBuffer(previousNodeText.getText());
                            sb.append(((Text) currentNode).getText());
                            nodesToDetatch.add(currentNode);
                        } else if (previousNode instanceof Text && !(currentNode instanceof Text)) {
                            // Update node if needed
                            if (sb != null) {
                                previousNode.setText(sb.toString());
                            }
                            previousNode = currentNode;
                            sb = null;
                        } else {
                            previousNode = currentNode;
                            sb = null;
                        }
                    } else {
                        previousNode = currentNode;
                        sb = null;
                    }
                }
                // Update node if needed
                if (previousNode != null && sb != null) {
                    previousNode.setText(sb.toString());
                }
            }
        });
        // Detach nodes only in the end so as to not confuse the acceptor above
        for (Iterator i = nodesToDetatch.iterator(); i.hasNext();) {
            final Node currentNode = (Node) i.next();
            currentNode.detach();
        }

        return nodeToNormalize;
    }

    public static DocumentSource getDocumentSource(final Document d) {
        /*
         * Saxon's error handler is expensive for the service it provides so we just use our 
         * singeton intead. 
         * 
         * Wrt expensive, delta in heap dump info below is amount of bytes allocated during the 
         * handling of a single request to '/' in the examples app. i.e. The trace below was 
         * responsible for creating 200k of garbage during the handing of a single request to '/'.
         * 
         * delta: 213408 live: 853632 alloc: 4497984 trace: 380739 class: byte[]
         * 
         * TRACE 380739:
         * java.nio.HeapByteBuffer.<init>(HeapByteBuffer.java:39)
         * java.nio.ByteBuffer.allocate(ByteBuffer.java:312)
         * sun.nio.cs.StreamEncoder$CharsetSE.<init>(StreamEncoder.java:310)
         * sun.nio.cs.StreamEncoder$CharsetSE.<init>(StreamEncoder.java:290)
         * sun.nio.cs.StreamEncoder$CharsetSE.<init>(StreamEncoder.java:274)
         * sun.nio.cs.StreamEncoder.forOutputStreamWriter(StreamEncoder.java:69)
         * java.io.OutputStreamWriter.<init>(OutputStreamWriter.java:93)
         * java.io.PrintWriter.<init>(PrintWriter.java:109)
         * java.io.PrintWriter.<init>(PrintWriter.java:92)
         * org.orbeon.saxon.StandardErrorHandler.<init>(StandardErrorHandler.java:22)
         * org.orbeon.saxon.event.Sender.sendSAXSource(Sender.java:165)
         * org.orbeon.saxon.event.Sender.send(Sender.java:94)
         * org.orbeon.saxon.IdentityTransformer.transform(IdentityTransformer.java:31)
         * org.orbeon.oxf.xml.XMLUtils.getDigest(XMLUtils.java:453)
         * org.orbeon.oxf.xml.XMLUtils.getDigest(XMLUtils.java:423)
         * org.orbeon.oxf.processor.generator.DOMGenerator.<init>(DOMGenerator.java:93)         
         *
         * Before mod
         *
         * 1.4.2_06-b03 	P4 2.6 Ghz	/ 	50 th	tc 4.1.30	10510 ms ( 150 mb ), 7124 ( 512 mb ) 	2.131312472239924 ( 150 mb ), 1.7474380872589803 ( 512 mb )
         *
         * after mod
         *
         * 1.4.2_06-b03 	P4 2.6 Ghz	/ 	50 th	tc 4.1.30	9154 ms ( 150 mb ), 6949 ( 512 mb ) 	1.7316203642295738 ( 150 mb ), 1.479365288194895 ( 512 mb )
         *
         */
        final LocationDocumentSource lds = new LocationDocumentSource(d);
        final XMLReader rdr = lds.getXMLReader();
        rdr.setErrorHandler(XMLUtils.ERROR_HANDLER);
        return lds;
    }

    public static byte[] getDigest(Document document) {
        final DocumentSource ds = getDocumentSource(document);
        return XMLUtils.getDigest(ds);
    }

    /**
     * Clean-up namespaces. Some tools generate namespace "un-declarations" or
     * the form xmlns:abc="". While this is needed to keep the XML infoset
     * correct, it is illegal to generate such declarations in XML 1.0 (but it
     * is legal in XML 1.1). Technically, this cleanup is incorrect at the DOM
     * and SAX level, so this should be used only in rare occasions, when
     * serializing certain documents to XML 1.0.
     */
    public static Document adjustNamespaces
            (Document document, boolean xml11) {
        if (xml11)
            return document;
        LocationSAXWriter writer = new LocationSAXWriter();
        LocationSAXContentHandler ch = new LocationSAXContentHandler();
        writer.setContentHandler(new NamespaceCleanupContentHandler(ch, xml11));
        try {
            writer.write(document);
        } catch (SAXException e) {
            throw new OXFException(e);
        }
        return ch.getDocument();
    }

    /**
     * Return a Map of namespaces in scope on the given element.
     */
    public static Map getNamespaceContext(Element element) {
        final Map namespaces = new HashMap();
        for (Element currentNode = element; currentNode != null; currentNode = currentNode.getParent()) {
            final List currentNamespaces = currentNode.declaredNamespaces();
            for (Iterator j = currentNamespaces.iterator(); j.hasNext();) {
                final Namespace namespace = (Namespace) j.next();
                if (!namespaces.containsKey(namespace.getPrefix()))
                    namespaces.put(namespace.getPrefix(), namespace.getURI());
            }
        }
        return namespaces;
    }

    /**
     * Return a Map of namespaces in scope on the given element, without the default namespace.
     */
    public static Map getNamespaceContextNoDefault(Element element) {
        final Map namespaces = getNamespaceContext(element);
        namespaces.remove("");
        return namespaces;
    }

    /**
     * Extract a QName from an Element and an attribute name. The prefix of the QName must be in
     * scope. Return null if the attribute is not found.
     */
    public static QName extractAttributeValueQName(Element element, String attributeName) {
        return extractTextValueQName(element, element.attributeValue(attributeName));
    }

    /**
     * Extract a QName from an Element and an attribute QName. The prefix of the QName must be in
     * scope. Return null if the attribute is not found.
     */
    public static QName extractAttributeValueQName(Element element, QName attributeQName) {
        return extractTextValueQName(element, element.attributeValue(attributeQName));
    }

    /**
     * Extract a QName from an Element's string value. The prefix of the QName must be in scope.
     * Return null if the text is empty.
     */
    public static QName extractTextValueQName(Element element) {
        return extractTextValueQName(element, element.getStringValue());
    }

    /**
     * Extract a QName from an Element's string value. The prefix of the QName must be in scope.
     * Return null if the text is empty.
     *
     * @param element       Element containing the attribute
     * @param qNameString   QName to analyze
     * @return              a QName object or null if not found
     */
    public static QName extractTextValueQName(Element element, String qNameString) {
        if (qNameString == null)
            return null;
        qNameString = qNameString.trim();
        if (qNameString.length() == 0)
            return null;
        final Map namespaces = getNamespaceContext(element);
        final int colonIndex = qNameString.indexOf(':');
        final String prefix;
        final String localName;
        final String namespaceURI;
        if (colonIndex == -1) {
            prefix = "";
            localName = qNameString;
            final String nsURI = (String) namespaces.get(prefix);
            namespaceURI = nsURI == null ? "" : nsURI;
        } else {
            prefix = qNameString.substring(0, colonIndex);
            localName = qNameString.substring(colonIndex + 1);
            namespaceURI = (String) namespaces.get(prefix);
            if (namespaceURI == null) {
                throw new OXFException("No namespace declaration found for prefix: " + prefix);
            }
        }
        return new QName(localName, new Namespace(prefix, namespaceURI));
    }

    /**
     * Decode a String containing an exploded QName (also known as a "Clark name") into a QName.
     */
    public static QName explodedQNameToQName(String qName) {
        int openIndex = qName.indexOf("{");

        if (openIndex == -1)
            return new QName(qName);

        String namespaceURI = qName.substring(openIndex + 1, qName.indexOf("}"));
        String localName = qName.substring(qName.indexOf("}") + 1);
        return new QName(localName, new Namespace("p1", namespaceURI));
    }

    /**
     * Encode a QName to an exploded QName (also known as a "Clark name") String.
     */
    public static String qNameToexplodedQName(QName qName) {
        return (qName == null) ? null : XMLUtils.buildExplodedQName(qName.getNamespaceURI(), qName.getName());
    }

    public static XPath createXPath(final String expression) throws InvalidXPathException {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createXPath(expression);
    }

    public static Text createText(final String text) {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createText(text);
    }

    public static Element createElement(final String name) {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createElement(name);
    }

    public static Element createElement(final String qualifiedName, final String namespaceURI) {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createElement(qualifiedName, namespaceURI);
    }

    public static Attribute createAttribute(final QName qName, final String value) {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createAttribute(null, qName, value);
    }

    public static Namespace createNamespace(final String prefix, final String uri) {
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createNamespace(prefix, uri);
    }

    /**
     * Return a new document with a copy of newRoot as its root.
     */
    public static Document createDocument(final Element newRoot) {
        final Element copy = newRoot.createCopy();
        final NonLazyUserDataDocumentFactory factory = NonLazyUserDataDocumentFactory.getInstance14();
        return factory.createDocument(copy);
    }

    /**
     * Return a new document with a copy of newRoot as its root and all parent namespaces copied to
     * the new root element, assuming they are not already declared on the new root element.
     */
    public static Document createDocumentCopyParentNamespaces(final Element newRoot) {

        final Document document = Dom4jUtils.createDocument(newRoot);
        final Element rootElement = document.getRootElement();

        final Element parentElement = newRoot.getParent();
        final Map parentNamespaceContext = Dom4jUtils.getNamespaceContext(parentElement);
        final Map rootElementNamespaceContext = Dom4jUtils.getNamespaceContext(rootElement);

        for (Iterator k = parentNamespaceContext.keySet().iterator(); k.hasNext();) {
            final String prefix = (String) k.next();
            // NOTE: Don't use rootElement.getNamespaceForPrefix() because that will return the element prefix's
            // namespace even if there are no namespace nodes
            if (rootElementNamespaceContext.get(prefix) == null) {
                final String uri = (String) parentNamespaceContext.get(prefix);
                rootElement.addNamespace(prefix, uri);
            }
        }

        return document;
    }

    public static Document createDocument() {
        final NonLazyUserDataDocumentFactory fctry
                = NonLazyUserDataDocumentFactory.getInstance14();
        return fctry.createDocument();
    }

    /**
     * <!-- getFileAndLine -->
     * Workaround for Java's lack of an equivalent to C's __FILE__ and __LINE__ macros.  Use
     * carefully as it is not fast.
     * <p/>
     * Perhaps in 1.5 we will find a better way.
     *
     * @return LocationData of caller.
     */
    public static LocationData getLocationData() {
        return getLocationData(1, false);
    }

    public static LocationData getLocationData(final int depth, boolean isDebug) {
        // Enable this with a property for debugging only, as it is time consuming
        if (!isDebug && !OXFProperties.instance().getPropertySet()
                .getBoolean("oxf.debug.enable-java-location-data", false).booleanValue())
            return null;

        // Compute stack trace and extract useful information
        final Exception e = new Exception();
        final StackTraceElement[] stkTrc = e.getStackTrace();
        final int dpthToUse = depth + 1;
        final String sysID = stkTrc[dpthToUse].getFileName();
        final int line = stkTrc[dpthToUse].getLineNumber();
        return new LocationData(sysID, line, -1);
    }

    /**
     * Visit a subtree of a dom4j document.
     *
     * @param container         element containing the elements to visit
     * @param visitorListener   listener to call back
     */
    public static void visitSubtree(Element container, VisitorListener visitorListener) {
        for (Iterator i = container.content().iterator(); i.hasNext();) {
            final Node childNode = (Node) i.next();

            if (childNode instanceof Element) {
                final Element childElement = (Element) childNode;
                visitorListener.startElement(childElement);
                visitSubtree(childElement, visitorListener);
                visitorListener.endElement(childElement);
            } else if (childNode instanceof Text) {
                visitorListener.text((Text) childNode);
            } else {
                // Ignore as we don't need other node types for now
            }
        }
    }

    public static interface VisitorListener {
        public void startElement(Element element);
        public void endElement(Element element);
        public void text(Text text);
    }
}
