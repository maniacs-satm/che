/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.docker.client;

import com.google.common.collect.ImmutableMap;
import com.openshift.internal.restclient.ResourceFactory;
import com.openshift.internal.restclient.model.DeploymentConfig;
import com.openshift.internal.restclient.model.Pod;
import com.openshift.internal.restclient.model.Port;
import com.openshift.internal.restclient.model.Service;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.IResourceFactory;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.images.DockerImageURI;
import com.openshift.restclient.model.*;
import com.openshift.restclient.model.deploy.DeploymentTriggerType;
import org.eclipse.che.plugin.docker.client.json.ContainerCreated;
import org.eclipse.che.plugin.docker.client.json.ContainerInfo;
import org.eclipse.che.plugin.docker.client.json.PortBinding;
import org.eclipse.che.plugin.docker.client.params.CreateContainerParams;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * Client for OpenShift API.
 *
 * @author ML
 */
@Singleton
public class OpenShiftConnector {
    private static final Logger LOG = LoggerFactory.getLogger(OpenShiftConnector.class);
    private static final String OPENSHIFT_API_VERSION = "v1";
    private static final String CHE_WORKSPACE_ID_ENV_VAR = "CHE_WORKSPACE_ID";
    private static final String CHE_OPENSHIFT_RESOURCES_PREFIX = "che-ws-";

    private static final String CHE_DEFAULT_OPENSHIFT_PROJECT_NAME = "eclipse-che";
    private static final String CHE_DEFAULT_OPENSHIFT_SERVICEACCOUNT = "cheserviceaccount";
    private static final String OPENSHIFT_DEFAULT_API_ENDPOINT = "https://10.0.2.15:8443/";
    private static final String OPENSHIFT_DEFAULT_USER_NAME = "openshift-dev";
    private static final String OPENSHIFT_DEFAULT_USER_PASSWORD = "devel";
    private static final String OPENSHIFT_SERVICE_TYPE_NODE_PORT = "NodePort";
    public  static final String DOCKER_PROTOCOL_PORT_DELIMITER = "/";
    public static final String IMAGE_PULL_POLICY_ALWAYS = "Always";
    public static final String CHE_DEFAULT_EXTERNAL_ADDRESS = "172.17.0.1";

    private final IClient            openShiftClient;
    private final IResourceFactory   openShiftFactory;
    private final String             cheOpenShiftProjectName;
    private final String             cheOpenShiftServiceAccount;
    private final String             openShiftApiEndpoint;
    private final String             openShiftUserName;
    private final String             openShiftUserPassword;

    public final Map<Integer, String> servicePortNames = ImmutableMap.<Integer, String>builder().
                                                                put(22, "sshd").
                                                                put(4401, "wsagent").
                                                                put(4403, "wsagent-jpda").
                                                                put(4411, "terminal").
                                                                put(8080, "tomcat").
                                                                put(8000, "tomcat-jpda").
                                                                put(9876, "codeserver").build();
    private final String wsAgentExternalAddress;

    @Inject
    public OpenShiftConnector() {
        // Hardcoded values. Should be injected instead
        this.cheOpenShiftProjectName = CHE_DEFAULT_OPENSHIFT_PROJECT_NAME;
        this.cheOpenShiftServiceAccount = CHE_DEFAULT_OPENSHIFT_SERVICEACCOUNT;
        this.openShiftApiEndpoint = OPENSHIFT_DEFAULT_API_ENDPOINT;
        this.wsAgentExternalAddress = CHE_DEFAULT_EXTERNAL_ADDRESS;
        this.openShiftUserName = OPENSHIFT_DEFAULT_USER_NAME;
        this.openShiftUserPassword = OPENSHIFT_DEFAULT_USER_PASSWORD;

        this.openShiftClient = new ClientBuilder(this.openShiftApiEndpoint)
                .withUserName(this.openShiftUserName)
                .withPassword(this.openShiftUserPassword)
                .build();
        this.openShiftFactory = new ResourceFactory(openShiftClient);
    }

    /**
     * @param createContainerParams
     * @return
     * @throws IOException
     */
    public ContainerCreated createContainer(CreateContainerParams createContainerParams) throws IOException {

        String containerName = getNormalizedContainerName(createContainerParams);
        String workspaceID = getCheWorkspaceId(createContainerParams);
        String imageName = "mariolet/che-ws-agent";//"172.30.166.244:5000/eclipse-che/che-ws-agent:latest";//createContainerParams.getContainerConfig().getImage();
        Map<String, Map<String, String>> exposedPorts = createContainerParams.getContainerConfig().getExposedPorts();
        Map<String, PortBinding[]> portBindings = createContainerParams.getContainerConfig().getHostConfig().getPortBindings();
        String[] envVariables = createContainerParams.getContainerConfig().getEnv();

        IProject cheProject = getCheProject();
        createOpenShiftService(cheProject, workspaceID, exposedPorts);
        String deploymentConfigName = createOpenShiftDeploymentConfig(cheProject,
                                                                      workspaceID,
                                                                      imageName,
                                                                      containerName,
                                                                      exposedPorts,
                                                                      envVariables);

        String containerID = waitAndRetrieveContainerID(cheProject, deploymentConfigName);
        if (containerID == null) {
            throw new RuntimeException("Failed to get the ID of the container running in the OpenShift pod");
        }

        return new ContainerCreated(containerID, null);
    }

    /**
     * @param docker
     * @param container
     * @return
     * @throws IOException
     */
    public ContainerInfo inspectContainer(DockerConnector docker, String container) throws IOException {
        // Proxy to DockerConnector
        ContainerInfo info = docker.inspectContainer(container);
        if (info == null) {
            return null;
        }
        // Ignore portMapping for now: info.getNetworkSettings().setPortMapping();
        // replacePortMapping(info)
        replaceNetworkSettings(info);
        replaceLabels(info);

        return info;
    }

    private String getNormalizedContainerName(CreateContainerParams createContainerParams) {
        String containerName = createContainerParams.getContainerName();
        // The name of a container in Kubernetes should be a
        // valid hostname as specified by RFC 1123 (i.e. max length
        // of 63 chars and no underscores)
        return containerName.substring(9).replace('_', '-');
    }

    protected String getCheWorkspaceId(CreateContainerParams createContainerParams) {
        Stream<String> env = Arrays.stream(createContainerParams.getContainerConfig().getEnv());
        String workspaceID = env.filter(v -> v.startsWith(CHE_WORKSPACE_ID_ENV_VAR) && v.contains("=")).
                                 map(v -> v.split("=",2)[1]).
                                 findFirst().
                                 orElse("");
        return workspaceID.replaceFirst("workspace","");
    }

    private IProject getCheProject() throws IOException {
        List<IProject> list = openShiftClient.list(ResourceKind.PROJECT);
        IProject cheProject = list.stream()
                                   .filter(p -> p.getName().equals(cheOpenShiftProjectName))
                                   .findFirst().orElse(null);
        if (cheProject == null) {
            LOG.error("OpenShift project " + cheOpenShiftProjectName + " not found");
            throw new IOException("OpenShift project " + cheOpenShiftProjectName + " not found");
        }
        return cheProject;
    }

    private void createOpenShiftService(IProject cheProject, String workspaceID, Map<String, Map<String, String>> exposedPorts) {
        IService service = openShiftFactory.create(OPENSHIFT_API_VERSION, ResourceKind.SERVICE);
        ((Service) service).setNamespace(cheProject.getNamespace());
        ((Service) service).setName(CHE_OPENSHIFT_RESOURCES_PREFIX + workspaceID);
        service.setType(OPENSHIFT_SERVICE_TYPE_NODE_PORT);

        List<IServicePort> openShiftPorts = getServicePortsFrom(exposedPorts);
        service.setPorts(openShiftPorts);

        service.setSelector("deploymentConfig", (CHE_OPENSHIFT_RESOURCES_PREFIX + workspaceID));
        LOG.info(String.format("Createing service: %s", service));
        openShiftClient.create(service);
    }

    private String createOpenShiftDeploymentConfig(IProject cheProject,
                                                   String workspaceID,
                                                   String imageName,
                                                   String sanitizedContaninerName,
                                                   Map<String, Map<String, String>> exposedPorts,
                                                   String[] envVariables) {
        IDeploymentConfig dc = openShiftFactory.create(OPENSHIFT_API_VERSION, ResourceKind.DEPLOYMENT_CONFIG);
        ((DeploymentConfig) dc).setName(CHE_OPENSHIFT_RESOURCES_PREFIX + workspaceID);
        ((DeploymentConfig) dc).setNamespace(cheProject.getName());
        ((DeploymentConfig) dc).getNode().get("spec").get("template").get("spec").get("dnsPolicy").set("Default");
        dc.setReplicas(1);
        dc.setReplicaSelector("deploymentConfig", CHE_OPENSHIFT_RESOURCES_PREFIX + workspaceID );
        dc.setServiceAccountName(this.cheOpenShiftServiceAccount);

        addContainer(dc, workspaceID, imageName, sanitizedContaninerName, exposedPorts, envVariables);
        dc.addTrigger(DeploymentTriggerType.CONFIG_CHANGE);
        openShiftClient.create(dc);
        return dc.getName();
    }

    private void addContainer(IDeploymentConfig dc, String workspaceID, String imageName, String containerName, Map<String, Map<String, String>> exposedPorts, String[] envVariables) {
        Set<IPort> containerPorts = getContainerPortsFrom(exposedPorts);
        Map<String, String> containerEnv = getContainerEnvFrom(envVariables);
        dc.addContainer(containerName,
                new DockerImageURI(imageName),
                containerPorts,
                containerEnv,
                Collections.emptyList());
        dc.getContainer(containerName).setImagePullPolicy(IMAGE_PULL_POLICY_ALWAYS);
    }

    private String waitAndRetrieveContainerID(IProject cheproject, String deploymentConfigName) {
        String deployerLabelKey = "openshift.io/deployer-pod-for.name";
        for (int i = 0; i < 120; i++) {
            try {
                Thread.sleep(1000);                 //1000 milliseconds is one second.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

            List<IPod> pods = openShiftClient.list(ResourceKind.POD, cheproject.getNamespace(), Collections.emptyMap());
            long deployPodNum = pods.stream().filter(p -> p.getLabels().keySet().contains(deployerLabelKey)).count();

            if (deployPodNum == 0) {
                LOG.info("Pod has been deployed.");
                for (IPod pod : pods) {
                    if (pod.getLabels().get("deploymentConfig").equals(deploymentConfigName)) {
                        ModelNode containerID = ((Pod) pod).getNode().get("status").get("containerStatuses").get(0).get("containerID");
                        return containerID.toString().substring(10, 74);
                    }
                }
            }
        }
        return null;
    }


    public List<IServicePort> getServicePortsFrom(Map<String, Map<String, String>> exposedPorts) {
        List<IServicePort> servicePorts = new ArrayList<>();
        for (String exposedPort: exposedPorts.keySet()){
            String[] portAndProtocol = exposedPort.split(DOCKER_PROTOCOL_PORT_DELIMITER,2);

            String port = portAndProtocol[0];
            String protocol = portAndProtocol[1];

            int portNumber = Integer.parseInt(port);
            String portName = isNullOrEmpty(servicePortNames.get(portNumber)) ?
                    exposedPort : servicePortNames.get(portNumber);
            int targetPortNumber = portNumber;

            IServicePort servicePort = OpenShiftPortFactory.createServicePort(
                    portName,
                    protocol,
                    portNumber,
                    targetPortNumber);
            servicePorts.add(servicePort);
        }
        return servicePorts;
    }

    public Set<IPort> getContainerPortsFrom(Map<String, Map<String, String>> exposedPorts) {
        Set<IPort> containerPorts = new HashSet<>();
        for (String exposedPort: exposedPorts.keySet()){
            String[] portAndProtocol = exposedPort.split(DOCKER_PROTOCOL_PORT_DELIMITER,2);

            String port = portAndProtocol[0];
            String protocol = portAndProtocol[1].toUpperCase();

            int portNumber = Integer.parseInt(port);
            String portName = isNullOrEmpty(servicePortNames.get(portNumber)) ?
                    exposedPort : servicePortNames.get(portNumber);

            Port containerPort = new Port(new ModelNode());
            containerPort.setName(portName);
            containerPort.setProtocol(protocol);
            containerPort.setContainerPort(portNumber);
            containerPorts.add(containerPort);
        }
        return containerPorts;
    }

    public Map<String, String> getContainerEnvFrom(String[] envVariables){
        Map<String, String> env = new HashMap<>();
        for (String envVariable : envVariables) {
            String[] nameAndValue = envVariable.split("=",2);
            String varName = nameAndValue[0];
            String varValue = nameAndValue[1];
            env.put(varName, varValue);
        }
        return env;
    }

    private void replaceLabels(ContainerInfo info) {
        if (info.getConfig() == null) {
            return;
        }

        Map<String,String> configLabels = new HashMap<>();
        configLabels.put("che:server:8000:protocol", "http");
        configLabels.put("che:server:8000:ref", "tomcat8-debug");
        configLabels.put("che:server:8080:protocol", "http");
        configLabels.put("che:server:8080:ref", "tomcat8");
        configLabels.put("che:server:9876:protocol", "http");
        configLabels.put("che:server:9876:ref", "codeserver");
        info.getConfig().setLabels(configLabels);
    }

    private void replaceNetworkSettings(ContainerInfo info) throws IOException {

        if (info.getNetworkSettings() == null) {
            return;
        }

        IProject cheproject = getCheProject();

        IService service = getCheWorkspaceService(cheproject);
        Map<String, List<PortBinding>> networkSettingsPorts = getCheServicePorts((Service) service);

        info.getNetworkSettings().setPorts(networkSettingsPorts);
    }

    private IService getCheWorkspaceService(IProject cheproject) throws IOException {
        List<IService> services = openShiftClient.list(ResourceKind.SERVICE, cheproject.getNamespace(), Collections.emptyMap());
        // TODO: improve how the service is found (e.g. using a label with the workspaceid)
        IService service = services.stream().filter(s -> s.getName().startsWith(CHE_OPENSHIFT_RESOURCES_PREFIX)).findFirst().orElse(null);
        if (service == null) {
            LOG.error("No service with prefix " + CHE_OPENSHIFT_RESOURCES_PREFIX +" found");
            throw new IOException("No service with prefix " + CHE_OPENSHIFT_RESOURCES_PREFIX +" found");
        }
        return service;
    }

    private Map<String, List<PortBinding>> getCheServicePorts(Service service) {
        Map<String, List<PortBinding>> networkSettingsPorts = new HashMap<>();
        List<ModelNode> servicePorts = service.getNode().get("spec").get("ports").asList();
        LOG.info("Retrieving " + servicePorts.size() + " ports exposed by service " + service.getName());
        for (ModelNode servicePort : servicePorts) {
            String protocol = servicePort.get("protocol").asString();
            String targetPort = servicePort.get("targetPort").asString();
            String nodePort = servicePort.get("nodePort").asString();
            String portName = servicePort.get("name").asString();

            LOG.info("Port: " + targetPort + DOCKER_PROTOCOL_PORT_DELIMITER + protocol + " (" + portName + ")");

            networkSettingsPorts.put(targetPort + DOCKER_PROTOCOL_PORT_DELIMITER + protocol.toLowerCase(),
                    Collections.singletonList(new PortBinding().withHostIp(CHE_DEFAULT_EXTERNAL_ADDRESS).withHostPort(nodePort)));
        }
        return networkSettingsPorts;
    }

    private List<IServicePort> putServicePorts() {
        List<IServicePort> openShiftPorts = new ArrayList<>();

        IServicePort openShiftPort1 = OpenShiftPortFactory.createServicePort(
                "ssh",
                "tcp",
                22,
                22);
        openShiftPorts.add(openShiftPort1);

        IServicePort openShiftPort2 = OpenShiftPortFactory.createServicePort(
                "wsagent",
                "tcp",
                4401,
                4401);
        openShiftPorts.add(openShiftPort2);

        IServicePort openShiftPort3 = OpenShiftPortFactory.createServicePort(
                "wsagent-jpda",
                "tcp",
                4403,
                4403);
        openShiftPorts.add(openShiftPort3);

        IServicePort openShiftPort4 = OpenShiftPortFactory.createServicePort(
                "port1",
                "tcp",
                4411,
                4411);
        openShiftPorts.add(openShiftPort4);

        IServicePort openShiftPort5 = OpenShiftPortFactory.createServicePort(
                "tomcat",
                "tcp",
                8080,
                8080);
        openShiftPorts.add(openShiftPort5);

        IServicePort openShiftPort6 = OpenShiftPortFactory.createServicePort(
                "tomcat-jpda",
                "tcp",
                8888,
                8888);
        openShiftPorts.add(openShiftPort6);

        IServicePort openShiftPort7 = OpenShiftPortFactory.createServicePort(
                "port2",
                "tcp",
                9876,
                9876);
        openShiftPorts.add(openShiftPort7);
        return openShiftPorts;
    }

    private void putEnvVariables(Map<String, String> envVariables, String workspaceID) {
        envVariables.put("CHE_LOCAL_CONF_DIR", "/mnt/che/conf");
        envVariables.put("USER_TOKEN", "dummy_token");
        envVariables.put("CHE_API_ENDPOINT", "http://172.17.0.4:8080/wsmaster/api");
        envVariables.put("JAVA_OPTS", "-Xms256m -Xmx2048m -Djava.security.egd=file:/dev/./urandom");
        envVariables.put("CHE_WORKSPACE_ID", workspaceID);
        envVariables.put("CHE_PROJECTS_ROOT", "/projects");
//        envVariables.put("PATH","/opt/jdk1.8.0_45/bin:/home/user/apache-maven-3.3.9/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
//        envVariables.put("MAVEN_VERSION","3.3.9");
//        envVariables.put("JAVA_VERSION","8u45");
//        envVariables.put("JAVA_VERSION_PREFIX","1.8.0_45");
        envVariables.put("TOMCAT_HOME", "/home/user/tomcat8");
        //envVariables.put("JAVA_HOME","/opt/jdk1.8.0_45");
        envVariables.put("M2_HOME", "/home/user/apache-maven-3.3.9");
        envVariables.put("TERM", "xterm");
        envVariables.put("LANG", "en_US.UTF-8");
    }

    private void putContainerPorts(Set<IPort> containerPorts) {
        Port port1 = new Port(new ModelNode());
        port1.setName("ssh");
        port1.setProtocol("TCP");
        port1.setContainerPort(22);
        containerPorts.add(port1);
        Port port2 = new Port(new ModelNode());
        port2.setName("wsagent");
        port2.setProtocol("TCP");
        port2.setContainerPort(4401);
        containerPorts.add(port2);
        Port port3 = new Port(new ModelNode());
        port3.setName("wsagent-jpda");
        port3.setProtocol("TCP");
        port3.setContainerPort(4403);
        containerPorts.add(port3);
        Port port4 = new Port(new ModelNode());
        port4.setName("port1");
        port4.setProtocol("TCP");
        port4.setContainerPort(4411);
        containerPorts.add(port4);
        Port port5 = new Port(new ModelNode());
        port5.setName("tomcat");
        port5.setProtocol("TCP");
        port5.setContainerPort(8080);
        containerPorts.add(port5);
        Port port6 = new Port(new ModelNode());
        port6.setName("tomcat-jpda");
        port6.setProtocol("TCP");
        port6.setContainerPort(8888);
        containerPorts.add(port6);
        Port port7 = new Port(new ModelNode());
        port7.setName("port2");
        port7.setProtocol("TCP");
        port7.setContainerPort(9876);
        containerPorts.add(port7);
    }


}