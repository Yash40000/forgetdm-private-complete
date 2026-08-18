package io.forgetdm.testdata;

import jakarta.persistence.*;

import java.time.Instant;

/** A business-asset the tester can ask for, in business terms (the self-service catalog). */
@Entity
@Table(name = "td_recipe")
public class TdRecipeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "recipe_key", nullable = false) private String recipeKey;
    @Column(nullable = false) private String name;
    private String description;
    @Column(name = "asset_type", nullable = false) private String assetType;
    @Column(nullable = false) private String keywords;
    @Column(name = "attributes_json") private String attributesJson;
    @Column(name = "backing_json") private String backingJson;
    @Column(nullable = false) private boolean anchor;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipeKey() { return recipeKey; }
    public void setRecipeKey(String v) { this.recipeKey = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String v) { this.assetType = v; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String v) { this.keywords = v; }
    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String v) { this.attributesJson = v; }
    public String getBackingJson() { return backingJson; }
    public void setBackingJson(String v) { this.backingJson = v; }
    public boolean isAnchor() { return anchor; }
    public void setAnchor(boolean v) { this.anchor = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
