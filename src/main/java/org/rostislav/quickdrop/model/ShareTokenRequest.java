package org.rostislav.quickdrop.model;

public class ShareTokenRequest {
    public String token; // full token (publicId + secret) for client-side construction
    public String publicId;
    public String wrappedDek;
    public String wrapNonce;
    public String secretHash;
    public Integer encryptionVersion;
    public String tokenMode;
}
