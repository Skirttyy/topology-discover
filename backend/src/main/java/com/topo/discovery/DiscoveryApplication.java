package com.topo.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Punct de intrare al aplicatiei.
 *
 * Teza de an: Descoperirea topologiei L2/L3 in retele multi-vendor (Juniper, Arista)
 *
 * @EnableAsync este necesar pentru ca discovery-ul sa ruleze pe thread-uri separate
 * (nu vrem sa blocam request-ul HTTP cat timp scanam un /24 intreg).
 */
@SpringBootApplication
@EnableAsync
public class DiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }
}
