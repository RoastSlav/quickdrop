package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.ShareTokenEntity;

public record ShareTokenResult(ShareTokenEntity token, String shareKey) {
}
