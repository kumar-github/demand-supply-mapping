package dev.badprogrammer.demandsupplymapping.review.dto;

/**
 * POST body for recording a Propose/Reject/Override decision. {@code action} is "approved" (Propose), "rejected"
 * (Reject), or "overridden" (Override) — matching demand_candidate_decisions.status's existing values, rather than
 * inventing separate UI-only verbs.
 */
public record DecisionRequest(String action) {
}
