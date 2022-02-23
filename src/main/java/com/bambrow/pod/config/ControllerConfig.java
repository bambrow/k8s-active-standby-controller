package com.bambrow.pod.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "k8s")
public class ControllerConfig {
    private List<ServiceElection> services;

    public List<ServiceElection> getServices() {
        return services;
    }

    public void setServices(List<ServiceElection> services) {
        this.services = services;
    }

    public static class ServiceElection {
        private String serviceName;
        private boolean autoElect;
        private String[] activeScript;
        private String activeOutput;

        public String getServiceName() {
            return serviceName;
        }

        public boolean isAutoElect() {
            return autoElect;
        }

        public String[] getActiveScript() {
            return activeScript;
        }

        public String getActiveOutput() {
            return activeOutput;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public void setAutoElect(boolean autoElect) {
            this.autoElect = autoElect;
        }

        public void setActiveScript(String[] activeScript) {
            this.activeScript = activeScript;
        }

        public void setActiveOutput(String activeOutput) {
            this.activeOutput = activeOutput;
        }

        @Override
        public String toString() {
            return "ServiceElection{" +
                    "serviceName='" + serviceName + '\'' +
                    ", autoElect=" + autoElect +
                    ", activeScript='" + Arrays.toString(activeScript) + '\'' +
                    ", activeOutput='" + activeOutput + '\'' +
                    '}';
        }

        public boolean isValid() {
            return StringUtils.isNotEmpty(serviceName);
        }
    }
}
