/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.index.status

import org.orbeon.oxf.fr.persistence.relational.RelationalUtils
import org.orbeon.oxf.util.NetUtils

import scala.collection.JavaConverters._

// Functions dealing with the session

object StatusStore {

  private var currentStatus: Status = Stopped

  private def session =
    NetUtils.getExternalContext.getSession(true).getAttributesMap.asScala

  def getStatus: Status = currentStatus

  def setStatus(status: Status): Unit = {

    // Log status
    if (RelationalUtils.Logger.isDebugEnabled) {
      def liftLog(log: (String, String) ⇒ Unit): (String ⇒ Unit) = log("Reindex status", _: String)
      def logInfo  = liftLog(RelationalUtils.Logger.logInfo)
      def logDebug = liftLog(RelationalUtils.Logger.logDebug)
      status match {
        case Stopped                         ⇒ logInfo("Stopped" )
        case Starting(providers)             ⇒ logInfo("Starting, will index " + providers.mkString("[", ", ", "]"))
        case Stopping                        ⇒ logInfo("Stopping")
        case Indexing(provider, providerCount, maybeDocumentCount) ⇒
          def providerInfo = s"$provider ${providerCount.current}/${providerCount.total}"
          maybeDocumentCount match {
            case None                        ⇒ logInfo (s"Indexing $providerInfo")
            case Some(dc) if dc.current == 0 ⇒ logInfo (s"Indexing $providerInfo, ${dc.total} documents")
            case Some(dc) if dc.current != 0 ⇒ logDebug(s"Indexing $providerInfo, document ${dc.current}/${dc.total}")
          }
      }
    }

    currentStatus = status
  }
}
