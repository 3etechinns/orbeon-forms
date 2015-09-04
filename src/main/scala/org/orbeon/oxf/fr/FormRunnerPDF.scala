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
package org.orbeon.oxf.fr

import org.orbeon.oxf.fr.FormRunner._
import org.orbeon.oxf.fr.FormRunner.FormRunnerParams
import org.orbeon.saxon.functions.EscapeURI

import collection.JavaConverters._
import java.util.{Map ⇒ JMap}
import org.orbeon.oxf.util.ScalaUtils.nonEmptyOrNone
import org.orbeon.oxf.util.URLFinder
import org.orbeon.oxf.xforms.XFormsUtils._
import org.orbeon.oxf.xforms.function.xxforms.{XXFormsProperty, XXFormsPropertiesStartsWith}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.XML._

trait FormRunnerPDF {

  // Return mappings (formatName → expression) for all PDF formats in the properties
  //@XPathFunction
  def getPDFFormats = {

    def propertiesStartingWith(prefix: String) =
      XXFormsPropertiesStartsWith.propertiesStartsWith(prefix).asScala map (_.getStringValue)

    val formatPairs =
      for {
        formatPropertyName ← propertiesStartingWith("oxf.fr.pdf.format")
        expression ← Option(XXFormsProperty.property(formatPropertyName)) map (_.getStringValue)
        formatName = formatPropertyName split '.' last
      } yield
        formatName → expression

    formatPairs.toMap.asJava
  }

  // Return the PDF formatting expression for the given parameters
  //@XPathFunction
  def getPDFFormatExpression(pdfFormats: JMap[String, String], app: String, form: String, name: String, dataType: String) = {

    val propertyName = List("oxf.fr.pdf.map", app, form, name) ::: Option(dataType).toList mkString "."

    val expressionOpt =
      for {
        format     ← Option(XXFormsProperty.property(propertyName)) map (_.getStringValue)
        expression ← Option(pdfFormats.get(format))
      } yield
        expression

    expressionOpt.orNull
  }

  // Build a PDF control id from the given HTML control
  //@XPathFunction
  def buildPDFFieldNameFromHTML(control: NodeInfo) = {

    def isContainer(e: NodeInfo) = {
      val classes = e.attClasses
      classes("xbl-fr-section") || (classes("xbl-fr-grid") && (e \\ "table" exists (_.attClasses("fr-repeat"))))
    }

    def findControlName(e: NodeInfo) =
      nonEmptyOrNone(getStaticIdFromId(e.id)) flatMap FormRunner.controlNameFromIdOpt

    def ancestorContainers(e: NodeInfo) =
      control ancestor * filter isContainer reverse

    def suffixAsList(id: String) =
      nonEmptyOrNone(getEffectiveIdSuffix(id)).toList

    // This only makes sense if we are passed a control with a name
    findControlName(control) map { controlName ⇒
      ((ancestorContainers(control) flatMap findControlName) :+ controlName) ++ suffixAsList(control.id) mkString "$"
     } orNull
  }

  import URLFinder._

  // Add http/https/mailto hyperlinks to a plain string
  //@XPathFunction
  def hyperlinkURLs(s: String, hyperlinks: Boolean) =
    replaceURLs(s, if (hyperlinks) replaceWithHyperlink else replaceWithPlaceholder)

  // Custom filename (for PDF and TIFF output) for the detail page if specified and if evaluates to a non-empty name
  //@XPathFunction
  def filenameOrNull(format: String): String = (
    formRunnerProperty(s"oxf.fr.detail.$format.filename")(FormRunnerParams())
    flatMap nonEmptyOrNone
    flatMap (expr ⇒ nonEmptyOrNone(process.SimpleProcess.evaluateString(expr)))
    map     (EscapeURI.escape(_, "-_.~").toString)
    orNull
  )
}
