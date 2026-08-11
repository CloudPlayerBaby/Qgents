package qg.qgent.security;

import java.util.UUID;

public interface CurrentActorProvider {
    UUID currentUserId();
}
