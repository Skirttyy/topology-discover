package com.topo.discovery.collector;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Executa comenzi SSH pe echipamente de retea.
 * FARA bootstrap config - SNMP si LLDP sunt deja active pe device-uri.
 */
@Component
@Slf4j
public class SshCommandExecutor {

    @Value("${discovery.ssh.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${discovery.ssh.command-timeout-ms}")
    private int commandTimeoutMs;

    /**
     * Executa o comanda si returneaza output-ul brut.
     */
    public String executeCommand(String host, int port, String username, String password, String command) {
        if (username == null || username.isBlank()) {
            throw new SshExecutionException("SSH username null/blank pentru " + host);
        }
        if (password == null) {
            throw new SshExecutionException("SSH password null pentru " + host);
        }
        Session session = null;
        ChannelExec channel = null;
        try {
            session = openSession(host, port, username, password);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            channel.connect();

            // citim stream-ul de input pana se inchide canalul
            InputStream in = channel.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + commandTimeoutMs;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                while (in.available() > 0) {
                    int len = in.read(buf);
                    if (len > 0) stdout.write(buf, 0, len);
                }
                Thread.sleep(50);
            }
            // citim ce a mai ramas
            while (in.available() > 0) {
                int len = in.read(buf);
                if (len > 0) stdout.write(buf, 0, len);
            }

            return stdout.toString();
        } catch (JSchException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "JSchException fara mesaj";
            String reason = categorizeSshError(msg);
            log.warn("SSH [{}] catre {}:{} — {}", reason, host, port, msg);
            throw new SshExecutionException("SSH " + reason + " catre " + host + ": " + msg, e);
        } catch (Exception e) {
            log.warn("SSH eroare IO pe {}:{} — {}", host, port, e.getMessage());
            throw new SshExecutionException("SSH IO eroare pe " + host, e);
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    /**
     * Verifica daca portul SSH e deschis (folosit la scanarea subnet-ului).
     */
    public boolean isSshReachable(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Session openSession(String host, int port, String username, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        // algorimi extinsi: mwiede/jsch suporta rsa-sha2-256/512 care lipseau in 0.1.55
        // si care cauzau JSchException("java.lang.NullPointerException") pe vJunos/vEOS
        config.put("server_host_key",
                "rsa-sha2-256,rsa-sha2-512,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ssh-ed25519");
        config.put("kex",
                "curve25519-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384," +
                "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1," +
                "diffie-hellman-group-exchange-sha256");
        config.put("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc");
        config.put("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,3des-cbc");
        config.put("mac.s2c", "hmac-sha2-256,hmac-sha1,hmac-sha2-256-etm@openssh.com");
        config.put("mac.c2s", "hmac-sha2-256,hmac-sha1,hmac-sha2-256-etm@openssh.com");
        session.setConfig(config);
        session.setTimeout(connectTimeoutMs);
        session.connect(connectTimeoutMs);
        return session;
    }

    private static String categorizeSshError(String msg) {
        if (msg == null) return "EROARE_NECUNOSCUTA";
        String lower = msg.toLowerCase();
        if (lower.contains("auth") || lower.contains("authentication"))      return "AUTH_FAIL";
        if (lower.contains("connection refused"))                             return "CONN_REFUSED";
        if (lower.contains("timeout") || lower.contains("timed out"))        return "TIMEOUT";
        if (lower.contains("no route") || lower.contains("unreachable"))     return "UNREACHABLE";
        if (lower.contains("nullpointerexception") || lower.contains("kex")) return "KEX_FAIL";
        if (lower.contains("algorithm"))                                     return "ALGO_MISMATCH";
        return "EROARE";
    }

    public static class SshExecutionException extends RuntimeException {
        public SshExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
        public SshExecutionException(String message) {
            super(message);
        }
    }
}
