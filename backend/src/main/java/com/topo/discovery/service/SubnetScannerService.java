package com.topo.discovery.service;

import com.topo.discovery.collector.SshCommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Scanează un subnet în notație CIDR (ex: 192.168.100.0/24) și găsește
 * adresele IP la care portul SSH (22) e deschis - acestea sunt considerate
 * device-uri "vii" candidate pentru a fi adăugate ca seed devices.
 *
 * Scanarea se face în paralel (thread pool) pentru viteză, dar limitată
 * (discovery.subnet-scan.parallel-threads) ca să nu sufoce rețeaua de lab.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubnetScannerService {

    private final SshCommandExecutor sshExecutor;

    @Value("${discovery.subnet-scan.parallel-threads}")
    private int parallelThreads;

    @Value("${discovery.subnet-scan.port-check}")
    private int portToCheck;

    @Value("${discovery.subnet-scan.port-timeout-ms}")
    private int portTimeoutMs;

    /**
     * @param cidr subnet in format CIDR, ex: "192.168.100.0/24"
     * @return lista de adrese IP la care s-a gasit portul SSH deschis
     */
    public List<String> scanForLiveHosts(String cidr) {
        List<String> allIps = expandCidr(cidr);
        log.info("Scanez {} adrese IP din subnet-ul {} pentru SSH deschis...", allIps.size(), cidr);

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<Future<String>> futures = new ArrayList<>();

        for (String ip : allIps) {
            futures.add(executor.submit(() -> {
                boolean reachable = sshExecutor.isSshReachable(ip, portToCheck, portTimeoutMs);
                return reachable ? ip : null;
            }));
        }

        List<String> liveHosts = new ArrayList<>();
        for (Future<String> future : futures) {
            try {
                String result = future.get(portTimeoutMs + 2000, TimeUnit.MILLISECONDS);
                if (result != null) {
                    liveHosts.add(result);
                }
            } catch (Exception e) {
                // host neresponsiv / timeout - normal pentru IP-uri neutilizate, ignoram
            }
        }

        executor.shutdown();
        log.info("Gasite {} device-uri vii in subnet-ul {}", liveHosts.size(), cidr);
        return liveHosts;
    }

    /** Expandeaza un CIDR in lista de adrese IP individuale (exclude network/broadcast pentru /24 si mai mici). */
    private List<String> expandCidr(String cidr) {
        String[] parts = cidr.split("/");
        String baseIp = parts[0];
        int prefixLength = Integer.parseInt(parts[1]);

        long ipAsLong = ipToLong(baseIp);
        long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        long network = ipAsLong & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);

        List<String> ips = new ArrayList<>();
        // pentru subnet-uri mici (/31, /32) includem toate adresele; altfel excludem network/broadcast
        long start = (prefixLength >= 31) ? network : network + 1;
        long end = (prefixLength >= 31) ? broadcast : broadcast - 1;

        for (long i = start; i <= end; i++) {
            ips.add(longToIp(i));
        }
        return ips;
    }

    private long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        long result = 0;
        for (String octet : octets) {
            result = (result << 8) | Integer.parseInt(octet);
        }
        return result;
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }
}
