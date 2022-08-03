package com.bambrow.pod.service;

import com.bambrow.pod.config.ControllerConfig;
import io.kubernetes.client.Exec;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.kubectl.Kubectl;
import io.kubernetes.client.extended.kubectl.exception.KubectlException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.ModelMapper;
import io.kubernetes.client.util.Streams;
import io.kubernetes.client.util.generic.options.ListOptions;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ControllerService {

    private ControllerConfig config;
    private String namespace;

    private final String POD_LABEL_KEY = "active-standby-status";
    private final String POD_LABEL_ACTIVE = "active";
    private final String POD_NAMESPACE = "POD_NAMESPACE";

    private final Logger log = LoggerFactory.getLogger(ControllerService.class);

    @PostConstruct
    private void init() throws IOException {
        log.info("Initializing...");
        ModelMapper.addModelMap("", "v1", "Namespace", "namespaces", false, V1Namespace.class);
        ModelMapper.addModelMap("", "v1", "Pod", "pods", true, V1Pod.class);
        ModelMapper.addModelMap("", "v1", "Service", "services", true, V1Service.class);
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);
        namespace = System.getenv(POD_NAMESPACE);
        log.info("Setting namespace: {}", namespace);
    }

    @Scheduled(fixedRate = 5000)
    private void run() {
        List<ControllerConfig.ServiceElection> serviceElections = config.getServices();
        for (ControllerConfig.ServiceElection serviceElection : serviceElections) {
            log.info("Get service election: {}", serviceElection.toString());
            if (serviceElection.isValid()) {
                String serviceName = serviceElection.getServiceName();
                V1Service service = getServiceWithName(serviceName);
                if (service != null) {
                    List<V1Pod> podList = getPodsByService(service);
                    if (!podList.isEmpty()) {
                        electActivePod(service, podList, serviceElection);
                    }
                }
            }
        }
    }

    private V1Service getServiceWithName(String serviceName) {
        try {
            V1Service service = Kubectl.get(V1Service.class).skipDiscovery().namespace(namespace).name(serviceName).execute();
            log.info("Find service: {}", serviceName);
            return service;
        } catch (KubectlException e) {
            log.error("Error when finding service with namespace: {}, serviceName: {}", namespace, serviceName);
            e.printStackTrace();
            return null;
        }
    }

    private List<V1Pod> getPodsByService(V1Service service) {
        // find service selectors, without our customized label
        Map<String, String> selectors = service.getSpec() != null ? service.getSpec().getSelector() : new HashMap<>();
        if (selectors.containsKey(POD_LABEL_KEY)) {
            selectors.remove(POD_LABEL_KEY);
        }
        String labelSelector = selectors.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).collect(Collectors.joining(","));
        ListOptions options = new ListOptions();
        options.setLabelSelector(labelSelector);
        // find pods with service selectors
        try {
            List<V1Pod> podList = Kubectl.get(V1Pod.class).skipDiscovery().namespace(namespace).options(options).execute();
            log.info("Find pods: {}", podList.stream().map(x -> x.getMetadata().getName()).collect(Collectors.joining(",")));
            return podList;
        } catch (KubectlException e) {
            log.error("Error when finding pods with label: {}" + labelSelector);
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private void electActivePod(V1Service service, List<V1Pod> podList, ControllerConfig.ServiceElection serviceElection) {
        // check if there is an active pod running, delabel it if not running
        for (V1Pod pod : podList) {
            if (pod.getMetadata().getLabels().containsKey(POD_LABEL_KEY) && POD_LABEL_ACTIVE.equals(pod.getMetadata().getLabels().get(POD_LABEL_KEY))) {
                if (isPodRunning(pod)) {
                    log.info("Find a running pod with active label: {}", pod.getMetadata().getName());
                    if (!serviceElection.isAutoElect()) {
                        String[] activeScript = serviceElection.getActiveScript();
                        String activeOutput = serviceElection.getActiveOutput();
                        if (executeCmd(pod.getMetadata().getName(), activeScript, activeOutput)) {
                            log.info("Confirm the running pod is truly active: {}", pod.getMetadata().getName());
                            addSelectorToService(service);
                            return;
                        } else {
                            log.info("The running pod is not truly active, delabel it: {}", pod.getMetadata().getName());
                            delabelActivePod(pod.getMetadata().getName());
                        }
                    } else {
                        addSelectorToService(service);
                        return;
                    }
                } else {
                    log.info("Find a pod with active label but not running, delabel it: {}" + pod.getMetadata().getName());
                    delabelActivePod(pod.getMetadata().getName());
                }
            }
        }
        // get eligible pods for election
        List<V1Pod> eligiblePodList = podList.stream().filter(this::isPodRunning).collect(Collectors.toList());
        boolean elected = false;
        // if auto elect, choose the first one; otherwise use predefined script and output
        if (serviceElection.isAutoElect()) {
            V1Pod activePod = eligiblePodList.get(0);
            log.info("Auto elect the first pod: {}", activePod.getMetadata().getName());
            if (labelActivePod(activePod.getMetadata().getName())) {
                elected = true;
            }
        } else {
            String[] activeScript = serviceElection.getActiveScript();
            String activeOutput = serviceElection.getActiveOutput();
            for (V1Pod eligiblePod : eligiblePodList) {
                if (executeCmd(eligiblePod.getMetadata().getName(), activeScript, activeOutput)) {
                    log.info("Active script passed in pod: {}", eligiblePod.getMetadata().getName());
                    if (labelActivePod(eligiblePod.getMetadata().getName())) {
                        elected = true;
                        break;
                    }
                }
            }
        }
        // finally, add selector to service if necessary
        if (elected) {
            addSelectorToService(service);
        }
    }

    private boolean addSelectorToService(V1Service service) {
        Map<String, String> selectors = service.getSpec() != null ? service.getSpec().getSelector() : new HashMap<>();
        if (!selectors.containsKey(POD_LABEL_KEY)) {
            V1Patch patch = new V1Patch(String.format("{\"spec\":{\"selector\":{\"%s\":\"%s\"}}}", POD_LABEL_KEY, POD_LABEL_ACTIVE));
            try {
                Kubectl.patch(V1Service.class).skipDiscovery().namespace(namespace).name(service.getMetadata().getName()).patchType(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH).patchContent(patch).execute();
                log.info("Patching service: {} with patch: {}", service.getMetadata().getName(), patch.getValue());
                return true;
            } catch (KubectlException e) {
                log.error("Error patching service: {} with patch: {}", service.getMetadata().getName(), patch.getValue());
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    private boolean executeCmd(String podName, String[] command, String activeOutput) {
        Exec exec = new Exec();
        log.info("Executing command: " + Arrays.toString(command));
        try {
            Process proc = exec.exec(namespace, podName, command, true, true);
            OutputStream os = new ByteArrayOutputStream();
            Thread out = new Thread(
                    () -> {
                        try {
                            Streams.copy(proc.getInputStream(), os);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
            );
            out.start();
            proc.waitFor();
            out.join();
            String output = os.toString();
            log.info("Executing command output: {}", output);
            boolean equal = StringUtils.equals(output == null ? null : output.trim(), activeOutput);
            log.info("Comparing {} with {}: {}", output == null ? null : output.trim(), activeOutput, equal);
            return equal;
        } catch (ApiException | IOException | InterruptedException e) {
            log.error("Executing command failed: {} in pod {}", Arrays.toString(command), podName);
            e.printStackTrace();
            return false;
        }
    }

    private boolean isPodRunning(V1Pod pod) {
        return "Running".equals(pod.getStatus().getPhase());
    }

    private boolean labelActivePod(String podName) {
        try {
            Kubectl.label(V1Pod.class).skipDiscovery().namespace(namespace).name(podName).addLabel(POD_LABEL_KEY, POD_LABEL_ACTIVE).execute();
            log.info("Labelling pod: {} with label: {}={}", podName, POD_LABEL_KEY, POD_LABEL_ACTIVE);
            return true;
        } catch (KubectlException e) {
            log.error("Error labelling pod: {} with label: {}={}", podName, POD_LABEL_KEY, POD_LABEL_ACTIVE);
            e.printStackTrace();
            return false;
        }
    }

    private boolean delabelActivePod(String podName) {
        try {
            Kubectl.label(V1Pod.class).skipDiscovery().namespace(namespace).name(podName).addLabel(POD_LABEL_KEY, "none").execute();
            log.info("Delabelling pod: {} with label: {}={}", podName, POD_LABEL_KEY, "none");
            return true;
        } catch (KubectlException e) {
            log.error("Error delabelling pod: {} with label: {}={}", podName, POD_LABEL_KEY, "none");
            e.printStackTrace();
            return false;
        }
    }

    @Autowired
    private void setConfig(ControllerConfig config) {
        this.config = config;
    }

}
