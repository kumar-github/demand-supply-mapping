package dev.badprogrammer.demandsupplymapping.review.dto;

/**
 * The three-tier demand lifecycle discussed for the Review queue (not a schema column — computed fresh on every read
 * from demand_enriched.balance_positions, match_candidates, and demand_candidate_decisions, so it can never drift out
 * of sync with those):
 * <ul>
 *   <li>{@link #OPEN} — unfilled positions remain AND at least one Strong/Good candidate is still
 *       undecided. Stays in the main queue.</li>
 *   <li>{@link #FILLED} — approved decisions already cover every open position. Drops off the
 *       queue entirely (nothing left to decide).</li>
 *   <li>{@link #EXHAUSTED} — unfilled positions remain, but every Strong/Good candidate has
 *       already been Proposed or Rejected. Stays visible (tagged, per the wireframe's "needs
 *       hiring" sample row) because the demand is still open — it just can't be closed from
 *       inside this app any more; it's a hiring-gap signal, not a dead end.</li>
 * </ul>
 */
public enum DemandLifecycleState {
    OPEN, FILLED, EXHAUSTED
}
