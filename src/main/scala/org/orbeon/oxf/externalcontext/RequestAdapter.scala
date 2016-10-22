/**
  * Copyright (C) 2009 Orbeon, Inc.
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
package org.orbeon.oxf.externalcontext

import java.io.{InputStream, Reader}
import java.security.Principal
import java.{util ⇒ ju}

import org.orbeon.oxf.fr.Credentials
import org.orbeon.oxf.webapp.ExternalContext

class RequestAdapter extends ExternalContext.Request {
  def getContainerType: String = null
  def getContainerNamespace: String = null
  def getPathInfo: String = null
  def getRequestPath: String = null
  def getContextPath: String = null
  def getServletPath: String = null
  def getClientContextPath(urlString: String): String = null
  def getAttributesMap: ju.Map[String, AnyRef] = null
  def getHeaderValuesMap: ju.Map[String, Array[String]] = null
  def getParameterMap: ju.Map[String, Array[AnyRef]] = null
  def getCharacterEncoding: String = null
  def getContentLength: Int = 0
  def getContentType: String = null
  def getInputStream: InputStream = null
  def getReader: Reader = null
  def getProtocol: String = null
  def getRemoteHost: String = null
  def getRemoteAddr: String = null
  def getScheme: String = null
  def getMethod: String = null
  def getServerName: String = null
  def getServerPort: Int = 0
  def getSession(create: Boolean): ExternalContext.Session = null
  def sessionInvalidate() = ()
  def isRequestedSessionIdValid: Boolean = false
  def getRequestedSessionId: String = null
  def getAuthType: String = null
  def isSecure: Boolean = false
  def credentials: Option[Credentials] = None
  def isUserInRole(role: String): Boolean = false
  def getUserPrincipal: Principal = null
  def getLocale: ju.Locale = null
  def getLocales: ju.Enumeration[_] = null
  def getPathTranslated: String = null
  def getQueryString: String = null
  def getRequestURI: String = null
  def getRequestURL: String = null
  def getPortletMode: String = null
  def getWindowState: String = null
  def getNativeRequest: AnyRef = null
}
