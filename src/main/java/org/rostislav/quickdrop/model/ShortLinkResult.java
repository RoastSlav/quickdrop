package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.UploadShareLink;

public record ShortLinkResult(UploadShareLink link, String shareKey) {
}
