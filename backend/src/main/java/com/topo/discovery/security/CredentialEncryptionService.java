package com.topo.discovery.security;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cripteaza/decripteaza credentialele device-urilor (SSH password, SNMP community)
 * inainte de a fi salvate in / citite din baza de date.
 *
 * Foloseste un PBEStringEncryptor dedicat (separat de cel folosit de Jasypt
 * pentru application.yml), cu cheia luata din aceeasi variabila de mediu
 * JASYPT_ENCRYPTOR_PASSWORD, pentru simplitate operationala.
 *
 * IMPORTANT: cheia (JASYPT_ENCRYPTOR_PASSWORD) NU trebuie hardcodata si NU
 * trebuie comisa in Git. Se seteaza ca variabila de mediu pe server / in
 * fisierul .env (vezi README -> sectiunea Deploy pe Proxmox).
 */
@Service
public class CredentialEncryptionService {

    private final PooledPBEStringEncryptor encryptor;

    public CredentialEncryptionService(@Value("${jasypt.encryptor.password}") String encryptionKey) {
        this.encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword(encryptionKey);
        config.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        config.setKeyObtentionIterations(1000);
        config.setPoolSize(2);
        config.setProviderName("SunJCE");
        config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        config.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        config.setStringOutputType("base64");
        this.encryptor.setConfig(config);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        return encryptor.encrypt(plainText);
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return null;
        }
        return encryptor.decrypt(encryptedText);
    }
}
