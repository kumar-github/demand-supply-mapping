package dev.badprogrammer.demandsupplymapping.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "refresh_runs")
public class RefreshRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "source", nullable = false)
    private String source; // supply | demand | matching — CHECK-constrained

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "status", nullable = false)
    private String status = "running"; // running | succeeded | failed

    // Added 2026-09-28 for RefreshService — full_db_design.sql's
    // refresh_runs table has these columns but the entity didn't yet.
    @Column(name = "rows_in")
    private Integer rowsIn;

    @Column(name = "rows_new")
    private Integer rowsNew;

    @Column(name = "rows_changed")
    private Integer rowsChanged;

    @Column(name = "rows_flagged")
    private Integer rowsFlagged;

    @Column(name = "error_message")
    private String errorMessage;

    protected RefreshRun() {
    }

    public RefreshRun(String source) {
        this.source = source;
    }

    public RefreshRun(String source, String fileName) {
        this.source = source;
        this.fileName = fileName;
    }

    public Long getRunId() {
        return runId;
    }

    public String getSource() {
        return source;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRowsIn() {
        return rowsIn;
    }

    public void setRowsIn(Integer rowsIn) {
        this.rowsIn = rowsIn;
    }

    public Integer getRowsNew() {
        return rowsNew;
    }

    public void setRowsNew(Integer rowsNew) {
        this.rowsNew = rowsNew;
    }

    public Integer getRowsChanged() {
        return rowsChanged;
    }

    public void setRowsChanged(Integer rowsChanged) {
        this.rowsChanged = rowsChanged;
    }

    public Integer getRowsFlagged() {
        return rowsFlagged;
    }

    public void setRowsFlagged(Integer rowsFlagged) {
        this.rowsFlagged = rowsFlagged;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
