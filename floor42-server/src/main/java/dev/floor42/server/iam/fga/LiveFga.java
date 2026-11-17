package dev.floor42.server.iam.fga;

import io.quarkus.arc.DefaultBean;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The OpenFGA client the brokers use: each call goes to the client of the store and model {@link FgaStore} recorded, so
 * a store that is not ready refuses ({@link FgaException#UNAVAILABLE}) instead of answering. The tests replace this
 * bean with an in-memory one.
 */
@Singleton
@DefaultBean
class LiveFga implements FgaClient {

    private final FgaStore store;

    @Inject
    LiveFga(FgaStore store) {
        this.store = store;
    }

    @Override
    public boolean check(String user, String relation, String object, List<FgaTuple> contextual) {
        return store.client().check(user, relation, object, contextual);
    }

    @Override
    public void write(List<FgaTuple> writes, List<FgaTuple> deletes) {
        store.client().write(writes, deletes);
    }

    @Override
    public List<FgaTuple> read(String object) {
        return store.client().read(object);
    }
}
