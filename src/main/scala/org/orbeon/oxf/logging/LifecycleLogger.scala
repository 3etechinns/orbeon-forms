/**
 * Copyright (C) 2014 Orbeon, Inc.
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
package org.orbeon.oxf.logging

import java.util
import java.util.concurrent.atomic.AtomicInteger
import javax.servlet.http.{HttpSession, HttpServletRequest}

import org.orbeon.oxf.externalcontext.RequestAdapter
import org.orbeon.oxf.pipeline.InitUtils
import org.orbeon.oxf.pipeline.api.ExternalContext.Session.SessionListener
import org.orbeon.oxf.pipeline.api.ExternalContext.{Session, Request}
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.oxf.util.{JSON, NetUtils}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

private class MinimalSession(session: HttpSession) extends Session {

  override def getId = session.getId

  override def getCreationTime: Long = throw new UnsupportedOperationException
  override def isNew: Boolean = throw new UnsupportedOperationException
  override def getLastAccessedTime: Long = throw new UnsupportedOperationException
  override def removeListener(sessionListener: SessionListener): Unit = throw new UnsupportedOperationException
  override def setMaxInactiveInterval(interval: Int): Unit = throw new UnsupportedOperationException
  override def getAttributesMap: util.Map[String, AnyRef] = throw new UnsupportedOperationException
  override def getAttributesMap(scope: Int): util.Map[String, AnyRef] = throw new UnsupportedOperationException
  override def addListener(sessionListener: SessionListener): Unit = throw new UnsupportedOperationException
  override def invalidate(): Unit = throw new UnsupportedOperationException
  override def getMaxInactiveInterval: Int = throw new UnsupportedOperationException
}

private class MinimalRequest(req: HttpServletRequest) extends RequestAdapter {

  override lazy val getAttributesMap = new InitUtils.RequestMap(req)
  override def getRequestPath        = NetUtils.getRequestPathInfo(req)
  override def getMethod             = req.getMethod

  private lazy val sessionWrapper = new MinimalSession(req.getSession(true))

  override def getSession(create: Boolean): Session = {
    val underlyingSession = req.getSession(create)
    if (underlyingSession ne null)
      sessionWrapper
    else
      null
  }
}

object MinimalRequest {
  def apply(req: HttpServletRequest): Request = new MinimalRequest(req)
}

object LifecycleLogger {

  private val LoggerName = "org.orbeon.lifecycle"
  private val Logger     = LoggerFactory.getLogger(LoggerName)

  private val globalRequestCounter = new AtomicInteger(0)

  private def arrayToParams(params: Array[String]) =
    params.grouped(2) map { case Array(x, y) ⇒ (x, y) } toList

  private def getRequestId(req: Request) =
    req.getAttributesMap.get(LoggerName + ".request-id").asInstanceOf[String]

  private def requestIdSetIfNeeded(req: Request): (String, Boolean) =
    Option(getRequestId(req)) map (_ → true) getOrElse {
      val requestId = globalRequestCounter.incrementAndGet().toString
      req.getAttributesMap.put(LoggerName + ".request-id", requestId)
      requestId → false
    }
  
  private def findSessionId(req: Request): Option[String] =
    Option(req.getSession(false)) map (_.getId)

  private def event(req: Request, source: String, message: String, params: Seq[(String, String)]): Unit =
    try {
      val (requestId, existingId) = requestIdSetIfNeeded(req)
      event(requestId, findSessionId(req), source, message, (if (existingId) Nil else basicRequestDetails(req)) ++ params)
    } catch {
      case NonFatal(t) ⇒ logInternalError(t)
    }

  private def event(requestId: String, sessionIdOpt: Option[String], source: String, message: String, params: Seq[(String, String)]): Unit = {
    val all = ("request" → requestId) +: ("session" → sessionIdOpt.orNull) +: ("source" → source) +: ("message" → message) +: params
    val formatted = all collect { case (name, value) if value ne null ⇒ s""""$name": "${JSON.quoteValue(value)}"""" }
    Logger.info(formatted.mkString("""event: {""", ", ", "}"))
  }

  private def logInternalError(t: Throwable) =
    Logger.error(s"throwable caught during logging: ${t.getMessage}")

  def basicRequestDetailsAssumingRequestJava(params: Array[String]) =
    basicRequestDetails(NetUtils.getExternalContext.getRequest) ::: arrayToParams(params)

  def basicRequestDetails(req: Request) =
    List(
      "path"   → req.getRequestPath,
      "method" → req.getMethod
    )

  def withEventAssumingRequest[T](source: String, message: String, params: Seq[(String, String)])(body: ⇒ T): T =
    withEvent(NetUtils.getExternalContext.getRequest, source, message, params)(body)

  def withEvent[T](req: Request, source: String, message: String, params: Seq[(String, String)])(body: ⇒ T): T = {
    val timestamp = System.currentTimeMillis
    var currentThrowable: Throwable = null
    event(req, source, s"start: $message", params)
    try {
      body
    } catch {
      case t: Throwable ⇒
        currentThrowable = t
        throw t
    } finally {
      val endParams =
        ("time", f"${System.currentTimeMillis - timestamp}%,d ms") ::
        ((currentThrowable ne null) list ("threw", currentThrowable.getMessage))

      event(req, source, s"end: $message", endParams)
    }
  }

  def eventAssumingRequestJava(source: String, message: String, params: Array[String]): Unit =
    try eventAssumingRequest(source, message, arrayToParams(params))
    catch { case NonFatal(t) ⇒ logInternalError(t) }

  def eventAssumingRequest(source: String, message: String, params: Seq[(String, String)]): Unit =
    try event(NetUtils.getExternalContext.getRequest, source, message, params)
    catch { case NonFatal(t) ⇒ logInternalError(t) }

  def formatDelay(timestamp: Long) =
    (System.currentTimeMillis - timestamp).toString
}
