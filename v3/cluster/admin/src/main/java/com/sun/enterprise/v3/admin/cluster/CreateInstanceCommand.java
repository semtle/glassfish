/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
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

package com.sun.enterprise.v3.admin.cluster;

import com.sun.enterprise.util.SystemPropertyConstants;
import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.config.serverbeans.Nodes;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.Servers;
import com.sun.enterprise.config.serverbeans.ServerRef;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.ExceptionUtil;
import java.io.IOException;
import org.glassfish.api.ActionReport;
import com.sun.enterprise.util.io.InstanceDirs;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;
import org.glassfish.api.admin.CommandRunner.CommandInvocation;
import org.glassfish.internal.api.ServerContext;
import org.jvnet.hk2.annotations.*;
import org.jvnet.hk2.component.*;
import java.util.logging.Logger;

/**
 * Remote AdminCommand to create an instance.  This command is run only on DAS.
 *  1. Register the instance on DAS
 *  2. Create the file system on the instance node via ssh, node agent, or other
 *  3. Bootstrap a minimal set of config files on the instance for secure admin.
 *
 * @author Jennifer Chou
 */
@Service(name = "create-instance")
@I18n("create.instance")
@Scoped(PerLookup.class)
@ExecuteOn({RuntimeType.DAS})
public class CreateInstanceCommand implements AdminCommand {

    private static final String NL = System.getProperty("line.separator");

    @Inject
    private CommandRunner cr;
    
    @Inject
    Habitat habitat;

    @Inject
    Node[] nodeList;

    @Inject
    private Nodes nodes;

    @Inject
    private Servers servers;

    @Inject
    private ServerEnvironment env;

    @Inject
    private ServerContext serverContext;

    @Param(name="node", alias="nodeagent")
    String node;

    @Param(name = "config", optional=true)
    @I18n("generic.config")
    String configRef;

    @Param(name="cluster", optional=true)
    String clusterName;

    @Param(name="lbenabled", optional = true)
    private Boolean lbEnabled;

    @Param(name = "checkports", optional = true, defaultValue = "true")
    private boolean checkPorts;

    @Param(optional = true, defaultValue = "false")
    private boolean terse;

    @Param(name = "portbase", optional = true)
    private String portBase;

    @Param(name = "systemproperties", optional = true, separator = ':')
    private String systemProperties;

    @Param(name = "instance_name", primary = true)
    private String instance;

    private Logger logger;
    private AdminCommandContext ctx;
    private Node theNode = null;
    private String nodeHost = null;
    private String nodeDir = null;
    private String installDir = null;
    private String registerInstanceMessage = null;
    private InstanceDirUtils insDU;

    @Override
    public void execute(AdminCommandContext context) {
        ActionReport report = context.getActionReport();
        ctx = context;
        logger = context.logger;

        if(!env.isDas()) {
            String msg = Strings.get("notAllowed");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        // Make sure Node is valid
        theNode = nodes.getNode(node);
        if (theNode == null) {
            String msg = Strings.get("noSuchNode", node);
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        if (lbEnabled != null && clusterName == null) {
            String msg = Strings.get("lbenabledNotForStandaloneInstance");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        nodeHost = theNode.getNodeHost();
        nodeDir = theNode.getNodeDirAbsolute();
        installDir = theNode.getInstallDir();

        if (!StringUtils.ok(nodeHost)) {
            String msg = Strings.get("create.instance.missing.info",
                    theNode.getName(),"nodehost", "create-instance", "update-node-config",
                    "create-local-instance");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }
        if (!StringUtils.ok(installDir)) {
            String msg = Strings.get("create.instance.missing.info",
                    theNode.getName(),"installdir", "create-instance", "update-node-config",
                    "create-local-instance");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        // First, update domain.xml by calling _register-instance
        CommandInvocation ci = cr.getCommandInvocation("_register-instance", report);
        ParameterMap map = new ParameterMap();
        map.add("node", node);
        map.add("config", configRef);
        map.add("cluster", clusterName);
        if(lbEnabled != null){
            map.add("lbenabled", lbEnabled.toString());
        }
        if(!checkPorts){
            map.add("checkports", "false");
        }
        if(StringUtils.ok(portBase)){
            map.add("portbase", portBase);
        }
        map.add("systemproperties", systemProperties);
        map.add("DEFAULT", instance);
        ci.parameters(map);
        ci.execute();


        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS &&
            report.getActionExitCode() != ActionReport.ExitCode.WARNING) {
            // If we couldn't update domain.xml then stop!
            return;
        }

        registerInstanceMessage = report.getMessage();

        // Then go create the instance filesystem on the node
        createInstanceFilesystem(context);            
    }

    /**
     * Returns the directory for the selected instance that is on the local
     * system.
     * @param instanceName name of the instance
     * @return File for the local file system location of the instance directory
     * @throws IOException
     */
    private File getLocalInstanceDir() throws IOException {
        /*
         * Pass the node directory parent and the node directory name explicitly
         * or else InstanceDirs will not work as we want if there are multiple
         * nodes registered on this node.
         * 
         * If the configuration recorded an explicit directory for the node,
         * then use it.  Otherwise, use the default node directory of
         * ${installDir}/glassfish/nodes/${nodeName}.
         */
        final File nodeDirFile = (nodeDir != null ?
            new File(nodeDir) :
            defaultLocalNodeDirFile());
        InstanceDirs instanceDirs = new InstanceDirs(nodeDirFile.toString(), theNode.getName(), instance);
        return instanceDirs.getInstanceDir();
    }

    private File defaultLocalNodeDirFile() {
        /*
         * The "nodes" directory we want to use is a child of
         * the install directory.
         *
         * The installDir field contains the installation directory which the
         * administrator specified, if s/he specified one, when the target node
         * was first created.  It is null if the administrator did not specify
         * an installation directory for the node.  In that case we should
         * use the DAS's install directory (because this method applies in the
         * local instance case).
         */
        final File nodeParentDir = (
                installDir == null
                    ? serverContext.getInstallRoot()
                    : new File(installDir, "glassfish"));
        return new File(nodeParentDir, "nodes");
    }

    private File getDomainInstanceDir() {
        return env.getInstanceRoot();
    }

    /**
     *
     * Delivers bootstrap files for secure admin locally, because the instance
     * is on the same system as the DAS (and therefore on the same system where
     * this command is running).
     *
     * @return 0 if successful, 1 otherwise
     */
    private int bootstrapSecureAdminLocally() {
        final ActionReport report = ctx.getActionReport();

        try {
            final SecureAdminBootstrapHelper bootHelper =
                    SecureAdminBootstrapHelper.getLocalHelper(
                        env.getInstanceRoot(),
                        getLocalInstanceDir());
            bootHelper.bootstrapInstance();
            return 0;
        } catch (Exception ex) {
            String msg = Strings.get("create.instance.local.boot.failed", instance, node, nodeHost);
            logger.log(Level.SEVERE, msg, ex);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return 1;
        }
    }

    /**
     * Delivers bootstrap files for secure admin remotely, because the instance
     * is NOT on the same system as the DAS.
     *
     * @return 0 if successful; 1 otherwise
     */
    private int bootstrapSecureAdminRemotely() {
        ActionReport report = ctx.getActionReport();
        // nodedir is the root of where all the node dirs will be created.  
        // add the name of the node as that is where the instance files should be created
        String thisNodeDir = null;
        if (nodeDir != null)
            thisNodeDir = nodeDir + "/" + node;
        try {
            final SecureAdminBootstrapHelper bootHelper =
                SecureAdminBootstrapHelper.getRemoteHelper(
                    habitat,
                    getDomainInstanceDir(),
                    thisNodeDir,
                    instance,
                    theNode, logger);
            bootHelper.bootstrapInstance();
            return 0;
        } catch (Exception ex) {
            String exmsg = ex.getMessage();
            if (exmsg == null) {
                // The root cause message is better than no message at all
                exmsg = ExceptionUtil.getRootCause(ex).toString();
            }
            String msg = Strings.get(
                    "create.instance.remote.boot.failed",
                    instance,
                    (ex instanceof SecureAdminBootstrapHelper.BootstrapException ? 
                        ((SecureAdminBootstrapHelper.BootstrapException)ex).sshSettings() : null),
                    exmsg,
                    nodeHost);
            logger.severe(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return 1;
        }
    }

    public void createInstanceFilesystem(AdminCommandContext context) {
        ActionReport report = ctx.getActionReport();
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);

        NodeUtils nodeUtils = new NodeUtils(habitat, logger);
        Server dasServer =
                servers.getServer(SystemPropertyConstants.DAS_SERVER_NAME);
        String dasHost = dasServer.getAdminHost();
        String dasPort = Integer.toString(dasServer.getAdminPort());

        ArrayList<String> command = new ArrayList<String>();
        String humanCommand = null;

        if (!theNode.isLocal()) {
            // Only specify the DAS host if the node is remote. See issue 13993
            command.add("--host");
            command.add(dasHost);
        }

        command.add("--port");
        command.add(dasPort);

        command.add("_create-instance-filesystem");

        if (nodeDir != null) {
            command.add("--nodedir");
            command.add(nodeDir); //XXX escape spaces?
        }

        command.add("--node");
        command.add(node);

        command.add(instance);

        humanCommand = makeCommandHuman(command);
        if (!theNode.isLocal() && !theNode.getType().equals("SSH")){
            String msg = Strings.get("create.instance.config",
                    instance, humanCommand);
            msg = StringUtils.cat(NL, registerInstanceMessage, msg );
            report.setMessage(msg);
            return;
        }

        // First error message displayed if we fail
        String firstErrorMessage = Strings.get("create.instance.filesystem.failed",
                        instance, node, nodeHost );

        StringBuilder output = new StringBuilder();

        // Run the command on the node and handle errors.
        nodeUtils.runAdminCommandOnNode(theNode, command, ctx, firstErrorMessage,
                humanCommand, output);

        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {
            // something went wrong with the nonlocal command don't continue but set status to success
            // because config was updated correctly or we would not be here.
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);            
            return;
        }

        // If it was successful say so and display the command output
        String msg = Strings.get("create.instance.success",
                    instance, nodeHost);
        if (!terse) {
            msg = StringUtils.cat(NL,
                    output.toString().trim(), registerInstanceMessage, msg);
        }
        report.setMessage(msg);

        // Bootstrap secure admin files
        if (theNode.isLocal()) {
            bootstrapSecureAdminLocally();
        } else {
            bootstrapSecureAdminRemotely();
        }
             // something went wrong with the nonlocal command don't continue but set status to success
            // because config was updated correctly or we would not be here.
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }

    private String makeCommandHuman(List<String> command) {
        StringBuilder fullCommand = new StringBuilder();

        fullCommand.append("asadmin ");

        for (String s : command) {
            if (s.equals("_create-instance-filesystem")) {
                // We tell the user to run create-local-instance, not the
                // hidden command
                fullCommand.append(" ");
                fullCommand.append("create-local-instance");
            } else {
                fullCommand.append(" ");
                fullCommand.append(s);
            }
        }

        return fullCommand.toString();
    }

}
