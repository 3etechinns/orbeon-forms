/**
 * Copyright (C) 2012 Orbeon, Inc.
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
package org.orbeon.oxf.xml

import org.xml.sax.Attributes
import org.xml.sax.helpers.AttributesImpl

import scala.collection.immutable.Seq

trait XMLReceiverSupport {

    support ⇒

    def withDocument[T](body: ⇒ T)(implicit receiver: XMLReceiver) = {
        receiver.startDocument()
        val result = body
        receiver.endDocument()
        result
    }

    def withElement[T](localName: String, atts: Seq[(String, String)] = Nil)(body: ⇒ T)(implicit receiver: XMLReceiver): T =
        withElement(prefix = "", uri = "", localName, atts)(body)

    def withElement[T](prefix: String, uri: String, localName: String, atts: Attributes)(body: ⇒ T)(implicit receiver: XMLReceiver): T = {
        val qName = XMLUtils.buildQName(prefix, localName)
        receiver.startElement(uri, localName, qName, atts)
        val result = body
        receiver.endElement(uri, localName, qName)
        result
    }

    def element(localName: String, atts: Seq[(String, String)] = Nil, text: String = "")(implicit receiver: XMLReceiver) =
        withElement(prefix = "", uri = "", localName, atts) {
            support.text(text)
        }

    def text(text: String)(implicit receiver: XMLReceiver) =
        if (text.nonEmpty) {
            val chars = text.toCharArray
            receiver.characters(chars, 0, chars.length)
        }

    def element(prefix: String, uri: String, localName: String, atts: Attributes)(implicit receiver: XMLReceiver) =
        withElement(prefix, uri, localName, atts) {}

    def addAttributes(attributesImpl: AttributesImpl, atts: Seq[(String, String)]): Unit =
        atts foreach {
            case (name, value) ⇒
                require(name ne null)
                if (value ne null)
                    attributesImpl.addAttribute("", name, name, "CDATA", value)
        }

    // NOTE: Encode attributes as space-separated XML attribute-like pairs
    def processingInstruction(name: String, atts: Seq[(String, String)] = Nil)(implicit receiver: XMLReceiver): Unit =
        receiver.processingInstruction(
            name,
            atts map { case (name, value) ⇒ s"""$name="${XMLUtils.escapeXMLForAttribute(value)}"""" } mkString " "
        )

    implicit def pairsToAttributes(atts: Seq[(String, String)]): Attributes = {
        val saxAtts = new AttributesImpl
        addAttributes(saxAtts, atts)
        saxAtts
    }
}
