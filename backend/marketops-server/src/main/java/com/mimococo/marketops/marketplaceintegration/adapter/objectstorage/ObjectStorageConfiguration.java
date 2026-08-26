package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import com.mimococo.marketops.marketplaceintegration.adapter.secret.MountedSecretResolver;
import com.mimococo.marketops.marketplaceintegration.adapter.secret.SecretMountProperties;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.SecretResolverPort;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the custody provider this environment uses.
 *
 * <p>Exactly one adapter is created. The choice is configuration read at
 * startup, so a running process cannot change where evidence lives, and a
 * deployment that intends the managed store cannot silently fall back to a local
 * directory when its configuration is incomplete: the object-store adapter is
 * created and then refuses every operation until it is configured, which is
 * visible immediately rather than after evidence has been written to the wrong
 * place.
 */
@Configuration
@EnableConfigurationProperties({ObjectStorageProperties.class, SecretMountProperties.class})
public class ObjectStorageConfiguration {

    /** How long the client waits to establish a connection to the store. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Resolution of opaque secret references for every outbound adapter. */
    @Bean
    public SecretResolverPort secretResolverPort(SecretMountProperties properties) {
        return new MountedSecretResolver(properties.getMountDirectory());
    }

    /** The client the object-store adapter uses; never follows a redirect. */
    @Bean
    public HttpClient objectStorageHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The active custody provider. */
    @Bean
    public ObjectStoragePort objectStoragePort(ObjectStorageProperties properties,
                                               SecretResolverPort secretResolverPort,
                                               HttpClient objectStorageHttpClient,
                                               Clock clock) {
        return switch (properties.getProvider()) {
            case FILESYSTEM -> new FilesystemObjectStorage(requireRoot(properties));
            case OBJECT_STORE -> new S3CompatibleObjectStorage(
                    objectStorageHttpClient, properties, secretResolverPort, clock);
        };
    }

    private static java.nio.file.Path requireRoot(ObjectStorageProperties properties) {
        if (properties.getRootDirectory() == null) {
            throw new IllegalStateException(
                    "marketops.object-storage.root-directory must be configured"
                            + " for the filesystem custody provider");
        }
        return properties.getRootDirectory();
    }
}
