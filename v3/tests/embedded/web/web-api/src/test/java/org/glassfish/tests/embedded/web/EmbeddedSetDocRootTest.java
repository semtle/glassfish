/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
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
 *
 */

package org.glassfish.tests.embedded.web;

import org.junit.Test;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import java.io.*;
import java.util.logging.Level;
import java.net.*;
import org.apache.catalina.Deployer;
import org.apache.catalina.logger.SystemOutLogger;
import org.glassfish.api.embedded.*;
import org.glassfish.api.embedded.web.*;
import javax.servlet.Servlet;
import javax.servlet.ServletRegistration;
import org.glassfish.tests.webapi.HelloWeb;

/**
 * Tests WebBuilding#setListings for directory listing feature
 * 
 * @author Amy Roh
 */
public class EmbeddedSetDocRootTest {

    static Server server;
    static EmbeddedWebContainer embedded;
    static File root;

    @BeforeClass
    public static void setupServer() throws Exception {
        try {
            EmbeddedFileSystem.Builder fsBuilder = new EmbeddedFileSystem.Builder();
            String p = System.getProperty("buildDir");
            root = new File(p).getParentFile();
            root =new File(root, "glassfish");
            EmbeddedFileSystem fs = fsBuilder.instanceRoot(root).build();

            Server.Builder builder = new Server.Builder("dirserve");
            builder.embeddedFileSystem(fs);
            server = builder.build();

            WebBuilder webBuilder = server.createConfig(WebBuilder.class);
            webBuilder.setDocRootDir(root);
            webBuilder.setListings(true);
            System.out.println("builder is " + webBuilder);
            server.addContainer(webBuilder);
            embedded = (EmbeddedWebContainer) webBuilder.create(server);
            embedded.setLogLevel(Level.INFO);
            embedded.setConfiguration(webBuilder);

            System.out.println("Added Web with base directory "+root.getAbsolutePath());

            Port http = server.createPort(8080);
            embedded.bind(http, "http");
            embedded.start();
        } catch(Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    
    @Test
    public void testEmbeddedWebAPIConfig() throws Exception {   
            
        System.out.println("================ EmbeddedSetDocRoot Test");
                
        /*VirtualServer vs = (VirtualServer)
                embedded.createVirtualServer("test-server", root);
        embedded.addVirtualServer(vs);
        Context context = (Context) embedded.createContext(root, null);
        vs.addContext(context, "/test");
        
        Servlet hello = new HelloWeb();
        ServletRegistration reg = context.addServlet("test-servlet", hello);
        reg.addMapping(new String[] {"/hello"});*/

        URL servlet = new URL("http://localhost:8080");
        URLConnection yc = servlet.openConnection();
        BufferedReader in = new BufferedReader(
                                new InputStreamReader(
                                yc.getInputStream()));

        StringBuilder sb = new StringBuilder();
        String inputLine;
        while ((inputLine = in.readLine()) != null){
            sb.append(inputLine);
        }
        in.close();
        
        Thread.sleep(100);
        
     }

    @AfterClass
    public static void shutdownServer() throws Exception {
        System.out.println("shutdown initiated");
        if (server!=null) {
            try {
                server.stop();
            } catch (LifecycleException e) {
                e.printStackTrace();
                throw e;
            }
        }
    }
    
}
