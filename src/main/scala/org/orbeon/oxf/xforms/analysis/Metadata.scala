/**
 * Copyright (C) 2011 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.analysis

import java.util.{Map ⇒ JMap}

import org.dom4j.io.DocumentSource
import org.orbeon.oxf.xforms.XFormsStaticStateImpl.StaticStateDocument
import org.orbeon.oxf.xforms.XFormsUtils
import org.orbeon.oxf.xforms.state.AnnotatedTemplate
import org.orbeon.oxf.xforms.xbl._
import org.orbeon.oxf.xml.{NamespaceMapping, SAXStore, TransformerUtils}

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}

/**
 * Container for element metadata gathered during document annotation/extraction:
 *
 * - id generation
 * - namespace mappings
 * - automatic XBL mappings
 * - full update marks
 *
 * There is one distinct Metadata instance per part.
 *
 * Split into traits for modularity.
 */
class Metadata(val idGenerator: IdGenerator) extends NamespaceMappings with BindingMetadata with Marks {
    def this() = this(new IdGenerator)
}

object Metadata {
    // Restore a Metadata object from the given StaticStateDocument
    def apply(staticStateDocument: StaticStateDocument, template: Option[AnnotatedTemplate]): Metadata = {

        // Restore generator with last id
        val metadata = new Metadata(new IdGenerator(staticStateDocument.lastId))

        // Restore namespace mappings and ids
        TransformerUtils.sourceToSAX(new DocumentSource(staticStateDocument.xmlDocument), new XFormsAnnotator(metadata))

        // Restore marks if there is a template
        template foreach { template ⇒
            for (mark ← template.saxStore.getMarks.asScala)
                metadata.putMark(mark)
        }

        metadata
    }
}

// Handling of template marks
trait Marks {
    private val marks = new mutable.HashMap[String, SAXStore#Mark]

    def putMark(mark: SAXStore#Mark) = marks += mark.id → mark
    def getMark(prefixedId: String) = marks.get(prefixedId)

    private def topLevelMarks = marks collect { case (prefixedId, mark) if XFormsUtils.isTopLevelId(prefixedId) ⇒ mark }
    def hasTopLevelMarks = topLevelMarks.nonEmpty
}

// Handling of namespaces
trait NamespaceMappings {

    private val namespaceMappings = new mutable.HashMap[String, NamespaceMapping]
    private val hashes = new mutable.LinkedHashMap[String, NamespaceMapping]

    def addNamespaceMapping(prefixedId: String, mapping: JMap[String, String]): Unit = {
        // Sort mappings by prefix
        val sorted = immutable.TreeMap(mapping.asScala.toSeq: _*)
        // Hash key/values
        val hexHash = NamespaceMapping.hashMapping(sorted.asJava)

        // Retrieve or create mapping object
        val namespaceMapping = hashes.getOrElseUpdate(hexHash, {
            val newNamespaceMapping = new NamespaceMapping(hexHash, sorted.asJava)
            hashes += (hexHash → newNamespaceMapping)
            newNamespaceMapping
        })

        // Remember that id has this mapping
        namespaceMappings += prefixedId → namespaceMapping
    }

    def removeNamespaceMapping(prefixedId: String): Unit =
        namespaceMappings -= prefixedId

    def getNamespaceMapping(prefixedId: String) = namespaceMappings.get(prefixedId).orNull

    def debugPrintNamespaces(): Unit = {
        println("Number of different namespace mappings: " + hashes.size)
        for ((key, value) ← hashes) {
            println("   hash: " + key)
            for ((prefix, uri) ← value.mapping.asScala)
                println("     " + prefix + " → " + uri)
        }
    }
}
