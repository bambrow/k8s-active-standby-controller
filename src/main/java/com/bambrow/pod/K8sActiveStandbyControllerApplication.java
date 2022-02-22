package com.bambrow.pod;

import com.bambrow.pod.service.ControllerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableScheduling
@RestController
public class K8sActiveStandbyControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(K8sActiveStandbyControllerApplication.class, args);
    }

    private ControllerService service;

    @RequestMapping("/")
    public String healthCheck() {
        return "OK!";
    }

    @Autowired
    public void setService(ControllerService service) {
        this.service = service;
    }
}
