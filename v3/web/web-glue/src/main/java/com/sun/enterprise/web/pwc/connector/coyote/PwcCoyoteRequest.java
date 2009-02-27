/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.web.pwc.connector.coyote;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.CoyoteInputStream;
import org.apache.catalina.connector.CoyoteReader;
import org.apache.catalina.connector.InputBuffer;
import com.sun.grizzly.util.http.Parameters;
// START GlassFish 898
import com.sun.grizzly.util.http.ServerCookie;
// END GlassFish 898
import com.sun.enterprise.web.pwc.PwcWebModule;
import com.sun.enterprise.web.session.SessionCookieConfig;
import com.sun.enterprise.web.logging.pwc.LogDomains;

/**
 * Customized version of the Tomcat 5 CoyoteRequest
 * This is required for supporting Web Programmatic Login and setting the
 * request encoding (charset).
 *
 * @author Jeanfrancois Arcand
 * @author Jan Luehe
 */
public class PwcCoyoteRequest extends Request {

    private static Logger logger = LogDomains.getLogger(PwcCoyoteRequest.class, LogDomains.PWC_LOGGER);

    // Have we already determined request encoding from sun-web.xml?
    private boolean sunWebXmlChecked = false;

    // START SJSAS 6346738
    private byte[] formData = null;
    private int formDataLen = 0;
    // END SJSAS 6346738

    public void setContext(Context ctx) {
        if (ctx == null) {
            // Invalid request. Response will be handled by
            // the StandardEngineValve
            return;
        }
        
        super.setContext(ctx);
        Response response = (Response) getResponse();
        // Assert response!=null
        if (response != null) {
            String[] cacheControls = ((PwcWebModule) ctx).getCacheControls();
            for (int i=0; cacheControls!=null && i<cacheControls.length; i++) {
                response.addHeader("Cache-Control", cacheControls[i]);
            }
        }

        sunWebXmlChecked = false;
    }

    public BufferedReader getReader() throws IOException {
        if (super.getCharacterEncoding() == null) {
            setRequestEncodingFromSunWebXml();
        }
        return super.getReader();
    }


    /**
     * Return the character encoding for this Request.
     *
     * If there is no request charset specified in the request, determines and
     * sets the request charset using the locale-charset-info,
     * locale-charset-map, and parameter-encoding elements provided in the
     * sun-web.xml.
     */
    public String getCharacterEncoding() {
        String enc = super.getCharacterEncoding();
        if (enc != null) {
            return enc;
        }
    
        boolean encodingFound = setRequestEncodingFromSunWebXml();
        if (encodingFound) {
            return super.getCharacterEncoding();
        } else {
            return null;
        }
    }


    /*
     * Configures the given JSESSIONID cookie with the cookie-properties from
     * sun-web.xml.
     *
     * @param cookie The JSESSIONID cookie to be configured
     */
    public void configureSessionCookie(Cookie cookie) {

        super.configureSessionCookie(cookie);

        // Do not consider SessionCookieConfig from sun-web.xml 
        // if ServletContext's SessionCookieConfig has been initialized
        if ((getContext() != null) && 
                (getContext().isSessionCookieConfigInitialized())) {
            return;
        }
        
        PwcWebModule wm = (PwcWebModule) getContext();
        SessionCookieConfig cookieConfig = wm.getSessionCookieConfigFromSunWebXml();

        if (cookieConfig != null) {

            String name = cookieConfig.getName();
            if (name != null && !name.equals(Globals.SESSION_COOKIE_NAME)) {
                logger.log(Level.WARNING,
                           "pe_coyote.request.illegal_cookie_name",
                           new String[] { name, Globals.SESSION_COOKIE_NAME });
            }
     
            if (cookieConfig.getPath() != null) {
                cookie.setPath(cookieConfig.getPath());
            }

            cookie.setMaxAge(cookieConfig.getMaxAge());

            if (cookieConfig.getDomain() != null) {
                cookie.setDomain(cookieConfig.getDomain());
            }

            if (cookieConfig.getComment() != null) {
                cookie.setVersion(1);
                cookie.setComment(cookieConfig.getComment());
            }

            if (!cookieConfig.getSecure().equalsIgnoreCase(SessionCookieConfig.DYNAMIC_SECURE)) {
                cookie.setSecure(Boolean.parseBoolean(cookieConfig.getSecure()));
            }
        }
    }
    

    // START SJSAS 6346738
    public void recycle() {
        super.recycle();
        formDataLen = 0;
        sunWebXmlChecked = false;
    }
    // END SJSAS 6346738
            

    /**
     * Determines and sets the request charset using the locale-charset-info,
     * locale-charset-map, and parameter-encoding elements provided in the
     * sun-web.xml.
     *
     * @return true if a request encoding has been determined and set,
     * false otherwise
     */
    private boolean setRequestEncodingFromSunWebXml() {

        if (sunWebXmlChecked) {
            return false;
        }

        sunWebXmlChecked = true;

        PwcWebModule wm = (PwcWebModule) getContext();

        String encoding = getFormHintFieldEncoding(wm);
        if (encoding == null) {
            encoding = wm.getDefaultCharset();
            if (encoding == null && wm.hasLocaleToCharsetMapping()) {
                encoding = wm.mapLocalesToCharset(getLocales());
            }
        }

        if (encoding != null) {
            try {
                setCharacterEncoding(encoding);
            } catch (UnsupportedEncodingException uee) {
                logger.log(Level.WARNING, "pe_coyote.request.encoding", uee);
            }
        }

        return (encoding != null);
    }


    /*
     * Returns the value of the query parameter whose name
     * corresponds to the value of the form-hint-field attribute of the
     * <parameter-encoding> element in sun-web.xml.
     *
     * @return The value of the query parameter whose name corresponds to the
     * value of the form-hint-field attribute in sun-web.xml, or null if the
     * request does not have any query string, or the query string does not
     * contain a query parameter with that name
     */
    private String getFormHintFieldEncoding(PwcWebModule wm) {

        String encoding = null;

        String formHintField = wm.getFormHintField();
        if (formHintField == null){
            return null;
        }

        if ("POST".equalsIgnoreCase(getMethod())) {
            // POST
            encoding = getPostDataEncoding(formHintField);
        } else {
            String query = getQueryString();
            if (query != null) {
                encoding = parseFormHintField(query, formHintField);
            }
        }

        return encoding;
    }
        
    
    private String getPostDataEncoding(String formHintField) {

        if (!getMethod().equalsIgnoreCase("POST")) {
            return null;
        }

        String contentType = getContentType();
        if (contentType == null)
            contentType = "";
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0) {
            contentType = contentType.substring(0, semicolon).trim();
        } else {
            contentType = contentType.trim();
        }
        if (!("application/x-www-form-urlencoded".equals(contentType))) {
            return null;
        }

        int len = getContentLength();
        if (len <= 0) {
            return null;
        }
        int maxPostSize = ((Connector) connector).getMaxPostSize();
        if ((maxPostSize > 0) && (len > maxPostSize)) {
            logger.log(Level.WARNING, "peCoyoteRequest.postTooLarge");
            throw new IllegalStateException("Post too large");
        }

        String encoding = null;

        try {
            formData = null;
            if (len < CACHED_POST_LEN) {
                if (postData == null)
                    postData = new byte[CACHED_POST_LEN];
                formData = postData;
            } else {
                formData = new byte[len];
            }
            int actualLen = readPostBody(formData, len);
            if (actualLen == len) {
                // START SJSAS 6346738
                formDataLen = actualLen;
                // END SJSAS 6346738
                String formDataString = new String(formData).substring(0, len);
                encoding = parseFormHintField(formDataString, formHintField);
            }
        } catch (Throwable t) {
            ; // Ignore
        }

        return encoding;
    }


    /*
     * Parses the value of the specified form-hint-field from the given
     * parameter string.
     *
     * @param paramsString Parameter string
     * @param formHintField From-hint-field
     *
     * @return Value of form-hint-field, or null if not found
     */
    private String parseFormHintField(String paramsString,
                                      String formHintField) {

        String encoding = null;

        formHintField += "=";            
        int index = paramsString.indexOf(formHintField);
        if (index != -1) {
            int endIndex = paramsString.indexOf('&', index);
            if (endIndex != -1) {
                encoding = paramsString.substring(
                    index + formHintField.length(), endIndex);
            } else {
                encoding = paramsString.substring(
                    index + formHintField.length());
            }
        }

        return encoding;
    }


    // START SJSAS 6346738
    /**
     * Gets the POST body of this request.
     *
     * @return The POST body of this request
     */
    protected byte[] getPostBody() throws IOException {

        if (formDataLen > 0) {
            // POST body already read
            return formData;
        } else {
            return super.getPostBody();
        } 
    }
    // END SJSAS 6346738


    // START GlassFish 898
    @Override
    protected Cookie makeCookie(ServerCookie scookie) {

        PwcWebModule wm = (PwcWebModule) getContext();
        boolean encodeCookies = false;
        if (wm != null && wm.getEncodeCookies()) {
            encodeCookies = true;
        }

        return makeCookie(scookie, encodeCookies);
    }
    // END GlassFish 898

}
