/**
 *  Copyright (C) 2005 Orbeon, Inc.
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

import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.externalcontext.ForwardExternalContextRequestWrapper;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorUtils;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.resources.handler.HTTPURLConnection;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.event.events.XFormsSubmitDoneEvent;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilities for XForms submission processing.
 */
public class XFormsSubmissionUtils {

    /**
     * Perform an optimized local connection using the Servlet API instead of using a URLConnection.
     */
    public static XFormsModelSubmission.ConnectionResult doOptimized(PipelineContext pipelineContext, ExternalContext externalContext,
                                                                     XFormsModelSubmission xformsModelSubmission, String method, final String action, String mediatype, boolean doReplace,
                                                                     byte[] messageBody, String queryString) {
        try {
            if (isPost(method) || isPut(method) || isGet(method) || isDelete(method)) {

                // Case of empty body
                if (messageBody == null)
                    messageBody = new byte[0];

                // Create requestAdapter depending on method
                final ForwardExternalContextRequestWrapper requestAdapter;
                final String effectiveResourceURI;
                {
                    if (isPost(method) || isPut(method)) {
                        // Simulate a POST or PUT
                        effectiveResourceURI = action;

                        if (XFormsServer.logger.isDebugEnabled())
                            XFormsServer.logger.debug("XForms - setting request body: " + new String(messageBody, "UTF-8"));

                        requestAdapter = new ForwardExternalContextRequestWrapper(externalContext.getRequest(),
                                effectiveResourceURI, method.toUpperCase(), (mediatype != null) ? mediatype : "application/xml", messageBody);
                    } else {
                        // Simulate a GET
                        {
                            final StringBuffer updatedActionStringBuffer = new StringBuffer(action);
                            if (queryString != null) {
                                if (action.indexOf('?') == -1)
                                    updatedActionStringBuffer.append('?');
                                else
                                    updatedActionStringBuffer.append('&');
                                updatedActionStringBuffer.append(queryString);
                            }
                            effectiveResourceURI = updatedActionStringBuffer.toString();
                        }
                        requestAdapter = new ForwardExternalContextRequestWrapper(externalContext.getRequest(),
                                effectiveResourceURI, method.toUpperCase());
                    }
                }

                if (XFormsServer.logger.isDebugEnabled())
                    XFormsServer.logger.debug("XForms - dispatching to effective resource URI: " + effectiveResourceURI);

                final ExternalContext.RequestDispatcher requestDispatcher = externalContext.getRequestDispatcher(action);
                final XFormsModelSubmission.ConnectionResult connectionResult = new XFormsModelSubmission.ConnectionResult(effectiveResourceURI) {
                    public void close() {
                        if (getResultInputStream() != null) {
                            try {
                                getResultInputStream().close();
                            } catch (IOException e) {
                                throw new OXFException("Exception while closing input stream for action: " + action);
                            }
                        }
                    }
                };
                if (doReplace) {
                    // "the event xforms-submit-done is dispatched"
                    if (xformsModelSubmission != null)
                        xformsModelSubmission.getContainingDocument().dispatchEvent(pipelineContext, new XFormsSubmitDoneEvent(xformsModelSubmission));
                    // Just forward the reply
                    requestDispatcher.forward(requestAdapter, externalContext.getResponse());
                    connectionResult.dontHandleResponse = true;
                } else {
                    // We must intercept the reply
                    final ResponseAdapter responseAdapter = new ResponseAdapter(externalContext.getNativeResponse());
                    requestDispatcher.include(requestAdapter, responseAdapter);

                    // Get response information that needs to be forwarded

                    // NOTE: Here, the resultCode is not propagated from the included resource
                    // when including Servlets. Similarly, it is not possible to obtain the
                    // included resource's content type or headers. Because of this we should not
                    // use an optimized submission from within a servlet.
                    connectionResult.resultCode = responseAdapter.getResponseCode();
                    connectionResult.resultMediaType = ProcessorUtils.XML_CONTENT_TYPE;
                    connectionResult.setResultInputStream(responseAdapter.getInputStream());
                    connectionResult.resultHeaders = new HashMap();
                    connectionResult.lastModified = 0;
                }

                return connectionResult;
            } else if (method.equals("multipart-post")) {
                // TODO
                throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
            } else if (method.equals("form-data-post")) {
                // TODO
                throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
            } else {
                throw new OXFException("xforms:submission: invalid submission method requested: " + method);
            }
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    /**
     * Perform a connection using an URLConnection.
     *
     * @param action absolute URL or absolute path (which must include the context path)
     */
    public static XFormsModelSubmission.ConnectionResult doRegular(ExternalContext externalContext,
                                                                   String method, final String action, String username, String password, String mediatype,
                                                                   byte[] messageBody, String queryString) {

        // Compute absolute submission URL
        final URL submissionURL = createAbsoluteURL(action, queryString, externalContext);
        return doRegular(externalContext, method, submissionURL, username, password, mediatype, messageBody);
    }

    public static XFormsModelSubmission.ConnectionResult doRegular(ExternalContext externalContext,
                                                                   String method, final URL submissionURL, String username, String password, String mediatype,
                                                                   byte[] messageBody) {

        // Perform submission
        final String scheme = submissionURL.getProtocol();
        if (scheme.equals("http") || scheme.equals("https") || (isGet(method) && (scheme.equals("file") || scheme.equals("oxf")))) {
            // http MUST be supported
            // https SHOULD be supported
            // file SHOULD be supported
            try {
                if (XFormsServer.logger.isDebugEnabled())
                    XFormsServer.logger.debug("XForms - opening URL connection for: " + submissionURL.toExternalForm());

                final URLConnection urlConnection = submissionURL.openConnection();
                final HTTPURLConnection httpURLConnection = (urlConnection instanceof HTTPURLConnection) ? (HTTPURLConnection) urlConnection : null;
                if (isPost(method) || isPut(method) || isGet(method) || isDelete(method)) {
                    // Whether a message body must be sent
                    final boolean hasRequestBody = isPost(method) || isPut(method);
                    // Case of empty body
                    if (messageBody == null)
                        messageBody = new byte[0];

                    urlConnection.setDoInput(true);
                    urlConnection.setDoOutput(hasRequestBody);

                    if (httpURLConnection != null) {
                        httpURLConnection.setRequestMethod(getHttpMethod(method));
                        if (username != null) {
                            httpURLConnection.setUsername(username);
                            if (password != null)
                               httpURLConnection.setPassword(password);
                        }
                    }
                    if (hasRequestBody) {
                        urlConnection.setRequestProperty("Content-type", (mediatype != null) ? mediatype : "application/xml");
                    }

                    // Forward cookies for session handling
                    // TODO: The Servlet spec mandates JSESSIONID as cookie name; we should only forward this cookie
                    if (username == null) {
                        final String[] cookies = (String[]) externalContext.getRequest().getHeaderValuesMap().get("cookie");
                        if (cookies != null) {
                            for (int i = 0; i < cookies.length; i++) {
                                final String cookie = cookies[i];
                                XFormsServer.logger.debug("XForms - forwarding cookie: " + cookie);
                                urlConnection.setRequestProperty("Cookie", cookie);
                            }
                        }
                    }

                    // Forward authorization header
                    // TODO: This should probably not be done automatically
                    if (username == null) {
                        final String authorizationHeader = (String) externalContext.getRequest().getHeaderMap().get("authorization");
                        if (authorizationHeader != null) {
                            XFormsServer.logger.debug("XForms - forwarding authorization header: " + authorizationHeader);
                            urlConnection.setRequestProperty("Authorization", authorizationHeader);
                        }
                    }

                    // Write request body if needed
                    if (hasRequestBody) {
                        if (XFormsServer.logger.isDebugEnabled())
                            XFormsServer.logger.debug("XForms - setting request body: " + new String(messageBody, "UTF-8"));
                        httpURLConnection.setRequestBody(messageBody);
                    }

                    urlConnection.connect();

                    // Create result
                    final XFormsModelSubmission.ConnectionResult connectionResult = new XFormsModelSubmission.ConnectionResult(submissionURL.toExternalForm()) {
                        public void close() {
                            if (getResultInputStream() != null) {
                                try {
                                    getResultInputStream().close();
                                } catch (IOException e) {
                                    throw new OXFException("Exception while closing input stream for action: " + submissionURL);
                                }
                            }

                            if (httpURLConnection != null)
                                httpURLConnection.disconnect();
                        }
                    };

                    // Get response information that needs to be forwarded
                    connectionResult.resultCode = (httpURLConnection != null) ? httpURLConnection.getResponseCode() : 200;
                    final String contentType = urlConnection.getContentType();
                    connectionResult.resultMediaType = (contentType != null) ? NetUtils.getContentTypeMediaType(contentType) : "application/xml";
                    connectionResult.resultHeaders = urlConnection.getHeaderFields();
                    connectionResult.lastModified = urlConnection.getLastModified();
                    connectionResult.setResultInputStream(urlConnection.getInputStream());

                    return connectionResult;

                } else if (method.equals("multipart-post")) {
                    // TODO
                    throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
                } else if (method.equals("form-data-post")) {
                    // TODO
                    throw new OXFException("xforms:submission: submission method not yet implemented: " + method);
                } else {
                    throw new OXFException("xforms:submission: invalid submission method requested: " + method);
                }
            } catch (IOException e) {
                throw new ValidationException(e, new LocationData(submissionURL.toExternalForm(), -1, -1));
            }
        } else if (!isGet(method) && (scheme.equals("file") || scheme.equals("oxf"))) {
            // TODO: implement writing to file: and oxf:
            // SHOULD be supported (should probably support oxf: as well)
            throw new OXFException("xforms:submission: submission URL scheme not yet implemented: " + scheme);
        } else if (scheme.equals("mailto")) {
            // TODO: implement sending mail
            // MAY be supported
            throw new OXFException("xforms:submission: submission URL scheme not yet implemented: " + scheme);
        } else {
            throw new OXFException("xforms:submission: submission URL scheme not supported: " + scheme);
        }
    }

    /**
     * Create an absolute URL from an action string and a search string.
     *
     * @param action            absolute URL or absolute path
     * @param queryString       optional query string to append to the action URL
     * @param externalContext   current ExternalContext
     * @return                  an absolute URL
     */
    public static URL createAbsoluteURL(String action, String queryString, ExternalContext externalContext) {
        URL resultURL;
        try {
            final String actionString;
            {
                final StringBuffer updatedActionStringBuffer = new StringBuffer(action);
                if (queryString != null && queryString.length() > 0) {
                    if (action.indexOf('?') == -1)
                        updatedActionStringBuffer.append('?');
                    else
                        updatedActionStringBuffer.append('&');
                    updatedActionStringBuffer.append(queryString);
                }
                actionString = updatedActionStringBuffer.toString();
            }

            if (actionString.startsWith("/")) {
                // Case of path absolute
                final String requestURL = externalContext.getRequest().getRequestURL();
                resultURL = URLFactory.createURL(requestURL, actionString);
            } else if (NetUtils.urlHasProtocol(actionString)) {
                // Case of absolute URL
                resultURL = URLFactory.createURL(actionString);
            } else {
                throw new OXFException("Invalid URL: " + actionString);
            }
        } catch (MalformedURLException e) {
            throw new OXFException("Invalid URL: " + action, e);
        }
        return resultURL;
    }

    public static boolean isGet(String method) {
        return method.equals("get") || method.equals(XMLUtils.buildExplodedQName(XFormsConstants.XXFORMS_NAMESPACE_URI, "get"));
    }

    public static boolean isPost(String method) {
        return method.equals("post") || method.equals("urlencoded-post") || method.equals(XMLUtils.buildExplodedQName(XFormsConstants.XXFORMS_NAMESPACE_URI, "post"));
    }

    public static boolean isPut(String method) {
        return method.equals("put") || method.equals(XMLUtils.buildExplodedQName(XFormsConstants.XXFORMS_NAMESPACE_URI, "put"));
    }

    public static boolean isDelete(String method) {
        return method.equals("delete") || method.equals(XMLUtils.buildExplodedQName(XFormsConstants.XXFORMS_NAMESPACE_URI, "delete"));
    }

    public static String getHttpMethod(String method) {
        return isGet(method) ? "GET" : isPost(method) ? "POST" : isPut(method) ? "PUT" : isDelete(method) ? "DELETE" : null;
    }
}

class ResponseAdapter implements ExternalContext.Response {

    private Object nativeResponse;

    private int status = 200;
    private String contentType;

    private StringWriter stringWriter;
    private PrintWriter printWriter;
    private LocalByteArrayOutputStream byteStream;

    private InputStream inputStream;

    public ResponseAdapter(Object nativeResponse) {
        this.nativeResponse = nativeResponse;
    }

    public int getResponseCode() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public Map getHeaders() {
        return null;
    }

    public InputStream getInputStream() {
        if (inputStream == null) {
            if (stringWriter != null) {
                final byte[] bytes;
                try {
                    bytes = stringWriter.getBuffer().toString().getBytes("utf-8");
                } catch (UnsupportedEncodingException e) {
                    throw new OXFException(e); // should not happen
                }
                inputStream = new ByteArrayInputStream(bytes, 0, bytes.length);
//                throw new OXFException("ResponseAdapter.getInputStream() does not yet support content written with getWriter().");
            } else if (byteStream != null) {
                inputStream = new ByteArrayInputStream(byteStream.getByteArray(), 0, byteStream.size());
            }
        }

        return inputStream;
    }

    public void addHeader(String name, String value) {
    }

    public boolean checkIfModifiedSince(long lastModified, boolean allowOverride) {
        return true;
    }

    public String getCharacterEncoding() {
        return null;
    }

    public String getNamespacePrefix() {
        return null;
    }

    public OutputStream getOutputStream() throws IOException {
        if (byteStream == null)
            byteStream = new LocalByteArrayOutputStream();
        return byteStream;
    }

    public PrintWriter getWriter() throws IOException {
        if (stringWriter == null) {
            stringWriter = new StringWriter();
            printWriter = new PrintWriter(stringWriter);
        }
        return printWriter;
    }

    public boolean isCommitted() {
        return false;
    }

    public void reset() {
    }

    public String rewriteActionURL(String urlString) {
        return null;
    }

    public String rewriteRenderURL(String urlString) {
        return null;
    }

    public String rewriteActionURL(String urlString, String portletMode, String windowState) {
        return null;
    }

    public String rewriteRenderURL(String urlString, String portletMode, String windowState) {
        return null;
    }

    public String rewriteResourceURL(String urlString, boolean absolute) {
        return null;
    }

    public void sendError(int sc) throws IOException {
        this.status = sc;
    }

    public void sendRedirect(String pathInfo, Map parameters, boolean isServerSide, boolean isExitPortal) throws IOException {
    }

    public void setCaching(long lastModified, boolean revalidate, boolean allowOverride) {
    }

    public void setContentLength(int len) {
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setHeader(String name, String value) {
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setTitle(String title) {
    }

    private static class LocalByteArrayOutputStream extends ByteArrayOutputStream {
        public byte[] getByteArray() {
            return buf;
        }
    }

    public Object getNativeResponse() {
        return nativeResponse;
    }
}
