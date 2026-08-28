package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where immutable Raw bytes are kept in this environment.
 *
 * <p>Two providers are supported and both are content-addressed and write-once.
 * The filesystem provider serves a single-node deployment and a workstation; the
 * object-store provider serves the managed environment. Which one is active is a
 * deployment decision recorded here, never a runtime one, so a process cannot
 * change where evidence lives while it is running.
 *
 * <p>No secret appears in this binding. The object-store provider names its
 * credential by opaque reference and resolves it, at the moment of use, through
 * the secret port.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.object-storage")
public final class ObjectStorageProperties {

    /** The custody providers this deployment can be configured to use. */
    public enum Provider {

        /** A local directory, content-addressed and write-once. */
        FILESYSTEM,

        /** An S3-compatible managed object store. */
        OBJECT_STORE
    }

    @NotNull
    private Provider provider = Provider.FILESYSTEM;

    private Path rootDirectory;

    @Pattern(regexp = "https://[a-z0-9][a-z0-9.-]{0,252}",
            message = "the object-store endpoint must be an https location")
    private String endpoint;

    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$")
    private String region;

    @Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,62}$")
    private String bucket;

    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private String accessKeyId;

    @Pattern(regexp = "^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$",
            message = "the object-store credential must be named by an opaque reference")
    private String credentialReference;

    /** Which custody provider is active. */
    public Provider getProvider() {
        return provider;
    }

    /** Bind the active custody provider. */
    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    /** Root of the filesystem store, or {@code null} for the object store. */
    public Path getRootDirectory() {
        return rootDirectory;
    }

    /** Bind the filesystem store root. */
    public void setRootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    /** Object-store endpoint, or {@code null} for the filesystem store. */
    public String getEndpoint() {
        return endpoint;
    }

    /** Bind the object-store endpoint. */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Object-store region used when signing. */
    public String getRegion() {
        return region;
    }

    /** Bind the object-store region. */
    public void setRegion(String region) {
        this.region = region;
    }

    /** Bucket that holds Raw evidence. */
    public String getBucket() {
        return bucket;
    }

    /** Bind the bucket. */
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    /**
     * Public half of the object-store credential.
     *
     * <p>An access key identifier is not secret: it names a credential without
     * being able to authenticate one. The half that can is never in
     * configuration and is resolved by reference at the moment of use.
     */
    public String getAccessKeyId() {
        return accessKeyId;
    }

    /** Bind the access key identifier. */
    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    /** Opaque reference to the secret half of the object-store credential. */
    public String getCredentialReference() {
        return credentialReference;
    }

    /** Bind the credential reference. */
    public void setCredentialReference(String credentialReference) {
        this.credentialReference = credentialReference;
    }
}
