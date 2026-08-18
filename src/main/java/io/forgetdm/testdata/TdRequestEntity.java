package io.forgetdm.testdata;

import jakarta.persistence.*;

import java.time.Instant;

/** One tester request: the plain-language text, the interpreted plan, and the provisioning receipt. */
@Entity
@Table(name = "td_request")
public class TdRequestEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_text", nullable = false) private String requestText;
    @Column(nullable = false) private String environment = "SIT";
    private String purpose;
    @Column(nullable = false) private int quantity = 1;
    @Column(nullable = false) private String status = "PLANNED";   // PLANNED | READY | FAILED
    @Column(name = "plan_json") private String planJson;
    @Column(name = "receipt_json") private String receiptJson;
    private String error;

    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(nullable = false) private String visibility = "GROUP";

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestText() { return requestText; }
    public void setRequestText(String v) { this.requestText = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { this.environment = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String v) { this.planJson = v; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String v) { this.receiptJson = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { this.ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { this.ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { this.ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
