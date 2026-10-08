ALTER TABLE demand_enriched
DROP
CONSTRAINT demand_enriched_classification_status_check;

ALTER TABLE demand_enriched
    ADD CONSTRAINT demand_enriched_classification_status_check
        CHECK (classification_status IN ('classified', 'unclassified', 'data_issue', 'out_of_scope'));
