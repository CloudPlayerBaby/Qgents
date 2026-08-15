package qg.qgent.github;

/**
 * Minimal GitHub Check Run summary for later quality-gate mapping.
 */
public record GitHubCheckRunDetails(
        /* GitHub Check Run identifier. */
        long id,
        /* Check name as configured by its provider. */
        String name,
        /* GitHub execution status. */
        String status,
        /* GitHub terminal conclusion, if available. */
        String conclusion
) {
}
