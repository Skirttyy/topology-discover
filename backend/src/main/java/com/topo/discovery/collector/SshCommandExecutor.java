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
import java.io.OutputStream;
import java.util.List;
import java.util.Properties;

/**
 * Wrapper subtire peste JSch pentru a executa comenzi SSH pe echipamente
 * de retea (atat "show" commands, cat si secvente de configurare).
 *
 * Notes despre echipamente de retea vs servere Linux:
 * - Multe echipamente (mai ales Junos in modul "set") asteapta comenzi
 *   trimise secvential pe acelasi shell interactiv, nu fiecare intr-un
 *   canal exec separat (configurarea ar fi pierduta intre comenzi).
 *   De aceea folosim un singur canal "shell" si trimitem comenzile rand pe rand,
 *   citind output-ul intre ele.
 */
@Component
@Slf4j
public class SshCommandExecutor {

    @Value("${discovery.ssh.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${discovery.ssh.command-timeout-ms}")
    private int commandTimeoutMs;

    /**
     * Executa o singura comanda "show"-style si returneaza output-ul brut.
     * Foloseste un canal exec dedicat - simplu si suficient pentru comenzi
     * read-only care nu depind de starea sesiunii anterioare.
     */
    public String executeCommand(String host, int port, String username, String password, String command) {
        Session session = null;
        ChannelExec channel = null;
        try {
            session = openSession(host, port, username, password);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            channel.setOutputStream(output);
            channel.setErrStream(output);
            channel.connect();

            waitForChannelClose(channel);

            return output.toString();
        } catch (JSchException e) {
            log.error("Eroare SSH catre {}: {}", host, e.getMessage());
            throw new SshExecutionException("Conexiune SSH esuata catre " + host, e);
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    /**
     * Executa o secventa de comenzi pe un shell interactiv unic - necesar
     * pentru configurare Junos (mod "configure" -> set ... -> commit)
     * sau EOS (configure terminal -> ... -> end).
     */
    public String executeCommandSequence(String host, int port, String username, String password,
                                          List<String> commands) {
        Session session = null;
        ChannelExec channel = null;
        try {
            session = openSession(host, port, username, password);

            channel = (ChannelExec) session.openChannel("shell");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            channel.setOutputStream(output);
            channel.setErrStream(output);

            try (OutputStream input = channel.getOutputStream()) {
                channel.connect();

                for (String cmd : commands) {
                    input.write((cmd + "\n").getBytes());
                    input.flush();
                    // mic delay ca device-ul sa proceseze comanda inainte de urmatoarea
                    sleepQuiet(400);
                }
                sleepQuiet(800);
            }

            waitForChannelClose(channel);
            return output.toString();
        } catch (Exception e) {
            log.error("Eroare la secventa SSH catre {}: {}", host, e.getMessage());
            throw new SshExecutionException("Secventa de configurare SSH esuata catre " + host, e);
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    /** Verifica rapid daca portul SSH (22) e deschis - folosit la scanarea de subnet. */
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
        // unele imagini Junos/EOS de lab folosesc algoritmi mai vechi - le permitem explicit
        config.put("PreferredAuthentications", "password");
        session.setConfig(config);

        session.setTimeout(connectTimeoutMs);
        session.connect(connectTimeoutMs);
        return session;
    }

    private void waitForChannelClose(ChannelExec channel) {
        long deadline = System.currentTimeMillis() + commandTimeoutMs;
        while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
            sleepQuiet(100);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class SshExecutionException extends RuntimeException {
        public SshExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
