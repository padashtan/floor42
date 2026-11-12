package dev.floor42.server.api;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where a mission's accepted deliverables land (ADR-0051): the folder chosen at creation, and the copy once made. Null
 * for a mission created without a folder (nothing is copied, as before 1.10.0).
 *
 * @param destination the folder the director chose, absolute
 * @param copy what was copied when the director accepted the delivery; null until then
 */
public record DeliveryHomeView(String destination, DeliveryCopyView copy) {

    /**
     * @param folder the mission's folder in the destination, where the files were written
     * @param files each file's outcome: WRITTEN, KEPT_BOTH (written beside a different file of that name), SAME (the
     *     same bytes were there) or LEFT_OUT with its reason
     * @param leftOut files past the journaled list, left out
     * @param truncated a listing of the mission was cut at its cap: some files were not considered
     */
    public record DeliveryCopyView(
            String folder, List<DeliveredCopyFile> files, int leftOut, boolean truncated, String at) {
        public DeliveryCopyView {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    public record DeliveredCopyFile(String agent, String path, String writtenAs, String outcome, String reason) {}

    static DeliveryHomeView of(Company c, MissionId m) {
        return c.deliveries()
                .destination(m)
                .map(d -> new DeliveryHomeView(
                        d,
                        c.deliveries()
                                .copy(m)
                                .map(x -> new DeliveryCopyView(
                                        x.folder(),
                                        x.files().stream()
                                                .map(f -> new DeliveredCopyFile(
                                                        f.agent(), f.path(), f.writtenAs(), f.outcome(), f.reason()))
                                                .toList(),
                                        x.leftOut(),
                                        x.truncated(),
                                        x.at().toString()))
                                .orElse(null)))
                .orElse(null);
    }
}
